package tv.aniliberty.next

import android.content.Context
import java.net.URI
import org.json.JSONArray

internal data class VastCampaign(
    val id: String,
    val url: String,
    val advertiser: String,
    val erid: String,
)

/** TV choices take precedence and are also written to an account after signing in. */
internal class SupportAds(
    context: Context,
    private val transport: AccountTransport = AccountHttpTransport(),
) {
    private val prefs = context.getSharedPreferences("tv-settings", Context.MODE_PRIVATE)
    val enabled: Boolean
        get() = prefs.getBoolean("support-video-ads", false)

    val everyEpisode: Boolean
        get() = prefs.getBoolean("support-video-ads-always", false)

    val content: Boolean
        get() = prefs.getBoolean("support-content-ads", false)

    val decided: Boolean
        get() = prefs.getBoolean("support-ads-decided", false)

    fun completeChoice() {
        prefs.edit().putBoolean("support-ads-decided", true).apply()
    }

    fun desired(): Map<String, Boolean> =
        linkedMapOf("video" to enabled, "always" to everyEpisode, "content" to content)

    fun shouldRequest(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong("support-ad-shown:tv", 0)
        return enabled && (everyEpisode || last == 0L || now < last || now - last >= 30 * 60 * 1000)
    }

    fun shown(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("support-ad-shown:tv", now).apply()
    }

    suspend fun campaigns(): List<VastCampaign> {
        if (!enabled) return emptyList()
        return parseVastCampaigns(transport.request("/media/vasts", "GET", null, null))
    }

    fun status(message: String) {
        prefs.edit().putString("support-ad-status", message).apply()
        android.util.Log.i("AniLibertyAds", message)
    }

    fun lastStatus(): String =
        prefs.getString("support-ad-status", "Реклама ещё не запрашивалась").orEmpty()
}

internal fun parseVastCampaigns(body: String): List<VastCampaign> {
    val values = JSONArray(body)
    return (0 until values.length()).mapNotNull { index ->
        val item = values.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url")
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
            return@mapNotNull null
        fun text(key: String) = if (item.isNull(key)) "" else item.optString(key)
        VastCampaign(text("id"), url, text("ad_company_name"), text("ad_erid"))
    }
}

/** Read actual server values before toggling, so a retry cannot undo a previous request. */
internal suspend fun syncSupportAds(
    api: AccountApi,
    token: String,
    initial: AccountProfile,
    desired: Map<String, Boolean>,
    sameSession: () -> Boolean = { true },
): AccountProfile {
    var profile = initial
    for ((key, wanted) in desired) {
        check(sameSession()) { "Аккаунт изменился" }
        val actual =
            when (key) {
                "video" -> profile.videoAds
                "always" -> profile.videoAdsAlways
                else -> profile.contentAds
            }
        check(actual != null) {
            "Сервис не вернул настройки рекламы. Повторите синхронизацию позже."
        }
        if (actual != wanted) profile = api.toggleAdPreference(token, key)
    }
    return profile
}
