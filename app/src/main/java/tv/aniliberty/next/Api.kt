package tv.aniliberty.next

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Domain identifiers are v1 identifiers. Episode UUIDs are never derived from positions. */
data class Episode(val id: String, val ordinal: Double, val name: String, val preview: String?, val duration: Long, val streams: List<Pair<String, String>>, val order: Double) {
    val label: String get() = name.ifBlank { "Эпизод ${number(ordinal)}" }
}
data class Credit(val role: String, val name: String)
data class Torrent(val label: String, val description: String, val magnet: String?)
data class Release(val id: Int, val alias: String, val title: String, val english: String, val poster: String?, val year: String, val season: String, val type: String, val age: String, val genres: List<String>, val description: String, val episodes: List<Episode>, val credits: List<Credit>, val torrents: List<Torrent>, val blocked: Boolean) {
    val metadata: String get() = listOf(year, season, type, age).filter(String::isNotBlank).joinToString(" · ")
}
fun number(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
fun imageUrl(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.let {
    when { it.startsWith("https://") -> it; it.startsWith("//") -> "https:$it"; it.startsWith("/") -> "https://cdn.anilibria.top$it"; else -> null }
}
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
private fun JSONObject.image(): String? = imageUrl(optJSONObject("optimized")?.text("preview")) ?: imageUrl(text("preview"))
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.display(key: String): String = optJSONObject(key)?.let { it.text("description").ifBlank { it.text("value") } }.orEmpty()

fun parseRelease(json: JSONObject): Release {
    val episodes = json.optJSONArray("episodes").objects().mapNotNull { e ->
        val id = e.text("id"); val ordinal = e.optDouble("ordinal", Double.NaN)
        if (id.isBlank() || !ordinal.isFinite()) null else Episode(id, ordinal, e.text("name"), e.optJSONObject("preview")?.image(), e.optLong("duration", 0).coerceAtLeast(0),
            listOf("1080p" to "hls_1080", "720p" to "hls_720", "480p" to "hls_480").mapNotNull { (quality, key) ->
                e.text(key).takeIf { it.startsWith("https://") }?.let { quality to it }
            }, e.optDouble("sort_order", ordinal))
    }.sortedWith(compareBy<Episode> { it.order }.thenBy { it.ordinal })
    return Release(json.getInt("id"), json.text("alias"), json.optJSONObject("name")?.text("main").orEmpty(), json.optJSONObject("name")?.text("english").orEmpty(), json.optJSONObject("poster")?.image(), json.text("year"), json.display("season"), json.display("type"), json.display("age_rating"), json.optJSONArray("genres").objects().map { it.text("name") }, json.text("description"), episodes,
        json.optJSONArray("members").objects().map { Credit(it.display("role"), it.text("nickname")) },
        json.optJSONArray("torrents").objects().map { Torrent(it.text("label"), listOf(it.text("description"), it.display("quality"), it.display("codec"), "%.2f ГБ".format(it.optDouble("size") / 1073741824.0), "↑ ${it.optInt("seeders")}").filter(String::isNotBlank).joinToString(" · "), it.text("magnet").takeIf { m -> m.startsWith("magnet:") }) },
        json.optBoolean("is_blocked_by_geo") || json.optBoolean("is_blocked_by_copyrights"))
}

class Api {
    private val cache = linkedMapOf<Int, Release>()
    private suspend fun request(path: String): String = withContext(Dispatchers.IO) {
        val connection = URI("https://aniliberty.top/api/v1$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000; connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("Сервис ответил: ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    suspend fun latest(): List<Release> = JSONArray(request("/anime/releases/latest?limit=24")).objects().map(::parseRelease)
    suspend fun catalog(query: String = "", page: Int = 1): Pair<List<Release>, Boolean> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(request("/anime/catalog/releases?limit=30&page=$page&f%5Bsearch%5D=$q"))
        val data = json.optJSONArray("data").objects().map(::parseRelease)
        val meta = json.optJSONObject("meta")
        return data to (meta != null && meta.optInt("current_page", page) < meta.optInt("last_page", page))
    }
    suspend fun release(id: Int): Release {
        synchronized(cache) { cache[id] }?.let { return it }
        val item = parseRelease(JSONObject(request("/anime/releases/$id")))
        synchronized(cache) { if (cache.size >= 24) cache.remove(cache.keys.first()); cache[id] = item }
        return item
    }
}
