package com.glosso.app.download

import com.glosso.app.resources.*
import kotlin.concurrent.Volatile
import okio.Path
import org.jetbrains.compose.resources.StringResource

/**
 * Why a download failed, as the learner is told it. Typed so each surface (the
 * shared UI, the Android notification) words it from the same resources.
 */
enum class DownloadFailure(val message: StringResource) {
    DAMAGED(Res.string.download_error_damaged),
    REJECTED(Res.string.download_error_rejected),
    SERVER(Res.string.download_error_server),
    SPACE(Res.string.download_error_space),
    NETWORK(Res.string.download_error_network),
    GENERIC(Res.string.download_error_generic),
    ;

    companion object {
        /** Reads a raw transport error (an exception message). */
        fun of(raw: String): DownloadFailure {
            val lower = raw.lowercase()
            return when {
                lower.contains("checksum") || lower.contains("incomplete") || lower.contains("size") -> DAMAGED
                lower.contains("http 4") -> REJECTED
                lower.contains("http 5") -> SERVER
                lower.contains("space") || lower.contains("enospc") -> SPACE
                lower.contains("unknownhost") || lower.contains("timeout") || lower.contains("connect") ||
                    lower.contains("network") || lower.contains("unreachable") -> NETWORK
                else -> GENERIC
            }
        }
    }
}

/** What a queued download belongs to. Used for ordering and completion hooks. */
enum class DownloadKind { MODEL, LESSON }

/**
 * The queue is ordered by this first and insertion second, so a small explicit
 * model download never gets stuck behind a long lesson pack, and finishing one
 * lesson before starting the next keeps the network usage bounded.
 */
enum class DownloadPriority(val rank: Int) { HIGH(0), NORMAL(1), LOW(2) }

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, WAITING_NETWORK, FAILED, DONE, CANCELLED }

/**
 * One file in an item's plan. [fetch] is what actually pulls the bytes, so the
 * engine is agnostic of the transport and tests can enqueue a fake. The
 * callback reports bytes written and total bytes (null when unknown).
 */
class DownloadFile(
    val url: String,
    val target: Path,
    val expectedBytes: Long? = null,
    val expectedSha256: String? = null,
    val fetch: suspend (onProgress: (Long, Long?) -> Unit) -> Unit,
)

/** A user-visible download: the model pack, or one lesson's assets. */
class DownloadItem(
    val id: String,
    val kind: DownloadKind,
    val title: String,
    val priority: DownloadPriority,
    val files: List<DownloadFile>,
    /** Set when the learner explicitly accepts a metered connection for this item. */
    @Volatile var allowMetered: Boolean = false,
)

/** The slice of an item the notification and the UI read. */
data class DownloadItemState(
    val id: String,
    val kind: DownloadKind,
    val title: String,
    val status: DownloadStatus,
    val bytesWritten: Long = 0,
    val bytesTotal: Long? = null,
    /** Why it failed, when [status] is [DownloadStatus.FAILED]. */
    val failure: DownloadFailure? = null,
    /** Files finished, and how many there are, for a size-less fallback bar. */
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.DONE -> 1f
            bytesTotal != null && bytesTotal > 0 -> (bytesWritten.toFloat() / bytesTotal).coerceIn(0f, 1f)
            // Without every size the byte total is unknown, but the item is still
            // moving: show finished files so the bar is never a silent 0%.
            filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            else -> 0f
        }

    val isTerminal: Boolean
        get() = status == DownloadStatus.DONE ||
            status == DownloadStatus.CANCELLED ||
            status == DownloadStatus.FAILED

    val isActive: Boolean get() = !isTerminal
}

data class DownloadsState(val items: List<DownloadItemState> = emptyList()) {
    /** Items still doing something, in display order. */
    val active: List<DownloadItemState> get() = items.filter { it.isActive }

    /**
     * The active downloads that are not the model. The offline drawer gives the
     * model its own card, so it must not also show up in the "Downloading"
     * section: that would paint two bars and two cancel buttons for one download.
     */
    val activeLessons: List<DownloadItemState> get() = active.filter { it.kind == DownloadKind.LESSON }

    /** Items actually transferring right now. */
    val running: List<DownloadItemState> get() = items.filter { it.status == DownloadStatus.RUNNING }

    val hasWork: Boolean get() = items.any { !it.isTerminal }

    fun item(id: String): DownloadItemState? = items.firstOrNull { it.id == id }

    /**
     * One honest overall fraction for the group notification. Weighs by known
     * byte totals when every active item knows its size, otherwise falls back to
     * completed files, so the bar never jumps backwards.
     */
    val overallProgress: Float
        get() {
            val active = active
            if (active.isEmpty()) return 0f
            val sized = active.filter { it.bytesTotal != null && it.bytesTotal > 0 }
            if (sized.size == active.size) {
                val total = sized.sumOf { it.bytesTotal ?: 0L }
                val written = sized.sumOf { it.bytesWritten }
                return if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
            }
            return active.map { it.progress }.average().toFloat()
        }
}
