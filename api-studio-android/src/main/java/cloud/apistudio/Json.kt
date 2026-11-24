package cloud.apistudio

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A request or response body as the protocol stores it. */
internal class CapturedBody(val body: Any?, val sizeBytes: Long?, val truncated: Boolean) {
    companion object {
        val NONE = CapturedBody(null, null, false)
    }
}

private val TEXTUAL = Regex("json|text|xml|javascript|x-www-form-urlencoded|graphql", RegexOption.IGNORE_CASE)

/** No content type counts as textual: most APIs that omit it still return JSON or text. */
internal fun isTextualContentType(contentType: String?): Boolean =
    contentType.isNullOrEmpty() || TEXTUAL.containsMatchIn(contentType)

/**
 * JSON text becomes a JSONObject/JSONArray (so the portal can pretty-print it); other text stays a string.
 * [truncated] says the caller already cut [text]; such text is never parsed.
 */
internal fun captureText(text: String?, sizeBytes: Long?, truncated: Boolean): CapturedBody {
    if (text == null) return CapturedBody(null, sizeBytes, truncated)
    if (text.isEmpty()) return CapturedBody(null, sizeBytes ?: 0, truncated)
    if (truncated) return CapturedBody("$text…", sizeBytes, true)
    val trimmed = text.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            val parsed = JSONTokener(text).nextValue()
            if (parsed is JSONObject || parsed is JSONArray) return CapturedBody(parsed, sizeBytes, false)
        } catch (_: Exception) {
            // Not JSON after all.
        }
    }
    return CapturedBody(text, sizeBytes, false)
}

/** Converts maps, lists and primitives to values JSONObject.put accepts. */
internal fun toJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double -> value
    is Number -> value.toDouble()
    is Map<*, *> -> JSONObject().also { json -> for ((k, v) in value) json.put(k.toString(), toJsonValue(v)) }
    is Collection<*> -> JSONArray().also { json -> value.forEach { json.put(toJsonValue(it)) } }
    is Array<*> -> JSONArray().also { json -> value.forEach { json.put(toJsonValue(it)) } }
    else -> value.toString()
}

internal fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject {
    if (value != null) put(key, toJsonValue(value))
    return this
}

internal fun JSONObject.copy(): JSONObject = JSONObject(toString())
