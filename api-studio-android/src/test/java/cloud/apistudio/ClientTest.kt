package cloud.apistudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTest {
    @Test fun `keeps the last 100 breadcrumbs, oldest first, with long messages cut`() {
        val client = testClient()
        repeat(150) { client.addBreadcrumb(Breadcrumb("log", "m$it")) }
        client.addBreadcrumb(Breadcrumb("log", "x".repeat(1000)))
        val crumbs = client.breadcrumbsJson()
        assertEquals(100, crumbs.length())
        assertEquals("m51", crumbs.getJSONObject(0).getString("message"))
        assertEquals(301, crumbs.getJSONObject(99).getString("message").length)
    }

    @Test fun `user and app are attached once set`() {
        val client = testClient { app = AppInfo("Shop", "2.4.0", "118") }
        client.captureMessage("before")
        client.setUser(UserInfo(id = "tester-7", email = "qa@example.com", extra = mapOf("team" to "qa")))
        client.setApp(AppInfo(build = "119"))
        client.captureMessage("after")
        val (before, after) = client.errorQueue.peek(2)
        assertFalse(before.has("user"))
        assertEquals("tester-7", after.getJSONObject("user").getString("id"))
        assertEquals("qa", after.getJSONObject("user").getString("team"))
        assertEquals("2.4.0", after.getJSONObject("app").getString("version"))
        assertEquals("119", after.getJSONObject("app").getString("build"))
        assertEquals("Message", after.getString("exception_type"))
    }

    @Test fun `disabled SDK records nothing`() {
        val client = testClient { enabled = false }
        client.captureMessage("x")
        client.addBreadcrumb(Breadcrumb("log", "x"))
        client.recordRequest(RecordedRequest("GET", "https://shop.test", 0, 1, statusCode = 200))
        assertEquals(0, client.errorQueue.size)
        assertEquals(0, client.logQueue.size)
        assertEquals(0, client.breadcrumbsJson().length())
    }

    @Test fun `queue persists across launches and keeps the newest when full`() {
        val dir = tempDir()
        val file = java.io.File(dir, "q.json")
        PersistentQueue(file, 3, null).apply { repeat(5) { push(JSONObject().put("i", it)) } }
        val next = PersistentQueue(file, 3, null)
        next.push(JSONObject().put("i", 99))
        assertEquals(listOf(3, 4, 99), next.peek(10).map { it.getInt("i") })
        dir.deleteRecursively()
    }

    @Test fun `install id is created once and reused`() {
        val dir = tempDir()
        val a = testClient(storageDir = dir).apply { start(); close() }
        val b = testClient(storageDir = dir).apply { start(); close() }
        val idA = a.deviceJson().getString("install_id")
        assertEquals(idA, b.deviceJson().getString("install_id"))
        assertTrue(a.sessionId != b.sessionId)
        dir.deleteRecursively()
    }
}
