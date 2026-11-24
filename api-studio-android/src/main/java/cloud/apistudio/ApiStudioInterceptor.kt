package cloud.apistudio

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Logs every request an OkHttp client makes (Retrofit included) to API Studio.
 *
 * ```
 * val client = OkHttpClient.Builder().addInterceptor(ApiStudioInterceptor()).build()
 * ```
 *
 * Add it with `addInterceptor` (not `addNetworkInterceptor`) so it sees decoded bodies and
 * one log per call, including calls that fail without a response.
 * Response bodies are read with `peekBody`, so the app's stream is never consumed.
 * It does nothing until [ApiStudio.init] has run.
 */
public class ApiStudioInterceptor internal constructor(
    private val client: () -> ApiStudioClient?,
) : Interceptor {

    public constructor() : this({ ApiStudio.client })

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val studio = client()
        val url = request.url.toString()
        if (studio == null || !safe(false) { studio.shouldCapture(url) }) return chain.proceed(request)

        val maxBytes = studio.options.maxBodyBytes
        val startedAt = System.currentTimeMillis()
        val started = System.nanoTime()
        val requestBody = safe(CapturedBody.NONE) { captureRequestBody(request, maxBytes) }
        val requestHeaders = safe(null) { requestHeaderMap(request) }

        val response = try {
            chain.proceed(request)
        } catch (error: Exception) {
            safe(Unit) {
                studio.recordRequest(
                    RecordedRequest(
                        method = request.method,
                        url = url,
                        startedAtMs = startedAt,
                        durationMs = elapsedMs(started),
                        requestHeaders = requestHeaders,
                        requestBody = requestBody,
                        statusCode = 0,
                        error = "${error.javaClass.simpleName}: ${error.message ?: ""}".trimEnd(' ', ':'),
                        cancelled = chain.call().isCanceled(),
                    ),
                )
            }
            throw error
        }

        safe(Unit) {
            studio.recordRequest(
                RecordedRequest(
                    method = request.method,
                    url = url,
                    startedAtMs = startedAt,
                    durationMs = elapsedMs(started),
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    statusCode = response.code,
                    responseHeaders = headerMap(response.headers),
                    responseBody = captureResponseBody(response, maxBytes),
                    cancelled = chain.call().isCanceled(),
                ),
            )
        }
        return response
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        fallback // The SDK never breaks the app's request.
    }
}

private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

internal fun headerMap(headers: Headers): MutableMap<String, String> {
    val result = LinkedHashMap<String, String>()
    for (name in headers.names()) result[name] = headers.values(name).joinToString(", ")
    return result
}

/**
 * OkHttp adds Content-Type and Content-Length from the body only after application interceptors
 * run, so they are filled in here to show what is actually sent.
 */
internal fun requestHeaderMap(request: Request): Map<String, String> {
    val headers = headerMap(request.headers)
    val body = request.body ?: return headers
    val names = headers.keys.map { it.lowercase() }
    if ("content-type" !in names) body.contentType()?.let { headers["Content-Type"] = it.toString() }
    if ("content-length" !in names) body.contentLength().takeIf { it >= 0 }?.let { headers["Content-Length"] = it.toString() }
    return headers
}

/** Text bodies above this size are not copied into memory at all; only their size is logged. */
private const val MAX_REQUEST_COPY_BYTES = 1024L * 1024L

internal fun captureRequestBody(request: Request, maxBytes: Int): CapturedBody {
    val body = request.body ?: return CapturedBody.NONE
    val length = body.contentLength()
    val size = if (length >= 0) length else null
    // One-shot and duplex bodies can be written only once, and that write belongs to OkHttp.
    if (body.isOneShot() || body.isDuplex()) return CapturedBody(null, size, false)
    if (!isTextual(body.contentType())) return CapturedBody(null, size, false)
    if (length > MAX_REQUEST_COPY_BYTES) return CapturedBody(null, size, true)
    val buffer = Buffer()
    body.writeTo(buffer)
    val total = buffer.size
    val truncated = total > maxBytes
    val text = if (truncated) buffer.readUtf8(maxBytes.toLong()) else buffer.readUtf8()
    return captureText(text, total, truncated)
}

internal fun captureResponseBody(response: Response, maxBytes: Int): CapturedBody {
    val body = response.body ?: return CapturedBody.NONE
    val length = body.contentLength()
    val contentType = body.contentType() ?: response.header("Content-Type")?.let { parseMediaType(it) }
    if (!isTextual(contentType)) return CapturedBody(null, if (length >= 0) length else null, false)
    // Peek one byte past the cap to learn whether the body is longer, without consuming it.
    val peeked = response.peekBody(maxBytes.toLong() + 1).bytes()
    val truncated = peeked.size > maxBytes
    val text = String(peeked, 0, minOf(peeked.size, maxBytes), Charsets.UTF_8)
    val size = when {
        length >= 0 -> length
        !truncated -> peeked.size.toLong()
        else -> null
    }
    return captureText(text, size, truncated)
}

private fun isTextual(type: MediaType?): Boolean = isTextualContentType(type?.toString())

private fun parseMediaType(value: String): MediaType? = value.toMediaTypeOrNull()
