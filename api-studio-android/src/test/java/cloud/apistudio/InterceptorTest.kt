package cloud.apistudio

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class InterceptorTest {
    private val server = MockWebServer()
    private lateinit var studio: ApiStudioClient
    private lateinit var http: OkHttpClient

    @Before fun setUp() {
        server.start()
        studio = testClient()
        http = OkHttpClient.Builder()
            .addInterceptor(ApiStudioInterceptor { studio })
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After fun tearDown() = server.shutdown()

    private fun logs(): List<JSONObject> = studio.logQueue.peek(500)

    @Test fun `logs method, url, headers, bodies, status and duration with redaction`() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "session=s")
                .setBody("""{"id":7,"token":"server-secret"}""")
                .setHeadersDelay(30, TimeUnit.MILLISECONDS),
        )
        val request = Request.Builder()
            .url(server.url("/api/login?page=2&access_token=abc"))
            .header("Authorization", "Bearer user-secret")
            .header("X-Trace", "t1")
            .post("""{"email":"a@b.c","password":"hunter2"}""".toRequestBody("application/json".toMediaType()))
            .build()

        val body = http.newCall(request).execute().use { it.body!!.string() }
        // The app still gets the whole, unredacted body: peekBody did not consume it.
        assertEquals("""{"id":7,"token":"server-secret"}""", body)
        assertEquals("""{"email":"a@b.c","password":"hunter2"}""", server.takeRequest().body.readUtf8())

        val log = logs().single()
        assertEquals("POST", log.getString("method"))
        assertTrue(log.getString("url").endsWith("/api/login?page=2&access_token=***REDACTED***"))
        assertEquals(201, log.getInt("status_code"))
        assertEquals("success", log.getString("status"))
        assertTrue(log.getLong("duration_ms") >= 25)
        assertEquals("2", log.getJSONObject("query_params").getString("page"))
        assertEquals(Redactor.REDACTED, log.getJSONObject("query_params").getString("access_token"))

        val req = log.getJSONObject("request")
        assertEquals(Redactor.REDACTED, req.getJSONObject("headers").getString("Authorization"))
        assertEquals("t1", req.getJSONObject("headers").getString("X-Trace"))
        assertEquals("application/json; charset=utf-8", req.getJSONObject("headers").getString("Content-Type"))
        assertEquals("38", req.getJSONObject("headers").getString("Content-Length"))
        assertEquals("a@b.c", req.getJSONObject("body").getString("email"))
        assertEquals(Redactor.REDACTED, req.getJSONObject("body").getString("password"))
        assertEquals(38, req.getLong("size_bytes"))

        val res = log.getJSONObject("response")
        assertEquals(Redactor.REDACTED, res.getJSONObject("headers").getString("Set-Cookie"))
        assertEquals(7, res.getJSONObject("body").getInt("id"))
        assertEquals(Redactor.REDACTED, res.getJSONObject("body").getString("token"))
        assertFalse(log.getBoolean("is_truncated"))

        assertEquals(studio.sessionId, log.getString("session_id"))
        assertEquals("api_studio.android", log.getJSONObject("sdk").getString("name"))
        assertEquals("android", log.getJSONObject("sdk").getString("platform"))
        assertEquals("android", log.getJSONObject("device").getString("os"))
        assertTrue(log.getString("timestamp").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))

        val crumb = studio.breadcrumbsJson().getJSONObject(0)
        assertEquals("http", crumb.getString("category"))
        assertTrue(crumb.getString("message").startsWith("POST "))
        assertFalse(crumb.getString("message").contains("access_token"))
    }

    @Test fun `bodies over 64 KB are cut and flagged, and the app still reads all of it`() {
        val big = "a".repeat(200_000)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(big))
        val read = http.newCall(Request.Builder().url(server.url("/big")).post(big.toRequestBody("text/plain".toMediaType())).build())
            .execute().use { it.body!!.string() }
        assertEquals(200_000, read.length)

        val log = logs().single()
        assertTrue(log.getBoolean("is_truncated"))
        val responseBody = log.getJSONObject("response").getString("body")
        assertEquals(64 * 1024 + 1, responseBody.length) // 64 KB plus the "…" marker
        assertEquals(200_000, log.getJSONObject("response").getLong("size_bytes"))
        assertEquals(64 * 1024 + 1, log.getJSONObject("request").getString("body").length)
        assertEquals(200_000, log.getJSONObject("request").getLong("size_bytes"))
    }

    @Test fun `binary bodies are not captured but their size is`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(1234))))
        val bytes = http.newCall(Request.Builder().url(server.url("/logo.png")).build()).execute().use { it.body!!.bytes() }
        assertEquals(1234, bytes.size)
        val response = logs().single().getJSONObject("response")
        assertTrue(response.isNull("body"))
        assertEquals(1234, response.getLong("size_bytes"))
    }

    @Test fun `http errors are logged as error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        http.newCall(Request.Builder().url(server.url("/fail")).build()).execute().close()
        val log = logs().single()
        assertEquals(500, log.getInt("status_code"))
        assertEquals("error", log.getString("status"))
        assertEquals("oops", log.getJSONObject("response").getString("body"))
    }

    @Test fun `a network failure is logged with status 0 and the exception still reaches the app`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            http.newCall(Request.Builder().url(server.url("/down")).build()).execute()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        val log = logs().single()
        assertEquals(0, log.getInt("status_code"))
        assertEquals("error", log.getString("status"))
        assertNotNull(log.optString("error").takeIf { it.isNotEmpty() })
        assertTrue(studio.breadcrumbsJson().getJSONObject(0).getString("message").endsWith(" failed"))
    }

    @Test fun `a cancelled call is logged as cancelled`() {
        server.enqueue(MockResponse().setBody("slow").setHeadersDelay(1, TimeUnit.SECONDS))
        val call = http.newCall(Request.Builder().url(server.url("/slow")).build())
        Thread { Thread.sleep(150); call.cancel() }.start()
        try {
            call.execute()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        val log = logs().single()
        assertEquals("cancelled", log.getString("status"))
        assertEquals(0, log.getInt("status_code"))
    }

    @Test fun `own uploads and ignored URLs are never logged`() {
        studio = testClient(baseUrl = server.url("/").toString().trimEnd('/')) { ignoreUrls = listOf("/health") }
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse())
        http.newCall(Request.Builder().url(server.url("/api/v1/logs/batch")).build()).execute().close()
        http.newCall(Request.Builder().url(server.url("/health")).build()).execute().close()
        assertEquals(0, studio.logQueue.size)
    }

    @Test fun `does nothing before init and when network capture is off`() {
        val bare = OkHttpClient.Builder().addInterceptor(ApiStudioInterceptor { null }).build()
        server.enqueue(MockResponse().setBody("x"))
        assertEquals("x", bare.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() })

        studio = testClient { captureNetwork = false }
        server.enqueue(MockResponse().setBody("y"))
        http.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals(0, studio.logQueue.size)
    }

    @Test fun `beforeSendLog can change or drop a log`() {
        studio = testClient {
            beforeSendLog = BeforeSend { log -> if (log.getString("url").contains("drop")) null else log.put("url", "changed") }
        }
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        http.newCall(Request.Builder().url(server.url("/drop")).build()).execute().close()
        http.newCall(Request.Builder().url(server.url("/keep")).build()).execute().close()
        assertEquals("changed", logs().single().getString("url"))
    }
}
