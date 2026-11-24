package cloud.apistudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val MAX_BREADCRUMBS = 100
internal const val MAX_BREADCRUMB_MESSAGE = 300
internal const val MAX_STACK_CHARS = 60_000
internal const val MAX_MESSAGE_CHARS = 4_000
internal const val LOG_BATCH = 50
internal const val ERROR_BATCH = 20

/** A trail entry attached to every error event (the last 100 are kept, oldest first). */
public data class Breadcrumb @JvmOverloads constructor(
    /** `http`, `navigation`, `log`, `user`, or your own. */
    val category: String,
    val message: String? = null,
    /** `debug`, `info`, `warning`, or `error`. */
    val level: String? = null,
    val data: Map<String, Any?>? = null,
    /** ISO 8601; defaults to now. */
    val timestamp: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp ?: isoNow())
        .put("category", category)
        .putIfNotNull("message", message)
        .putIfNotNull("level", level)
        .putIfNotNull("data", data)
}

/** One finished HTTP request, as an instrumentation saw it. */
internal class RecordedRequest(
    val method: String,
    val url: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: CapturedBody = CapturedBody.NONE,
    val statusCode: Int = 0,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: CapturedBody = CapturedBody.NONE,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/**
 * The SDK engine: context, breadcrumbs, the two persistent queues and their uploaders.
 * Apps use [ApiStudio]; this class has no Android dependencies so it can be unit tested on the JVM.
 * Every public method swallows its own errors: the SDK never crashes or blocks the host app.
 */
public class ApiStudioClient internal constructor(
    private val apiKey: String,
    internal val options: ApiStudioOptions,
    /** Where queues, crashes and the install id live; null keeps everything in memory. */
    storageDir: File?,
    private val sdk: Map<String, String>,
    private val deviceProvider: () -> Map<String, Any?>,
    transport: Transport = HttpUrlConnectionTransport(),
    private val executor: ScheduledExecutorService = newExecutor(),
    private val logger: (String) -> Unit = {},
) {
    public val sessionId: String = UUID.randomUUID().toString()
    internal val baseUrl: String = options.baseUrl.trimEnd('/')
    internal val redactor = Redactor(options.redactHeaders, options.redactKeys, options.includeDefaultRedaction)
    @Volatile private var user: UserInfo? = options.user
    @Volatile private var app: AppInfo? = options.app
    @Volatile private var installId: String? = null
    private val installIdFile = storageDir?.let { File(it, "install_id") }
    private val device: Map<String, Any?> by lazy {
        try {
            deviceProvider()
        } catch (_: Throwable) {
            emptyMap() // A missing system API must never break logging.
        }
    }
    private val breadcrumbs = ArrayDeque<JSONObject>()
    internal val logQueue = PersistentQueue(storageDir?.let { File(it, "logs.json") }, options.maxQueueSize, executor, ::onStorageError)
    internal val errorQueue = PersistentQueue(storageDir?.let { File(it, "errors.json") }, options.maxQueueSize, executor, ::onStorageError)
    internal val crashStore: CrashStore? = storageDir?.let { CrashStore(File(it, "crashes"), ::onStorageError) }
    private val logUploader = Uploader(
        "$baseUrl/api/v1/logs/batch", apiKey, transport, logQueue, "logs", LOG_BATCH, ::shrinkLog, ::debug,
    )
    private val errorUploader = Uploader(
        "$baseUrl/api/v1/errors/batch", apiKey, transport, errorQueue, "events", ERROR_BATCH, ::shrinkError, ::debug,
    )
    private var timer: ScheduledFuture<*>? = null

    public val enabled: Boolean get() = options.enabled

    /** Loads what the last launch left (queues, crashes, install id) and starts sending. */
    internal fun start() {
        if (!enabled) return
        runInBackground {
            loadInstallId()
            recoverCrashes()
            flushNow(force = false)
        }
        timer = try {
            executor.scheduleWithFixedDelay(
                { flushNow(force = false) },
                options.flushIntervalMs, options.flushIntervalMs, TimeUnit.MILLISECONDS,
            )
        } catch (error: Throwable) {
            debug("Could not schedule uploads: $error")
            null
        }
    }

    /** Stops the timer and sends what is queued. Blocks; for tests and shutdown. */
    internal fun close() {
        timer?.cancel(false)
        try {
            executor.submit { flushNow(force = true) }.get(30, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
        executor.shutdown()
    }

    /** Stops this client without blocking: queued work (a last flush included) finishes in the background. */
    internal fun shutdownAsync() {
        timer?.cancel(false)
        runInBackground { flushNow(force = true) }
        executor.shutdown()
    }

    // ---- context --------------------------------------------------------

    public fun setUser(user: UserInfo?) {
        this.user = user
    }

    public fun setApp(app: AppInfo) {
        this.app = this.app?.merge(app) ?: app
    }

    internal fun deviceJson(): JSONObject {
        val json = JSONObject()
        for ((key, value) in device) json.putIfNotNull(key, value)
        installId?.let { json.put("install_id", it) }
        return json
    }

    private fun putContext(json: JSONObject): JSONObject {
        json.put("session_id", sessionId)
        json.put("device", deviceJson())
        app?.let { json.put("app", it.toJson()) }
        user?.let { json.put("user", it.toJson()) }
        json.put("sdk", toJsonValue(sdk))
        return json
    }

    // ---- network --------------------------------------------------------

    /** False for API Studio's own uploads and for ignored URLs. */
    public fun shouldCapture(url: String): Boolean {
        if (!enabled || !options.captureNetwork || url.isEmpty()) return false
        if (url.startsWith(baseUrl)) return false
        if (options.ignoreUrls.any { url.contains(it) }) return false
        return options.ignoreUrlPatterns.none { it.containsMatchIn(url) }
    }

    internal fun recordRequest(input: RecordedRequest) {
        if (!shouldCapture(input.url)) return
        try {
            val statusCode = input.statusCode
            val failed = input.error != null || input.cancelled || statusCode == 0 || statusCode >= 400
            val log = JSONObject()
                .put("method", input.method.ifEmpty { "GET" }.uppercase())
                .put("url", redactor.redactUrl(input.url))
                .put("status_code", statusCode)
                .put("duration_ms", maxOf(0L, input.durationMs))
                .put("status", if (input.cancelled) "cancelled" else if (failed) "error" else "success")
                .putIfNotNull("query_params", redactor.redactQuery(parseQuery(input.url)))
                .put("request", payload(input.requestHeaders, input.requestBody))
                .put("response", payload(input.responseHeaders, input.responseBody))
                .putIfNotNull("error", input.error)
                .put("is_truncated", input.requestBody.truncated || input.responseBody.truncated)
                .put("timestamp", iso(input.startedAtMs))
            putContext(log)
            addHttpBreadcrumb(log)
            val processed = options.beforeSendLog?.let { it.process(log) ?: return } ?: log
            logQueue.push(processed)
        } catch (error: Throwable) {
            debug("Could not record a request: $error")
        }
    }

    private fun payload(headers: Map<String, String>?, body: CapturedBody): JSONObject {
        val json = JSONObject()
        redactor.redactHeaders(headers)?.let { json.put("headers", toJsonValue(it)) }
        json.put("body", redactor.redactBody(body.body) ?: JSONObject.NULL)
        json.put("size_bytes", body.sizeBytes ?: JSONObject.NULL)
        return json
    }

    private fun addHttpBreadcrumb(log: JSONObject) {
        val path = log.getString("url").substringBefore('?')
        val statusCode = log.getInt("status_code")
        val error = log.optString("error", "")
        val data = linkedMapOf<String, Any?>(
            "method" to log.getString("method"),
            "url" to path,
            "status_code" to statusCode,
            "duration_ms" to log.getLong("duration_ms"),
        )
        if (error.isNotEmpty()) data["error"] = error.take(200)
        addBreadcrumb(
            Breadcrumb(
                category = "http",
                message = "${log.getString("method")} $path ${if (statusCode == 0) "failed" else statusCode.toString()}",
                level = if (log.getString("status") == "success") "info" else "error",
                data = data,
                timestamp = log.getString("timestamp"),
            ),
        )
    }

    // ---- errors ---------------------------------------------------------

    public fun addBreadcrumb(breadcrumb: Breadcrumb) {
        if (!enabled) return
        try {
            val message = breadcrumb.message
            val crumb = if (message != null && message.length > MAX_BREADCRUMB_MESSAGE) {
                breadcrumb.copy(message = message.take(MAX_BREADCRUMB_MESSAGE) + "…")
            } else breadcrumb
            val json = crumb.toJson()
            synchronized(breadcrumbs) {
                breadcrumbs.addLast(json)
                while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            }
        } catch (error: Throwable) {
            debug("Could not add a breadcrumb: $error")
        }
    }

    internal fun breadcrumbsJson(): JSONArray = synchronized(breadcrumbs) { JSONArray(breadcrumbs.toList()) }

    @JvmOverloads
    public fun captureException(
        throwable: Throwable,
        tags: Map<String, String> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
        fingerprint: List<String>? = null,
    ) {
        if (!enabled) return
        try {
            buildErrorEvent(throwable, fatal = false, handled = true, tags, extra, fingerprint)?.let(errorQueue::push)
        } catch (error: Throwable) {
            debug("Could not capture an error: $error")
        }
    }

    @JvmOverloads
    public fun captureMessage(message: String, tags: Map<String, String> = emptyMap(), extra: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        try {
            buildEvent("Message", message, null, fatal = false, handled = true, tags, extra, null)?.let(errorQueue::push)
        } catch (error: Throwable) {
            debug("Could not capture a message: $error")
        }
    }

    internal fun buildErrorEvent(
        throwable: Throwable,
        fatal: Boolean,
        handled: Boolean,
        tags: Map<String, String> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
        fingerprint: List<String>? = null,
    ): JSONObject? {
        val titled = titleThrowable(throwable)
        return buildEvent(titled.javaClass.name, titled.message, stackTraceOf(throwable), fatal, handled, tags, extra, fingerprint)
    }

    private fun buildEvent(
        type: String,
        message: String?,
        stack: String?,
        fatal: Boolean,
        handled: Boolean,
        tags: Map<String, String>,
        extra: Map<String, Any?>,
        fingerprint: List<String>?,
    ): JSONObject? {
        val event = JSONObject()
            .put("exception_type", type.take(200))
            .putIfNotNull("message", message?.take(MAX_MESSAGE_CHARS))
            .putIfNotNull("stack_trace", stack?.take(MAX_STACK_CHARS))
            .put("fatal", fatal)
            .put("handled", handled)
            .put("timestamp", isoNow())
            .put("breadcrumbs", breadcrumbsJson())
        if (!fingerprint.isNullOrEmpty()) event.put("fingerprint", toJsonValue(fingerprint))
        if (tags.isNotEmpty()) event.put("tags", toJsonValue(tags))
        if (extra.isNotEmpty()) event.put("extra", toJsonValue(redactor.redactBody(extra)))
        putContext(event)
        val hook = options.beforeSendError ?: return event
        return hook.process(event)
    }

    /**
     * Called from the uncaught exception handler on the crashing thread. Writes the crash to disk
     * synchronously and never touches the network; it is sent on the next launch.
     */
    internal fun recordCrash(thread: Thread, throwable: Throwable) {
        if (!enabled) return
        val event = try {
            buildErrorEvent(throwable, fatal = true, handled = false, tags = mapOf("thread" to thread.name))
        } catch (_: Throwable) {
            null
        } ?: return
        val store = crashStore
        if (store == null) errorQueue.push(event) else store.write(event)
        logQueue.persistNow()
    }

    /** Moves crashes saved by an earlier launch into the error queue. */
    internal fun recoverCrashes() {
        val store = crashStore ?: return
        try {
            val pending = store.pending()
            if (pending.isEmpty()) return
            errorQueue.pushAll(pending.map { it.second })
            errorQueue.persistNow() // Saved in the queue before the crash files go.
            store.delete(pending.map { it.first })
            debug("Queued ${pending.size} crash(es) from the last launch.")
        } catch (error: Throwable) {
            debug("Could not recover crashes: $error")
        }
    }

    // ---- delivery -------------------------------------------------------

    /** Sends everything queued now, in the background. Errors go first. */
    public fun flush() {
        runInBackground { flushNow(force = true) }
    }

    /** Sends on the calling thread. */
    internal fun flushNow(force: Boolean) {
        try {
            errorUploader.flush(force)
            logUploader.flush(force)
        } catch (error: Throwable) {
            debug("Flush failed: $error")
        }
    }

    private fun runInBackground(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (error: Throwable) {
                    debug("Background task failed: $error")
                }
            }
        } catch (error: Throwable) {
            debug("Could not schedule work: $error")
        }
    }

    private fun loadInstallId() {
        val file = installIdFile
        installId = try {
            file?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { id ->
                    file?.let {
                        it.parentFile?.mkdirs()
                        it.writeText(id)
                    }
                }
        } catch (_: Throwable) {
            installId ?: UUID.randomUUID().toString()
        }
    }

    private fun onStorageError(error: Throwable) = debug("Storage error: $error")

    internal fun debug(message: String) {
        if (options.debug) logger(message)
    }

    internal companion object {
        fun newExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ApiStudio").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}

/**
 * The exception an issue is titled after. Android wraps exceptions thrown in lifecycle callbacks in a
 * plain RuntimeException ("Unable to start activity …") thrown from android.app; that wrapper would
 * give every such crash the same title, so the cause is used instead. The full trace is still sent.
 */
internal fun titleThrowable(throwable: Throwable): Throwable {
    var current = throwable
    repeat(5) {
        val cause = current.cause ?: return current
        val thrownByFramework = current.stackTrace.firstOrNull()?.className?.startsWith("android.app.") == true
        if (current.javaClass != RuntimeException::class.java || !thrownByFramework) return current
        current = cause
    }
    return current
}

/** Java's printed format (`at pkg.Class.method(File.kt:42)`, `Caused by: …`), which the backend parses. */
internal fun stackTraceOf(throwable: Throwable): String {
    val writer = StringWriter()
    PrintWriter(writer).use { throwable.printStackTrace(it) }
    return writer.toString()
}

internal fun shrinkLog(log: JSONObject): JSONObject? {
    val request = log.optJSONObject("request")
    val response = log.optJSONObject("response")
    val hasBody = { payload: JSONObject? -> payload != null && !payload.isNull("body") }
    if (!hasBody(request) && !hasBody(response)) return null
    val smaller = log.copy()
    smaller.optJSONObject("request")?.put("body", JSONObject.NULL)
    smaller.optJSONObject("response")?.put("body", JSONObject.NULL)
    smaller.put("is_truncated", true)
    return smaller
}

internal fun shrinkError(event: JSONObject): JSONObject? {
    val crumbs = event.optJSONArray("breadcrumbs")
    if (!event.has("extra") && (crumbs == null || isShrunk(crumbs))) return null
    val smaller = event.copy()
    smaller.remove("extra")
    if (crumbs != null) {
        val kept = JSONArray()
        for (i in maxOf(0, crumbs.length() - 10) until crumbs.length()) {
            val crumb = crumbs.optJSONObject(i)?.copy() ?: continue
            crumb.remove("data")
            kept.put(crumb)
        }
        smaller.put("breadcrumbs", kept)
    }
    return smaller
}

/** True once breadcrumbs are already cut to the last 10 without data, so shrinking ends. */
private fun isShrunk(crumbs: JSONArray): Boolean {
    if (crumbs.length() > 10) return false
    for (i in 0 until crumbs.length()) if (crumbs.optJSONObject(i)?.has("data") == true) return false
    return true
}

internal fun iso(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMs))

internal fun isoNow(): String = iso(System.currentTimeMillis())
