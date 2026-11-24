package cloud.apistudio

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** On-device redaction, with the same defaults as the Flutter and JS SDKs. */
public class Redactor @JvmOverloads constructor(
    headers: Collection<String> = emptyList(),
    keys: Collection<String> = emptyList(),
    includeDefaults: Boolean = true,
) {
    private val headers: Set<String> =
        ((if (includeDefaults) DEFAULT_REDACT_HEADERS else emptyList()) + headers).map { it.lowercase() }.toSet()
    private val keys: Set<String> =
        ((if (includeDefaults) DEFAULT_REDACT_KEYS else emptyList()) + keys).map(::normalizeKey).toSet()

    public fun isSecretKey(key: String): Boolean = normalizeKey(key) in keys

    public fun redactHeaders(headers: Map<String, String>?): Map<String, String>? {
        if (headers == null) return null
        val result = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val lower = name.lowercase()
            result[name] = if (lower in this.headers || isSecretKey(lower)) REDACTED else value
        }
        return result
    }

    /** Redacts secret keys at any depth of a parsed JSON body. Strings and other values pass through. */
    @JvmOverloads
    public fun redactBody(value: Any?, depth: Int = 0): Any? {
        if (depth > 20) return value
        return when (value) {
            is JSONObject -> {
                val result = JSONObject()
                for (key in value.keys()) {
                    result.put(key, if (isSecretKey(key)) REDACTED else redactBody(value.opt(key), depth + 1))
                }
                result
            }
            is JSONArray -> {
                val result = JSONArray()
                for (i in 0 until value.length()) result.put(redactBody(value.opt(i), depth + 1))
                result
            }
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>()
                for ((key, item) in value) {
                    val name = key.toString()
                    result[name] = if (isSecretKey(name)) REDACTED else redactBody(item, depth + 1)
                }
                result
            }
            is Collection<*> -> value.map { redactBody(it, depth + 1) }
            else -> value
        }
    }

    public fun redactQuery(query: Map<String, Any>?): Map<String, Any>? {
        if (query == null) return null
        val result = LinkedHashMap<String, Any>()
        for ((key, value) in query) result[key] = if (isSecretKey(key)) REDACTED else value
        return result
    }

    /** Redacts secret query parameters inside a URL string. */
    public fun redactUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val hashStart = url.indexOf('#', queryStart)
        val query = if (hashStart < 0) url.substring(queryStart + 1) else url.substring(queryStart + 1, hashStart)
        val redacted = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair
            else if (isSecretKey(safeDecode(pair.substring(0, eq)))) "${pair.substring(0, eq)}=$REDACTED"
            else pair
        }
        return url.substring(0, queryStart + 1) + redacted + (if (hashStart < 0) "" else url.substring(hashStart))
    }

    public companion object {
        public const val REDACTED: String = "***REDACTED***"

        @JvmField
        public val DEFAULT_REDACT_HEADERS: List<String> =
            listOf("authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization")

        @JvmField
        public val DEFAULT_REDACT_KEYS: List<String> = listOf(
            "password", "token", "access_token", "refresh_token", "secret",
            "api_key", "authorization", "card_number", "cvv",
        )

        /** Case-insensitive and ignores `_` / `-`, so `accessToken` matches `access_token`. */
        @JvmStatic
        public fun normalizeKey(key: String): String = key.lowercase().replace("_", "").replace("-", "")
    }
}

internal fun safeDecode(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (_: Exception) {
    value
}

/** Query parameters of a URL; a repeated key becomes a list. Null when there are none. */
internal fun parseQuery(url: String): Map<String, Any>? {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return null
    val hashStart = url.indexOf('#', queryStart)
    val query = if (hashStart < 0) url.substring(queryStart + 1) else url.substring(queryStart + 1, hashStart)
    if (query.isEmpty()) return null
    val result = LinkedHashMap<String, Any>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val key = safeDecode(if (eq < 0) pair else pair.substring(0, eq))
        val value = if (eq < 0) "" else safeDecode(pair.substring(eq + 1))
        when (val existing = result[key]) {
            null -> result[key] = value
            is MutableList<*> -> @Suppress("UNCHECKED_CAST") (existing as MutableList<String>).add(value)
            else -> result[key] = mutableListOf(existing as String, value)
        }
    }
    return if (result.isEmpty()) null else result
}
