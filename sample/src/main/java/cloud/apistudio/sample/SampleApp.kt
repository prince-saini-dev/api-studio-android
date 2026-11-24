package cloud.apistudio.sample

import android.app.Application
import cloud.apistudio.ApiStudio
import cloud.apistudio.ApiStudioOptions
import cloud.apistudio.UserInfo

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiStudio.init(this, BuildConfig.API_KEY, ApiStudioOptions().apply {
            baseUrl = BuildConfig.INGEST_URL
            debug = true
            flushIntervalMs = 2_000
        })
        ApiStudio.setUser(UserInfo(id = "tester-7", email = "qa@example.com"))
    }
}
