package io.github.jstn0713.ktxdelay.data

import io.github.jstn0713.ktxdelay.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val pageUrl: String) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

/** GitHub 에 올라온 최신 버전을 확인한다. */
object UpdateChecker {
    val releasesPage = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest"
    val apkDirectUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/ktx-delay-alert.apk"

    /** 실패하면 null */
    fun fetchLatest(): UpdateInfo? = try {
        val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) {
                null
            } else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = o.optString("tag_name")
                val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull()
                val assets = o.optJSONArray("assets")
                var apk = apkDirectUrl
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val url = assets.getJSONObject(i).optString("browser_download_url")
                        if (url.endsWith(".apk")) apk = url
                    }
                }
                code?.let { UpdateInfo(it, tag.removePrefix("v"), apk, o.optString("html_url", releasesPage)) }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** 앱을 열 때 확인 (이미 확인했으면 6시간 동안은 다시 안 함) */
    fun checkIfDue() {
        val now = System.currentTimeMillis()
        if (AppRepo.update.value != null && now - AppRepo.updateCheckedAt < 6 * 60 * 60 * 1000L) return
        val info = fetchLatest() ?: return
        AppRepo.updateCheckedAt = now
        AppRepo.update.value = info
    }
}
