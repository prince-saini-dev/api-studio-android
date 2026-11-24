package cloud.apistudio

import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

internal fun tempDir(): File = Files.createTempDirectory("apistudio-test").toFile().apply { deleteOnExit() }

internal val TEST_SDK = mapOf("name" to ApiStudio.SDK_NAME, "version" to "test", "platform" to ApiStudio.PLATFORM)

internal fun testClient(
    baseUrl: String = "https://ingest.test",
    storageDir: File? = null,
    transport: Transport = Transport { _, _, _ -> 202 },
    configure: ApiStudioOptions.() -> Unit = {},
): ApiStudioClient = ApiStudioClient(
    apiKey = "test-key",
    options = ApiStudioOptions().apply {
        this.baseUrl = baseUrl
        flushIntervalMs = 60_000
        configure()
    },
    storageDir = storageDir,
    sdk = TEST_SDK,
    deviceProvider = { mapOf("os" to "android", "os_version" to "14", "model" to "Pixel 8") },
    transport = transport,
)

/** Records every body posted, answering with the next status in [statuses] (then the last one again). */
internal class FakeTransport(vararg statuses: Int) : Transport {
    private val queue = ArrayDeque(statuses.toList())
    private var last = 202
    val posts = mutableListOf<Pair<String, JSONObject>>()

    override fun post(url: String, apiKey: String, json: String): Int {
        posts += url to JSONObject(json)
        last = queue.removeFirstOrNull() ?: last
        return last
    }
}

/** The JSON body of a request the SDK sent to MockWebServer (gunzipped). */
internal fun okhttp3.mockwebserver.RecordedRequest.json(): JSONObject {
    val bytes = body.readByteArray()
    val text = if (getHeader("Content-Encoding") == "gzip") GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
    return JSONObject(text)
}

internal fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')
