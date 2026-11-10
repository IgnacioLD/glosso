package com.glosso.server.tts

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP seam so providers are unit-testable without a live API and without
 * pulling an HTTP client into the test path. Production uses the JDK client.
 */
interface HttpTransport : AutoCloseable {

    data class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            return method == other.method && url == other.url && headers == other.headers &&
                (body?.contentEquals(other.body) ?: (other.body == null))
        }

        override fun hashCode(): Int =
            31 * (31 * (31 * method.hashCode() + url.hashCode()) + headers.hashCode()) +
                (body?.contentHashCode() ?: 0)
    }

    data class Response(
        val status: Int,
        val headers: Map<String, List<String>> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()

        fun headerValues(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()

        fun bodyText(): String = body.toString(Charsets.UTF_8)
    }

    fun send(request: Request): Response

    override fun close() {}
}

/**
 * JDK HttpClient transport. Maps socket failures to transient TTS errors and
 * respects the configured timeout.
 */
class JdkHttpTransport(private val timeout: Duration) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun send(request: HttpTransport.Request): HttpTransport.Response {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(timeout)
        for ((name, value) in request.headers) builder.header(name, value)
        val body = request.body
        if (body != null) {
            builder.method(request.method, HttpRequest.BodyPublishers.ofByteArray(body))
        } else {
            builder.method(request.method, HttpRequest.BodyPublishers.noBody())
        }
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw TtsException(TtsFailureKind.TIMEOUT, "HTTP timeout calling ${request.url}", cause = e)
        } catch (e: java.io.IOException) {
            throw TtsException(TtsFailureKind.CONNECTION, "HTTP connection error calling ${request.url}", cause = e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TtsException(TtsFailureKind.CONNECTION, "HTTP call interrupted", cause = e)
        }
        return HttpTransport.Response(
            status = response.statusCode(),
            headers = response.headers().map(),
            body = response.body(),
        )
    }
}
