package cloud.apistudio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashTest {
    private val server = MockWebServer()
    private val dir = tempDir()

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun nestedCrash(): Throwable = try {
        try {
            throw IllegalArgumentException("bad cart id")
        } catch (cause: Exception) {
            throw IllegalStateException("Checkout failed", cause)
        }
    } catch (e: Exception) {
        e
    }

    @Test fun `uncaught crash is written to disk synchronously and chained to the previous handler`() {
        val client = testClient(storageDir = dir)
        client.addBreadcrumb(Breadcrumb("navigation", "Cart -> Checkout"))
        var chained: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> chained = e }
        val handler = ApiStudioCrashHandler({ client }, previous)
        val crash = nestedCrash()

        handler.uncaughtException(Thread.currentThread(), crash)

        assertSame(crash, chained)
        // On disk the moment the handler returns: nothing waited on a background thread or the network.
        val files = File(dir, "crashes").listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(1, files.size)
        val event = org.json.JSONObject(files.single().readText())
        assertEquals("java.lang.IllegalStateException", event.getString("exception_type"))
        assertEquals("Checkout failed", event.getString("message"))
        assertTrue(event.getBoolean("fatal"))
        assertFalse(event.getBoolean("handled"))
        val trace = event.getString("stack_trace")
        assertTrue(trace, trace.startsWith("java.lang.IllegalStateException: Checkout failed"))
        assertTrue(trace, trace.contains("\tat cloud.apistudio.CrashTest.nestedCrash(CrashTest.kt:"))
        assertTrue(trace, trace.contains("Caused by: java.lang.IllegalArgumentException: bad cart id"))
        assertEquals("Cart -> Checkout", event.getJSONArray("breadcrumbs").getJSONObject(0).getString("message"))
        assertEquals("android", event.getJSONObject("sdk").getString("platform"))
        assertEquals(Thread.currentThread().name, event.getJSONObject("tags").getString("thread"))
    }

    @Test fun `a saved crash is sent on the next launch and then removed`() {
        testClient(storageDir = dir).let { first ->
            ApiStudioCrashHandler({ first }, { _, _ -> }).uncaughtException(Thread.currentThread(), nestedCrash())
        }

        server.enqueue(MockResponse().setResponseCode(202))
        server.start()
        val nextLaunch = testClient(baseUrl = server.baseUrl(), storageDir = dir, transport = HttpUrlConnectionTransport())
        nextLaunch.recoverCrashes()
        assertTrue(File(dir, "crashes").listFiles()!!.none { it.name.endsWith(".json") })
        nextLaunch.flushNow(force = true)

        val request = server.takeRequest()
        assertEquals("/api/v1/errors/batch", request.path)
        val event = request.json().getJSONArray("events").getJSONObject(0)
        assertTrue(event.getBoolean("fatal"))
        assertTrue(event.getString("stack_trace").contains("Caused by:"))
        assertEquals(0, nextLaunch.errorQueue.size)
    }

    @Test fun `a crash recovered but not yet sent survives another restart`() {
        testClient(storageDir = dir).let { first ->
            ApiStudioCrashHandler({ first }, { _, _ -> }).uncaughtException(Thread.currentThread(), nestedCrash())
        }
        testClient(storageDir = dir, transport = FakeTransport(503)).apply {
            recoverCrashes()
            flushNow(force = true) // offline
        }
        val third = testClient(storageDir = dir)
        assertEquals(1, third.errorQueue.size)
        assertTrue(third.errorQueue.peek(1).single().getBoolean("fatal"))
    }

    @Test fun `the crash handler never throws, even when recording fails`() {
        var chained = false
        val handler = ApiStudioCrashHandler({ throw RuntimeException("sdk bug") }, { _, _ -> chained = true })
        handler.uncaughtException(Thread.currentThread(), RuntimeException("app crash"))
        assertTrue(chained)
    }

    @Test fun `install keeps the previous handler and is idempotent`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val installed = ApiStudioCrashHandler.install { null }!!
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            assertSame(previous, installed.previous)
            assertEquals(null, ApiStudioCrashHandler.install { null })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test fun `captureException is handled and not fatal, with redacted extra`() {
        val client = testClient()
        client.captureException(nestedCrash(), tags = mapOf("flavor" to "qa"), extra = mapOf("password" to "p", "cart" to "c1"), fingerprint = listOf("checkout"))
        val event = client.errorQueue.peek(1).single()
        assertFalse(event.getBoolean("fatal"))
        assertTrue(event.getBoolean("handled"))
        assertEquals("qa", event.getJSONObject("tags").getString("flavor"))
        assertEquals(Redactor.REDACTED, event.getJSONObject("extra").getString("password"))
        assertEquals("c1", event.getJSONObject("extra").getString("cart"))
        assertEquals("checkout", event.getJSONArray("fingerprint").getString(0))
    }

    @Test fun `a framework lifecycle wrapper is not used as the issue title`() {
        val cause = IllegalStateException("Sample crash")
        val wrapper = RuntimeException("Unable to start activity ComponentInfo{x/.Main}: $cause", cause).apply {
            stackTrace = arrayOf(StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 4280))
        }
        val event = testClient().buildErrorEvent(wrapper, fatal = true, handled = false)!!
        assertEquals("java.lang.IllegalStateException", event.getString("exception_type"))
        assertEquals("Sample crash", event.getString("message"))
        assertTrue(event.getString("stack_trace").startsWith("java.lang.RuntimeException: Unable to start activity"))

        // An app's own RuntimeException keeps its title.
        val own = RuntimeException("mine", cause)
        assertEquals("java.lang.RuntimeException", testClient().buildErrorEvent(own, true, false)!!.getString("exception_type"))
    }
}
