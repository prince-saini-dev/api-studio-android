package cloud.apistudio

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Upload batching on real time with short intervals and wide margins: what matters is the number
 * of upload requests a burst of app calls costs, not exact timings.
 */
class BatchingTest {
    /** Records log/error batch sizes; each upload takes [latencyMs] like a real round trip. */
    private class SlowTransport(private val latencyMs: Long = 50) : Transport {
        val logBatches: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val errorBatches: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override fun post(url: String, apiKey: String, json: String): Int {
            val body = JSONObject(json)
            if (url.endsWith("/logs/batch")) logBatches += body.getJSONArray("logs").length()
            else errorBatches += body.getJSONArray("events").length()
            Thread.sleep(latencyMs)
            return 202
        }
    }

    private val transport = SlowTransport()
    private var client: ApiStudioClient? = null

    @After
    fun tearDown() {
        client?.close()
    }

    private fun start(intervalMs: Long): ApiStudioClient =
        testClient(transport = transport) { flushIntervalMs = intervalMs }.also {
            client = it
            it.start()
            Thread.sleep(100) // the empty start-up flush
        }

    private fun ApiStudioClient.call(i: Int, status: Int = 200) =
        recordRequest(RecordedRequest("GET", "https://api.shop.test/v1/master/table$i", System.currentTimeMillis(), 120, statusCode = status))

    @Test
    fun `a 50-call sync one after another costs 1 or 2 uploads instead of one per call`() {
        val client = start(intervalMs = 1_500)
        repeat(50) {
            client.call(it)
            Thread.sleep(20)
        }
        Thread.sleep(3_500)
        assertTrue("batches ${transport.logBatches}", transport.logBatches.size <= 2)
        assertEquals(50, transport.logBatches.sum())
    }

    @Test
    fun `50 calls at once go as one full batch without waiting for the window`() {
        val client = start(intervalMs = 60_000)
        repeat(50) { client.call(it) }
        Thread.sleep(600)
        assertEquals(listOf(50), transport.logBatches.toList())
    }

    @Test
    fun `a failed call is sent within 2 s taking the waiting logs along`() {
        val client = start(intervalMs = 60_000)
        client.call(1)
        client.call(2, status = 500)
        Thread.sleep(1_200)
        assertTrue(transport.logBatches.isEmpty())
        Thread.sleep(1_500)
        assertEquals(listOf(2), transport.logBatches.toList())
    }

    @Test
    fun `an error is sent at once with the API calls that led to it`() {
        val client = start(intervalMs = 60_000)
        client.call(1)
        client.call(2)
        client.captureException(IllegalStateException("total of null"))
        Thread.sleep(600)
        assertEquals(listOf(1), transport.errorBatches.toList())
        assertEquals(listOf(2), transport.logBatches.toList())
    }

    @Test
    fun `going to the background sends everything queued`() {
        val client = start(intervalMs = 60_000)
        repeat(3) { client.call(it) }
        Thread.sleep(200)
        assertTrue(transport.logBatches.isEmpty())
        client.onBackground()
        Thread.sleep(500)
        assertEquals(listOf(3), transport.logBatches.toList())
    }

    @Test
    fun `nothing is scheduled before start or after close`() {
        val client = testClient(transport = transport) { flushIntervalMs = 100 }
        client.call(1, status = 500)
        client.onBackground()
        Thread.sleep(400)
        assertTrue(transport.logBatches.isEmpty())
        client.close()
    }
}
