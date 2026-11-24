package cloud.apistudio.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import cloud.apistudio.ApiStudio
import cloud.apistudio.ApiStudioInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Driven with adb:
 *   adb shell am start -n cloud.apistudio.sample/.MainActivity --es action requests
 *   adb shell am start -n cloud.apistudio.sample/.MainActivity --es action crash
 */
class MainActivity : Activity() {
    private val http = OkHttpClient.Builder().addInterceptor(ApiStudioInterceptor()).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "API Studio sample" })
        when (intent.getStringExtra("action")) {
            "requests" -> Thread(::requests).start()
            "crash" -> {
                ApiStudio.addBreadcrumb("user", "Tapped Crash")
                throw IllegalStateException("Sample crash", IllegalArgumentException("bad cart id"))
            }
        }
    }

    private fun requests() {
        val api = BuildConfig.API_URL
        val login = Request.Builder().url("$api/api/login?page=2&access_token=abc")
            .header("Authorization", "Bearer user-secret")
            .post("""{"email":"qa@example.com","password":"hunter2"}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(login).execute().use { it.body?.string() }
        http.newCall(Request.Builder().url("$api/missing").build()).execute().close()
        try {
            http.newCall(Request.Builder().url("http://10.0.2.2:1/refused").build()).execute().close()
        } catch (_: Exception) {
        }
        try {
            error("Handled in sample")
        } catch (e: Exception) {
            ApiStudio.captureException(e, tags = mapOf("flavor" to "sample"))
        }
        ApiStudio.flush()
    }
}
