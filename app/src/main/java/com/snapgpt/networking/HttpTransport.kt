package com.snapgpt.networking

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** Minimal HTTP abstraction so every provider adapter can be tested against a local mock server. */
interface HttpTransport {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponse
}

/** Plain java.net implementation (no third-party HTTP dependency). Cancelling the coroutine closes the connection. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponse = suspendCancellableCoroutine { cont ->
        val conn = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { conn.disconnect() } }
        val worker = Thread {
            try {
                cont.resume(doRequest(conn, method, headers, body, connectTimeoutMs, readTimeoutMs))
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
        worker.isDaemon = true
        worker.name = "snapgpt-http"
        worker.start()
    }

    private fun doRequest(
        conn: HttpURLConnection,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponse {
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val h = HashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) h[k.lowercase()] = v.first() }
            return HttpResponse(status, h, text)
        } finally {
            conn.disconnect()
        }
    }
}
