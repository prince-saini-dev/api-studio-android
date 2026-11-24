# API Studio for Android

Native Android SDK for [API Studio](https://apistudio.cloud). It sends API logs (every OkHttp and
Retrofit request), crashes and handled errors to the API Studio portal. It speaks the
[API Studio SDK protocol v1](../api-studio-backend/docs/sdk-protocol.md) and mirrors the JS SDK
(`api-studio-js`): it uses the same redaction defaults, retry rules, batch sizes and breadcrumb limits.

- Artifact: `cloud.apistudio:api-studio-android` (not published yet; see [Building](#building))
- Package: `cloud.apistudio`
- minSdk 21, OkHttp 4.x (optional; needed only for network capture)

## Setup

### 1. Add the dependency

Until the artifact is on a Maven repository, publish it to your local Maven repository (see
[Building](#building)) and add `mavenLocal()`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

// app/build.gradle.kts
dependencies {
    implementation("cloud.apistudio:api-studio-android:0.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // you most likely have this already
}
```

The SDK adds the `INTERNET` permission through its manifest.

### 2. Start it in your `Application`

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiStudio.init(this, "YOUR_API_KEY", ApiStudioOptions().apply {
            debug = BuildConfig.DEBUG          // SDK diagnostics in Logcat, tag "ApiStudio"
            redactKeys = listOf("otp")         // added to the defaults
            ignoreUrls = listOf("/health")
        })
    }
}
```

Call `init` as early as you can, so the crash handler is installed before anything else runs.
The API key comes from the portal and selects the project and environment.

Java:

```java
ApiStudioOptions options = new ApiStudioOptions();
options.setDebug(BuildConfig.DEBUG);
ApiStudio.init(this, "YOUR_API_KEY", options);
```

### 3. Add the interceptor to your OkHttp client

```kotlin
val okHttp = OkHttpClient.Builder()
    .addInterceptor(ApiStudioInterceptor())
    .build()

val retrofit = Retrofit.Builder().client(okHttp).baseUrl("https://shop.app/").build()
```

Use `addInterceptor`, not `addNetworkInterceptor`. That way the interceptor sees decoded bodies,
records one log per call (not one per redirect or retry), and also records calls that fail without a
response. Each log has the method, URL, query, request and response headers and bodies, the status,
the duration, and whether the call was cancelled. The interceptor does nothing until `ApiStudio.init` runs.

### 4. Users, releases, errors, breadcrumbs

```kotlin
ApiStudio.setUser(UserInfo(id = "tester-7", email = "qa@example.com", name = "QA 7"))
ApiStudio.setUser(null) // on sign-out

ApiStudio.setApp(AppInfo(version = "2.4.0", build = "118")) // defaults come from the package

try { checkout() } catch (e: Exception) {
    ApiStudio.captureException(e, tags = mapOf("flavor" to "staging"), extra = mapOf("cart_id" to "c1"))
}
ApiStudio.captureMessage("Payment sheet dismissed")

ApiStudio.addBreadcrumb("navigation", "Cart → Checkout")
ApiStudio.addBreadcrumb(Breadcrumb(category = "user", message = "Tapped Pay", level = "info"))

ApiStudio.flush() // sends now, in the background (otherwise every 5 s)
```

## What it does

| | |
|---|---|
| API logs | `POST /api/v1/logs/batch`, 50 per batch |
| Errors and crashes | `POST /api/v1/errors/batch`, 20 per batch, sent before logs |
| Bodies | Textual bodies only (JSON, text, XML, form, GraphQL). JSON is sent parsed. Bodies are cut at 64 KB with `is_truncated: true`. Binary bodies are sent as `null`, with `size_bytes`. Response bodies are read with `peekBody`, so your app's stream is never consumed. One-shot and duplex request bodies are never read. |
| Failures | IOException → `status_code: 0`, `status: "error"`, `error: "SocketTimeoutException: …"`. Cancelled calls → `status: "cancelled"`. The exception is rethrown to your code unchanged. |
| Redaction (on device) | Headers `authorization`, `cookie`, `set-cookie`, `x-api-key`, `proxy-authorization`. JSON keys and query parameters `password`, `token`, `access_token`, `refresh_token`, `secret`, `api_key`, `authorization`, `card_number`, `cvv`. Matching ignores case, `_` and `-` (`accessToken` matches). Use `redactHeaders` and `redactKeys` to add names, or `includeDefaultRedaction = false` to use only yours. |
| Offline queue | `filesDir/apistudio/{logs,errors}.json`, up to 500 items each (the oldest are dropped). Survives restarts. |
| Retry rules | 2xx → sent. 400/404/410/422 → dropped. 413 → batch halved, then a single item is retried without bodies, then dropped. 401/403/408/429/5xx/network → kept, with backoff of 1 s doubling up to 5 min. |
| Crashes | The uncaught exception handler writes the crash synchronously to `filesDir/apistudio/crashes/`, then calls the previous handler (so Android's crash dialog and other crash reporters still run). The crash is sent on the next launch with `fatal: true`, `handled: false`. It never waits on the network. |
| Stack traces | Java's printed format (`at com.shop.CartViewModel.checkout(CartViewModel.kt:42)`, `Caused by: …`), which the backend parses and groups by. |
| Context | `session_id` (per launch), `device` (`os`, `os_version`, `model`, `manufacturer`, `brand`, `api_level`, `abi`, `is_emulator`, `install_id`), `app` (label, versionName, versionCode), `user`, and `sdk` = `{name: "api_studio.android", platform: "android"}`. |
| Own uploads | Sent with `HttpURLConnection` (gzipped, with each header set once), so they never pass through your OkHttp client. URLs under `baseUrl` are never logged. |
| Breadcrumbs | The last 100, oldest first. Messages are cut at 300 characters. Every HTTP call adds one. |

The SDK never throws into your app and never blocks the main thread on the network. Uploads run on
a single low-priority daemon thread.

### Options

| Option | Default | |
|---|---|---|
| `baseUrl` | hosted backend | |
| `enabled` | `true` | Turns everything off without removing calls |
| `captureNetwork` | `true` | Lets the interceptor record calls |
| `captureErrors` | `true` | Installs the crash handler |
| `app`, `user` | from the package / none | |
| `redactHeaders`, `redactKeys`, `includeDefaultRedaction` | `[]`, `[]`, `true` | |
| `ignoreUrls`, `ignoreUrlPatterns` | `[]` | Substrings or `Regex` values |
| `maxBodyBytes` | `65536` | |
| `flushIntervalMs` | `5000` | |
| `maxQueueSize` | `500` | Per queue |
| `beforeSendLog`, `beforeSendError` | none | Change or drop (return `null`) an item, as a `JSONObject` |
| `debug` | `false` | Logcat diagnostics |

## Not covered yet

- **NDK (native) crashes.** Only JVM/Kotlin exceptions are caught. Native signals (SIGSEGV and
  similar) in C/C++ code, or in libraries such as some image and video codecs, are not caught.
- **ANRs.** No watchdog. On API 30+ these could be read on next launch from
  `ActivityManager.getHistoricalProcessExitReasons`.
- **R8 / ProGuard mapping upload.** Obfuscated release builds send obfuscated traces (`a.b.c(SourceFile:1)`).
  These still group, but they are not readable until mapping upload and backend deobfuscation exist.
  Until then, keep line numbers with `-keepattributes SourceFile,LineNumberTable` and consider
  `-renamesourcefileattribute SourceFile`.
- **Other HTTP stacks.** `HttpURLConnection`, Ktor (non-OkHttp engines), Volley and Cronet are not
  instrumented. Ktor with the OkHttp engine works if you add the interceptor to its `OkHttpClient`.
- **Performance snapshots** (`POST /api/v1/performance`) are not sent.
- **Automatic breadcrumbs** for navigation and lifecycle are not recorded; add them with `addBreadcrumb`.
- **Coroutine exceptions** that a `CoroutineExceptionHandler` handles never reach the uncaught
  handler. Report them with `captureException`.

## Sample app

`sample/` is a small app for checking the SDK on a device or emulator. It is not part of the artifact.
By default it uploads to `http://10.0.2.2:8787`, the host machine as seen from an emulator, and calls
a test API on `:8788`. Override these with `-PingestUrl=… -PapiUrl=… -PapiKey=…`.

```bash
./gradlew :sample:installDebug
```

```bash
adb shell am start -n cloud.apistudio.sample/.MainActivity --es action requests
```

```bash
adb shell am start -n cloud.apistudio.sample/.MainActivity --es action crash
```

The first command makes a POST with secrets, a 404 and a refused connection, then reports a handled
error. The second crashes in `onCreate`; the crash is sent the next time the app starts.
These were checked on an Android 16 (API 36) emulator against a local receiver. They have not been
run against the real backend.

Crashes that Android wraps in `RuntimeException: Unable to start activity …` are titled after the
cause (`java.lang.IllegalStateException: …`); the full trace with the wrapper is still sent.

## Building

```bash
./gradlew test                  # unit tests (JUnit + OkHttp MockWebServer, no device needed)
./gradlew assembleRelease       # api-studio-android/build/outputs/aar/
./gradlew publishToMavenLocal   # cloud.apistudio:api-studio-android:0.1.0 in ~/.m2, for local use
```

You need JDK 17 or newer and an Android SDK with platform 35 (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

The engine (`ApiStudioClient`, queues, uploader, redaction, crash store) has no Android
dependencies, so it is unit tested on the JVM. `ApiStudio` and `androidDevice()` are the only
Android-specific code.
