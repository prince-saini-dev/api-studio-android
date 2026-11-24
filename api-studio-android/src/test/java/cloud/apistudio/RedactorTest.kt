package cloud.apistudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val redactor = Redactor()
    private val R = Redactor.REDACTED

    @Test fun `default headers are redacted case-insensitively`() {
        val result = redactor.redactHeaders(
            mapOf(
                "Authorization" to "Bearer abc", "COOKIE" to "a=b", "Set-Cookie" to "c=d", "x-api-key" to "k",
                "Proxy-Authorization" to "p", "Content-Type" to "application/json",
            ),
        )!!
        assertEquals(R, result["Authorization"])
        assertEquals(R, result["COOKIE"])
        assertEquals(R, result["Set-Cookie"])
        assertEquals(R, result["x-api-key"])
        assertEquals(R, result["Proxy-Authorization"])
        assertEquals("application/json", result["Content-Type"])
    }

    @Test fun `headers named like secret keys are redacted too`() {
        assertEquals(R, redactor.redactHeaders(mapOf("Access-Token" to "t"))!!["Access-Token"])
        assertEquals(R, redactor.redactHeaders(mapOf("Token" to "t"))!!["Token"])
    }

    @Test fun `key matching ignores case, underscores and dashes`() {
        for (key in listOf("password", "PASSWORD", "accessToken", "access-token", "Access_Token", "refreshToken",
            "apiKey", "api-key", "cardNumber", "CVV", "secret", "token", "authorization")) {
            assertTrue(key, redactor.isSecretKey(key))
        }
        assertFalse(redactor.isSecretKey("username"))
        assertFalse(redactor.isSecretKey("tokens"))
    }

    @Test fun `nested JSON bodies are redacted at every depth`() {
        val body = JSONObject("""{"user":{"email":"a@b.c","password":"p","cards":[{"card_number":"4111","cvv":"123","label":"x"}]},"accessToken":"t"}""")
        val result = redactor.redactBody(body) as JSONObject
        val user = result.getJSONObject("user")
        assertEquals("a@b.c", user.getString("email"))
        assertEquals(R, user.getString("password"))
        val card = user.getJSONArray("cards").getJSONObject(0)
        assertEquals(R, card.getString("card_number"))
        assertEquals(R, card.getString("cvv"))
        assertEquals("x", card.getString("label"))
        assertEquals(R, result.getString("accessToken"))
        // The original is untouched.
        assertEquals("p", body.getJSONObject("user").getString("password"))
    }

    @Test fun `strings and maps pass through or get redacted`() {
        assertEquals("password=hunter2", redactor.redactBody("password=hunter2"))
        assertEquals(mapOf("token" to R, "ok" to 1), redactor.redactBody(mapOf("token" to "t", "ok" to 1)))
    }

    @Test fun `query parameters are redacted in the URL and the parsed map`() {
        val url = "https://shop.app/api?page=2&access_token=abc&Token=x&q=a%20b#frag"
        assertEquals("https://shop.app/api?page=2&access_token=$R&Token=$R&q=a%20b#frag", redactor.redactUrl(url))
        val query = redactor.redactQuery(parseQuery(url))!!
        assertEquals("2", query["page"])
        assertEquals(R, query["access_token"])
        assertEquals("a b", query["q"])
        assertEquals("https://shop.app/api", redactor.redactUrl("https://shop.app/api"))
        assertNull(parseQuery("https://shop.app/api?"))
    }

    @Test fun `repeated query keys become a list`() {
        assertEquals(listOf("1", "2"), parseQuery("https://x.test/?id=1&id=2")!!["id"])
    }

    @Test fun `custom keys add to the defaults, or replace them`() {
        val custom = Redactor(headers = listOf("X-Session"), keys = listOf("otp"))
        assertEquals(R, custom.redactHeaders(mapOf("x-session" to "s"))!!["x-session"])
        assertTrue(custom.isSecretKey("OTP"))
        assertTrue(custom.isSecretKey("password"))

        val only = Redactor(keys = listOf("otp"), includeDefaults = false)
        assertFalse(only.isSecretKey("password"))
        assertEquals("Bearer x", only.redactHeaders(mapOf("Authorization" to "Bearer x"))!!["Authorization"])
    }
}
