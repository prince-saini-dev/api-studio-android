package cloud.apistudio

import org.json.JSONObject

/** The app release. The portal shows it as `version+build`. Unset fields are filled from the package. */
public data class AppInfo @JvmOverloads constructor(
    val name: String? = null,
    val version: String? = null,
    val build: String? = null,
) {
    internal fun merge(other: AppInfo): AppInfo =
        AppInfo(other.name ?: name, other.version ?: version, other.build ?: build)

    internal fun toJson(): JSONObject =
        JSONObject().putIfNotNull("name", name).putIfNotNull("version", version).putIfNotNull("build", build)
}

/** The signed-in user or tester. [extra] holds any other fields. */
public data class UserInfo @JvmOverloads constructor(
    val id: String? = null,
    val email: String? = null,
    val name: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    internal fun toJson(): JSONObject {
        val json = JSONObject()
        for ((key, value) in extra) json.putIfNotNull(key, value)
        return json.putIfNotNull("id", id).putIfNotNull("email", email).putIfNotNull("name", name)
    }
}

/** Changes or drops (return null) a log or error event before it is queued. */
public fun interface BeforeSend {
    public fun process(item: JSONObject): JSONObject?
}

/**
 * SDK settings. Kotlin: `ApiStudioOptions().apply { debug = true }`.
 * Java: `ApiStudioOptions o = new ApiStudioOptions(); o.setDebug(true);`.
 */
public class ApiStudioOptions {
    /** Defaults to the hosted API Studio backend. */
    public var baseUrl: String = DEFAULT_BASE_URL

    /** Turn the SDK off (for example in production builds) without removing calls. */
    public var enabled: Boolean = true

    /** Lets [ApiStudioInterceptor] record requests. */
    public var captureNetwork: Boolean = true

    /** Installs the uncaught exception handler. */
    public var captureErrors: Boolean = true

    public var app: AppInfo? = null
    public var user: UserInfo? = null

    /** Extra header names to redact (added to the defaults). */
    public var redactHeaders: List<String> = emptyList()

    /** Extra JSON body keys and query parameters to redact (added to the defaults). */
    public var redactKeys: List<String> = emptyList()

    /** Set false to redact only your own lists. */
    public var includeDefaultRedaction: Boolean = true

    /** Requests whose URL contains any of these strings are never logged. */
    public var ignoreUrls: List<String> = emptyList()

    /** Requests whose URL matches any of these are never logged. */
    public var ignoreUrlPatterns: List<Regex> = emptyList()

    /** Bytes kept per request and response body. Default 64 KB. */
    public var maxBodyBytes: Int = 64 * 1024

    /** How often queued items are sent. */
    public var flushIntervalMs: Long = 5_000

    /** Items kept per queue while offline; the oldest are dropped. */
    public var maxQueueSize: Int = 500

    public var beforeSendLog: BeforeSend? = null
    public var beforeSendError: BeforeSend? = null

    /** Print SDK diagnostics to Logcat (tag `ApiStudio`). */
    public var debug: Boolean = false

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api-studio-backend-345113340482.asia-south1.run.app"
    }
}
