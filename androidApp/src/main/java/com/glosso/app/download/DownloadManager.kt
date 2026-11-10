package com.glosso.app.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import android.util.Log
import com.glosso.app.platform.platformConnectivity
import androidx.core.content.ContextCompat
import com.glosso.app.Analytics
import com.glosso.app.OfflineStore
import com.glosso.app.OnDeviceScorer
import com.glosso.app.downloadFailureReason
import com.glosso.app.downloadSizeMb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem

/**
 * The app's single front door to downloads. It owns the [DownloadEngine], watches
 * connectivity (a callback, never a poll), applies the Wi-Fi-only policy and
 * keeps the foreground service alive while there is work. Every download in the
 * app - the acoustic model, a lesson's audio and art - goes through here, so
 * there is one queue, one notification and one set of rules.
 *
 * The completion hooks feed the existing stores: a finished lesson is committed
 * to [OfflineStore], a finished model to [OnDeviceScorer]. The screens read
 * those stores exactly as before, so nothing parallel was invented.
 */
object DownloadManager : Downloader {

    private const val TAG = "GlossoDownload"
    private const val MODEL_ID = DownloadIds.MODEL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var foregroundEnabled = false

    @Volatile
    private var connectivityRegistered = false

    private var engine: DownloadEngine? = null

    private val lock = Any()

    override val state: StateFlow<DownloadsState>
        get() = engineOrCreate().state

    fun init(context: Context) {
        appContext = context.applicationContext
        WifiOnlyPreference.init(context.applicationContext)
        engineOrCreate()
        // The worker parks on `wake` while the network is blocked; this callback
        // is the only thing that wakes it when Wi-Fi comes back, so it must be
        // registered exactly once or a Wi-Fi-only download never starts.
        registerConnectivity()
    }

    /**
     * From this point the manager may raise the foreground service. Called by the
     * Activity, which is the foreground context Android requires for a dataSync
     * service; tests never enable it and run the engine in-process.
     */
    fun enableForeground() {
        foregroundEnabled = true
        engineOrCreate()
        registerConnectivity()
    }

    fun modelItemId(): String = MODEL_ID
    fun lessonItemId(lessonId: String): String = DownloadIds.lesson(lessonId)

    /** Enqueues an item (resuming a known one) and raises the service if needed. */
    override fun enqueue(item: DownloadItem): Deferred<DownloadOutcome> {
        engineOrCreate().clearFinished()
        // A resume of an item still in the queue is not a new download.
        if (engineOrCreate().state.value.item(item.id)?.isActive != true) {
            trackDownload("download_started", item, "size_mb" to downloadSizeMb(expectedBytes(item)))
        }
        val handle = engineOrCreate().enqueue(item)
        maybeStartService()
        return handle
    }

    override fun pause(id: String) = engineOrCreate().pause(id)
    override fun resume(id: String) = engineOrCreate().resume(id)
    override fun cancel(id: String) {
        engineOrCreate().state.value.item(id)?.takeIf { it.isActive }?.let(::trackCancelled)
        engineOrCreate().cancel(id)
    }

    fun pauseAll() = engineOrCreate().pauseAll()
    fun resumeAll() = engineOrCreate().resumeAll()
    fun cancelAll() {
        engineOrCreate().state.value.active.forEach(::trackCancelled)
        engineOrCreate().cancelAll()
    }

    /** The learner accepts a metered connection for one item, from the notification. */
    override fun allowMetered(id: String) {
        engineOrCreate().allowMetered(id)
        maybeStartService()
    }

    override fun wifiOnlyChanged() {
        engineOrCreate().networkChanged()
    }

    // --------------------------------------------------------------- internals

    private fun engineOrCreate(): DownloadEngine = synchronized(lock) {
        engine ?: DownloadEngine(
            scope = scope,
            fileSystem = FileSystem.SYSTEM,
            onChanged = { snapshot ->
                OnDeviceScorer.onQueueState(snapshot.item(MODEL_ID))
                OfflineStore.onQueueState(snapshot.active.firstOrNull { it.kind == DownloadKind.LESSON })
            },
            onCompleted = { item ->
                val bytes = expectedBytes(item) ?: item.files.sumOf { it.target.toFile().length() }
                trackDownload("download_completed", item, "size_mb" to downloadSizeMb(bytes))
                when (item.kind) {
                    DownloadKind.MODEL -> OnDeviceScorer.onDownloadFinished()
                    DownloadKind.LESSON -> OfflineStore.commitDownload(lessonIdOf(item.id))
                }
            },
            onFailed = { item, failure ->
                trackDownload("download_failed", item, "reason" to downloadFailureReason(lastRawError))
                when (item.kind) {
                    DownloadKind.MODEL -> OnDeviceScorer.onDownloadFailed(failure)
                    DownloadKind.LESSON -> OfflineStore.onDownloadFailed(lessonIdOf(item.id), failure)
                }
            },
            networkAllowed = ::networkAllowed,
            classifyError = { raw ->
                // Keep the raw transport error in the log: the UI shows friendly
                // copy, but a failure must stay diagnosable without a debugger.
                Log.w(TAG, "download failed: $raw")
                // Items run one at a time and onFailed follows this call on the
                // same coroutine, so it reads the reason of its own failure.
                lastRawError = raw
                DownloadFailure.of(raw)
            },
        ).also { engine = it }
    }

    private fun lessonIdOf(itemId: String): String = DownloadIds.lessonIdOf(itemId)

    /** The raw transport error of the last failure, reduced to a reason id before it is reported. */
    @Volatile
    private var lastRawError: String? = null

    private fun expectedBytes(item: DownloadItem): Long? =
        item.files.map { it.expectedBytes }.takeIf { sizes -> sizes.isNotEmpty() && sizes.all { it != null && it > 0 } }
            ?.sumOf { it ?: 0L }

    /** One download event: never the title, URL or error text, only the kind and the lesson id. */
    private fun trackDownload(name: String, item: DownloadItem, extra: Pair<String, Any?>) {
        Analytics.track(name, downloadProps(item.kind, item.id) + extra)
    }

    private fun trackCancelled(item: DownloadItemState) {
        Analytics.track("download_failed", downloadProps(item.kind, item.id) + ("reason" to "cancelled"))
    }

    private fun downloadProps(kind: DownloadKind, id: String): Map<String, Any?> = when (kind) {
        DownloadKind.MODEL -> mapOf("kind" to "model")
        DownloadKind.LESSON -> mapOf("kind" to "lesson", "lesson_id" to lessonIdOf(id))
    }

    private fun networkAllowed(item: DownloadItem): Boolean {
        val context = appContext
        // No context yet, or running in-process for tests: never block the queue.
        if (context == null || !foregroundEnabled) return true
        val connectivity = platformConnectivity(context)
        if (!connectivity.isOnline()) return false
        if (item.allowMetered) return true
        if (!WifiOnlyPreference.enabled.value) return true
        return connectivity.isUnmetered()
    }

    private fun maybeStartService() {
        val context = appContext ?: return
        if (!foregroundEnabled) return
        if (!engineOrCreate().state.value.hasWork) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }.onFailure { error ->
            // Android 12+ forbids raising the service from the background. The
            // download still runs while the process lives; it just is not pinned.
            Log.w(TAG, "could not raise the download service", error)
        }
    }

    private fun registerConnectivity() {
        if (connectivityRegistered) return
        val context = appContext ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        synchronized(lock) {
            if (connectivityRegistered) return
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: AndroidNetwork) = engineOrCreate().networkChanged()
                override fun onLost(network: AndroidNetwork) = engineOrCreate().networkChanged()
                override fun onCapabilitiesChanged(network: AndroidNetwork, capabilities: NetworkCapabilities) =
                    engineOrCreate().networkChanged()
            })
            connectivityRegistered = true
        }
    }

    /** Drops the singleton wiring so a fresh init can be tested in isolation. */
    internal fun resetForTest() {
        synchronized(lock) {
            engine = null
            connectivityRegistered = false
        }
        appContext = null
        foregroundEnabled = false
    }
}
