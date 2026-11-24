package cloud.apistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** What the uploader does with a batch after the server answered. */
internal enum class Outcome { ACCEPTED, DROPPED, TOO_LARGE, RETRY_LATER }

/** Retry rules from the protocol: drop what can never succeed, keep the rest. */
internal fun classify(status: Int): Outcome = when {
    status in 200..299 -> Outcome.ACCEPTED
    status == 413 -> Outcome.TOO_LARGE
    status == 400 || status == 404 || status == 410 || status == 422 -> Outcome.DROPPED
    else -> Outcome.RETRY_LATER // 401/403, 408, 429, 5xx
}

/** Sends one JSON request body and returns the HTTP status. Throws IOException when there is no response. */
internal fun interface Transport {
    @Throws(IOException::class)
    fun post(url: String, apiKey: String, json: String): Int
}

/**
 * Uploads with HttpURLConnection, never with the app's OkHttp client, so uploads never pass
 * through [ApiStudioInterceptor]. Bodies are gzipped. Each header is set exactly once.
 */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Transport {
    override fun post(url: String, apiKey: String, json: String): Int {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            // Drain so the connection can be reused.
            try {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
            } catch (_: IOException) {
            }
            return status
        } finally {
            connection.disconnect()
        }
    }
}

internal const val MIN_BACKOFF_MS = 1_000L
internal const val MAX_BACKOFF_MS = 5 * 60 * 1_000L

/** 1 s, 2 s, 4 s … capped at 5 min, as the protocol specifies. */
internal fun backoffMs(failures: Int): Long =
    minOf(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl (failures - 1).coerceIn(0, 20))

/** Sends one queue in batches, one request at a time, with exponential backoff. Not thread-safe: call on one thread. */
internal class Uploader(
    private val url: String,
    private val apiKey: String,
    private val transport: Transport,
    private val queue: PersistentQueue,
    /** The body key the batch goes under: `logs` or `events`. */
    private val wrapKey: String,
    private val defaultBatchSize: Int,
    /** A smaller copy of an item that was rejected as too large, or null to drop it. */
    private val shrink: (JSONObject) -> JSONObject?,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    var retryAt: Long = 0
        private set
    private var batchSize = defaultBatchSize

    /** Sends everything queued. Returns when the queue is empty or a retry is pending. */
    fun flush(force: Boolean = false) {
        if (!force && clock() < retryAt) return
        while (queue.size > 0) {
            val batch = queue.peek(batchSize)
            if (batch.isEmpty()) return
            val outcome = try {
                classify(transport.post(url, apiKey, JSONObject().put(wrapKey, JSONArray(batch)).toString()))
            } catch (_: Exception) {
                Outcome.RETRY_LATER // offline, DNS, timeout
            }
            when (outcome) {
                Outcome.ACCEPTED, Outcome.DROPPED -> {
                    if (outcome == Outcome.DROPPED) log("Dropped ${batch.size} rejected item(s).")
                    queue.drop(batch.size)
                    failures = 0
                    retryAt = 0
                    batchSize = defaultBatchSize
                }
                Outcome.TOO_LARGE -> {
                    if (batch.size > 1) {
                        batchSize = maxOf(1, batch.size / 2)
                    } else {
                        // shrink() returns null for an item it already shrank, so this ends.
                        val smaller = shrink(batch[0])
                        if (smaller != null) {
                            queue.replaceFirst(smaller)
                            log("Retrying an oversized item without bodies.")
                        } else {
                            queue.drop(1)
                        }
                    }
                }
                Outcome.RETRY_LATER -> {
                    failures++
                    val delay = backoffMs(failures)
                    retryAt = clock() + delay
                    log("Upload paused; retrying in ${delay / 1000} s.")
                    return
                }
            }
        }
    }
}
