package cloud.apistudio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * API Studio for Android: API logs, crashes and errors.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ApiStudio.init(this, "YOUR_API_KEY")
 *     }
 * }
 * ```
 *
 * Every method is safe to call before [init] (it does nothing) and never throws.
 */
public object ApiStudio {
    internal const val SDK_NAME = "api_studio.android"
    internal const val PLATFORM = "android"
    private const val TAG = "ApiStudio"

    @Volatile
    internal var client: ApiStudioClient? = null
        private set

    /** True once [init] has run with `enabled = true`. */
    @JvmStatic
    public val isInitialized: Boolean get() = client?.enabled == true

    /**
     * Starts the SDK. Call once, from `Application.onCreate`, as early as possible so the crash
     * handler is in place before anything else runs. A second call replaces the first configuration.
     */
    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, apiKey: String, options: ApiStudioOptions = ApiStudioOptions()) {
        try {
            require(apiKey.isNotBlank()) { "API Studio: apiKey is required." }
            val appContext = context.applicationContext ?: context
            val app = packageAppInfo(appContext).let { fromPackage -> options.app?.let(fromPackage::merge) ?: fromPackage }
            options.app = app
            val debug = options.debug
            val next = ApiStudioClient(
                apiKey = apiKey,
                options = options,
                storageDir = File(appContext.filesDir, "apistudio"),
                sdk = mapOf("name" to SDK_NAME, "version" to BuildConfig.SDK_VERSION, "platform" to PLATFORM),
                deviceProvider = ::androidDevice,
                logger = { message -> if (debug) Log.d(TAG, message) },
            )
            client?.let { old -> runCatching { old.shutdownAsync() } }
            client = next
            if (!options.enabled) return
            if (options.captureErrors) ApiStudioCrashHandler.install { client }
            next.start()
            next.debug("Started; session ${next.sessionId}.")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "API Studio could not start: $error")
        }
    }

    /** Attaches a user or tester to everything sent from now on. Pass null on sign-out. */
    @JvmStatic
    public fun setUser(user: UserInfo?) {
        client?.setUser(user)
    }

    /** Overrides fields of the app release (filled from the package by default). */
    @JvmStatic
    public fun setApp(app: AppInfo) {
        client?.setApp(app)
    }

    /** Reports a caught exception (`handled: true`, `fatal: false`). */
    @JvmStatic
    @JvmOverloads
    public fun captureException(
        throwable: Throwable,
        tags: Map<String, String> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
        fingerprint: List<String>? = null,
    ) {
        client?.captureException(throwable, tags, extra, fingerprint)
    }

    /** Reports a message as an issue of type `Message`. */
    @JvmStatic
    @JvmOverloads
    public fun captureMessage(message: String, tags: Map<String, String> = emptyMap(), extra: Map<String, Any?> = emptyMap()) {
        client?.captureMessage(message, tags, extra)
    }

    @JvmStatic
    public fun addBreadcrumb(breadcrumb: Breadcrumb) {
        client?.addBreadcrumb(breadcrumb)
    }

    /** Shortcut for `addBreadcrumb(Breadcrumb(category, message))`. */
    @JvmStatic
    @JvmOverloads
    public fun addBreadcrumb(category: String, message: String, data: Map<String, Any?>? = null) {
        client?.addBreadcrumb(Breadcrumb(category, message, data = data))
    }

    /** Sends everything queued now, in the background. */
    @JvmStatic
    public fun flush() {
        client?.flush()
    }

    private fun packageAppInfo(context: Context): AppInfo = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        AppInfo(
            name = context.applicationInfo.loadLabel(context.packageManager).toString(),
            version = info.versionName,
            build = build.toString(),
        )
    } catch (_: PackageManager.NameNotFoundException) {
        AppInfo()
    } catch (_: Throwable) {
        AppInfo()
    }
}

internal fun androidDevice(): Map<String, Any?> = linkedMapOf(
    "os" to "android",
    "os_version" to Build.VERSION.RELEASE,
    "api_level" to Build.VERSION.SDK_INT,
    "model" to Build.MODEL,
    "manufacturer" to Build.MANUFACTURER,
    "brand" to Build.BRAND,
    "abi" to Build.SUPPORTED_ABIS?.firstOrNull(),
    "is_emulator" to isEmulator(),
)

private fun isEmulator(): Boolean = try {
    Build.FINGERPRINT?.startsWith("generic") == true ||
        Build.FINGERPRINT?.contains("emulator") == true ||
        Build.MODEL?.contains("sdk_gphone") == true ||
        Build.PRODUCT?.contains("sdk") == true
} catch (_: Throwable) {
    false
}
