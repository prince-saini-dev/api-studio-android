package cloud.apistudio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UploaderTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    @Test fun `classify follows the protocol retry table`() {
        for (s in listOf(200, 201, 202, 204)) assertEquals("$s", Outcome.ACCEPTED, classify(s))
        for (s in listOf(400, 404, 410, 422)) assertEquals("$s", Outcome.DROPPED, classify(s))
        assertEquals(Outcome.TOO_LARGE, classify(413))
        for (s in listOf(401, 403, 408, 429, 500, 502, 503, 504)) assertEquals("$s", Outcome.RETRY_LATER, classify(s))
    }

    @Test fun `backoff starts at 1 s, doubles, and stops at 5 min`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map(::backoffMs))
        assertEquals(256_000L, backoffMs(9))
        assertEquals(300_000L, backoffMs(10))
        assertEquals(300_000L, backoffMs(1_000))
    }

    private fun queueOf(n: Int) = PersistentQueue(null, 500, null).apply {
        repeat(n) { push(JSONObject().put("i", it).put("request", JSONObject().put("body", "x"))) }
    }

    private fun uploader(queue: PersistentQueue, transport: Transport, batch: Int = 50, now: () -> Long = { 0L }) =
        Uploader("https://ingest.test/api/v1/logs/batch", "k", transport, queue, "logs", batch, ::shrinkLog, {}, now)

    @Test fun `accepted and permanently rejected batches leave the queue`() {
        val queue = queueOf(120)
        val transport = FakeTransport(202, 422, 400)
        uploader(queue, transport).flush()
        assertEquals(0, queue.size)
        assertEquals(listOf(50, 50, 20), transport.posts.map { it.second.getJSONArray("logs").length() })
    }

    @Test fun `temporary failures keep the queue and back off`() {
        for (status in listOf(401, 403, 408, 429, 500, 503)) {
            val queue = queueOf(3)
            var now = 10_000L
            val up = uploader(queue, FakeTransport(status), now = { now })
            up.flush()
            assertEquals("$status", 3, queue.size)
            assertEquals(11_000L, up.retryAt)
            up.flush() // Still waiting: no request.
            now = 11_000L
            up.flush()
            assertEquals(13_000L, up.retryAt) // Second failure doubles.
        }
    }

    @Test fun `a network error is temporary`() {
        val queue = queueOf(2)
        uploader(queue, Transport { _, _, _ -> throw IOException("offline") }).flush()
        assertEquals(2, queue.size)
    }

    @Test fun `413 splits the batch, then strips bodies, then drops`() {
        val queue = queueOf(4)
        val transport = FakeTransport(413, 413, 413, 413, 202)
        uploader(queue, transport).flush()
        val sizes = transport.posts.map { it.second.getJSONArray("logs").length() }
        // 4 -> 2 -> 1 -> the same item without bodies -> dropped; then the other 3 are accepted.
        assertEquals(listOf(4, 2, 1, 1, 1, 2), sizes)
        val stripped = transport.posts[3].second.getJSONArray("logs").getJSONObject(0)
        assertTrue(stripped.getJSONObject("request").isNull("body"))
        assertTrue(stripped.getBoolean("is_truncated"))
        assertEquals(0, queue.size)
    }

    @Test fun `shrinking an error ends`() {
        val crumbs = JSONArray().apply { repeat(30) { put(JSONObject().put("category", "log").put("data", JSONObject().put("a", 1))) } }
        val event = JSONObject().put("exception_type", "X").put("breadcrumbs", crumbs).put("extra", JSONObject().put("k", 1))
        val once = shrinkError(event)!!
        assertFalse(once.has("extra"))
        assertEquals(10, once.getJSONArray("breadcrumbs").length())
        assertFalse(once.getJSONArray("breadcrumbs").getJSONObject(0).has("data"))
        assertEquals(null, shrinkError(once))
    }

    @Test fun `client batches logs by 50 and errors by 20, errors first, over HTTP`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(202)) }
        server.start()
        val client = testClient(baseUrl = server.baseUrl(), transport = HttpUrlConnectionTransport())
        repeat(60) { client.recordRequest(RecordedRequest("get", "https://shop.test/items/$it", 0, 5, statusCode = 200)) }
        repeat(25) { client.captureException(IllegalStateException("e$it")) }
        client.flushNow(force = true)

        val requests = (1..4).map { server.takeRequest() }
        assertEquals(listOf("/api/v1/errors/batch", "/api/v1/errors/batch", "/api/v1/logs/batch", "/api/v1/logs/batch"), requests.map { it.path })
        assertEquals(listOf(20, 5), requests.take(2).map { it.json().getJSONArray("events").length() })
        assertEquals(listOf(50, 10), requests.drop(2).map { it.json().getJSONArray("logs").length() })
        val first = requests[0]
        assertEquals("Bearer test-key", first.getHeader("Authorization"))
        assertEquals(1, first.headers.values("Content-Type").size)
        assertEquals("gzip", first.getHeader("Content-Encoding"))
        assertEquals(0, client.logQueue.size)
        assertEquals(0, client.errorQueue.size)
    }
}
