package tv.aniliberty.next

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Domain identifiers are v1 identifiers. Episode UUIDs are never derived from positions. */
data class Episode(val id: String, val ordinal: Double, val name: String, val preview: String?, val duration: Long, val streams: List<Pair<String, String>>, val order: Double, val updatedAt:String = "") {
    val label: String get() = name.ifBlank { "Эпизод ${number(ordinal)}" }
}
data class Credit(val role: String, val name: String)
data class Release(val id: Int, val alias: String, val title: String, val english: String, val poster: String?, val year: String, val season: String, val type: String, val age: String, val genres: List<String>, val description: String, val episodes: List<Episode>, val credits: List<Credit>, val blocked: Boolean, val latestEpisode: Episode? = null, val externalIds: ExternalIds = ExternalIds(), val ratings: List<Rating> = emptyList(), val ongoing:Boolean? = null, val updatedAt:String = "",val totalEpisodes:Int?=null,val releasedEpisodes:Int?=null) {
    val catalogueEpisodeCount:Int? get() = if(ongoing==true) releasedEpisodes else totalEpisodes
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
            }, e.optDouble("sort_order", ordinal),e.text("updated_at"))
    }.sortedWith(compareBy<Episode> { it.order }.thenBy { it.ordinal })
    return Release(json.getInt("id"), json.text("alias"), json.optJSONObject("name")?.text("main").orEmpty(), json.optJSONObject("name")?.text("english").orEmpty(), json.optJSONObject("poster")?.image(), json.text("year"), json.display("season"), json.display("type"), json.display("age_rating"), json.optJSONArray("genres").objects().map { it.text("name") }, json.text("description"), episodes,
        json.optJSONArray("members").objects().map { Credit(it.display("role"), it.text("nickname")) },
        json.optBoolean("is_blocked_by_geo") || json.optBoolean("is_blocked_by_copyrights"),
        json.optJSONObject("latest_episode")?.let { parseRelease(JSONObject().put("id",json.getInt("id")).put("episodes",JSONArray().put(it))).episodes.firstOrNull() },
        ExternalIds(json.optJSONObject("shikimori")?.optInt("id")?.takeIf { it > 0 },json.optJSONObject("mal")?.optInt("id")?.takeIf { it > 0 }),
        listOf("Shikimori" to "shikimori", "MyAnimeList" to "mal").mapNotNull { (source,key) -> json.optJSONObject(key)?.let { site -> site.optDouble("rating",Double.NaN).takeIf { it.isFinite() && it in 1.0..10.0 }?.let { Rating(source,it,site.optInt("votes").takeIf { it > 0 }) } } }).copy(ongoing=if(json.has("is_ongoing") && !json.isNull("is_ongoing"))json.getBoolean("is_ongoing")else null,updatedAt=json.text("updated_at"),totalEpisodes=json.optInt("episodes_total",-1).takeIf{it>0},releasedEpisodes=json.optJSONArray("episodes")?.let{publishedEpisodeCount(it)})
}

internal fun publishedEpisodeCount(episodes:JSONArray):Int = episodes.objects()
    .map{it.text("id")}.filter{it.isNotBlank()}.distinct().size

internal fun parseReleasedEpisodeCounts(body:String):Map<Int,Int> =
    JSONObject(body).optJSONArray("data").objects().mapNotNull{release->
        val id=release.optInt("id",-1)
        val episodes=release.optJSONArray("episodes")
        if(id>0 && episodes!=null)id to publishedEpisodeCount(episodes)else null
    }.toMap()

class Api {
    private val cache = linkedMapOf<Int, Release>()
    private val discoveryCache = linkedMapOf<Int, Pair<Long,Discovery>>()
    private suspend fun request(path: String): String = withContext(Dispatchers.IO) {
        val connection = URI("https://aniliberty.top/api/v1$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000; connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("Сервис ответил: ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    suspend fun home(): HomeData = supervisorScope {
        suspend fun <T> optional(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
        val promotions = async { optional { JSONObject(request("/media/promotions")).optJSONArray("data").objects().filter { !it.optBoolean("is_ad") && it.optJSONObject("release") != null }.map { Promotion(it.text("id"), it.optJSONObject("image")?.image(), parseRelease(it.getJSONObject("release"))) } } }
        val latest = async { latest() }
        val schedule = async { optional { val json = JSONObject(request("/anime/schedule/now")); listOf("yesterday", "today", "tomorrow").map { day -> json.optJSONArray(day).objects().mapNotNull { entry -> entry.optJSONObject("release")?.let { release -> entry.optJSONObject("published_release_episode")?.let { release.put("latest_episode",it) }; parseRelease(release) } } } } }
        val franchises = async { optional { randomFeatures(HomeSection.FRANCHISES) } }
        val genres = async { optional { randomFeatures(HomeSection.GENRES) } }
        val videos = async { optional { videos(9) } }
        HomeData(promotions.await(),latest.await(),schedule.await(),franchises.await(),genres.await(),videos.await())
    }
    suspend fun randomFeatures(section:HomeSection):List<Feature> = when(section){
        HomeSection.GENRES->features("/anime/genres/random?limit=4",false)
        HomeSection.FRANCHISES->features("/anime/franchises/random?limit=3",true)
        else->error("Этот раздел не поддерживает случайную подборку")
    }
    private suspend fun features(path:String,franchise:Boolean):List<Feature> = JSONArray(request(path)).objects().map { feature ->
        val subtitle=if(franchise)listOf(
            listOf(feature.text("first_year"),feature.text("last_year")).filter(String::isNotBlank).distinct().joinToString("–"),
            feature.text("total_releases").takeIf{it.isNotBlank()}?.let{"$it частей"}.orEmpty()
        ).filter(String::isNotBlank).joinToString(" · ") else feature.text("total_releases").takeIf{it.isNotBlank()}?.let{"$it релизов"}.orEmpty()
        Feature(feature.text("id"),feature.text("name"),subtitle,feature.optJSONObject("image")?.image())
    }.distinctBy{it.id}
    private suspend fun videos(limit:Int):List<HomeVideo> = parseHomeVideos(request("/media/videos?limit=$limit"))
    suspend fun releaseTorrents(releaseId:Int):List<TorrentLink> = JSONArray(request("/anime/torrents/release/$releaseId")).objects().map{t->
        TorrentLink(t.text("label").ifBlank{listOf(t.display("quality"),t.display("codec")).filter(String::isNotBlank).joinToString(" · ")},t.text("description"),t.text("magnet").takeIf{it.startsWith("magnet:")})
    }
    private suspend fun recentEpisodesPage(page:Int):SectionData {
        val body=request(catalogPath(Screen.Catalog(),page))
        val parsed=parseCatalogPage(body,page)
        if(parsed.releases.isEmpty())return SectionData()
        val ids=parsed.releases.joinToString(","){it.id.toString()}
        val details=JSONObject(request("/anime/releases/list?ids=$ids&limit=30&include=id,episodes.id,episodes.ordinal,episodes.name,episodes.updated_at,episodes.preview,episodes.sort_order,episodes.duration"))
            .optJSONArray("data").objects().associate{it.getInt("id") to parseRelease(it).episodes}
        val items=parsed.releases.map{release->
            val episodes=details[release.id]?:error("Не удалось получить серии релиза")
            release.copy(latestEpisode=episodes.maxByOrNull{it.updatedAt})
        }.filter{it.latestEpisode!=null}
        val cutoff=java.time.LocalDate.now().minusMonths(1)
        // The catalogue is ordered by fresh_at; only its ordered dates can end pagination.
        val freshDates=JSONObject(body).optJSONArray("data").objects().map{json->
            runCatching{java.time.OffsetDateTime.parse(json.text("fresh_at")).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()}.getOrNull()
        }
        val reachedEnd=freshDates.isNotEmpty() && freshDates.all{it!=null && it.isBefore(cutoff)}
        return SectionData(releases=recentEpisodeReleases(items),more=parsed.more && !reachedEnd)
    }
    suspend fun section(section:HomeSection,page:Int=1):SectionData = when(section){
        HomeSection.LATEST -> recentEpisodesPage(page)
        HomeSection.SCHEDULE -> SectionData(releases=JSONObject(request("/anime/schedule/week")).optJSONArray("data").objects().mapNotNull{entry->
            entry.optJSONObject("release")?.let{release->entry.optJSONObject("published_release_episode")?.let{release.put("latest_episode",it)};parseRelease(release)}
        }.distinctBy{it.id})
        HomeSection.FRANCHISES -> SectionData(features=features("/anime/franchises",true).sortedBy{it.title.lowercase()})
        HomeSection.GENRES -> SectionData(features=features("/anime/genres",false).sortedBy{it.title.lowercase()})
        HomeSection.VIDEOS -> videos(page*30).let{SectionData(videos=it,more=it.size>=page*30)}
    }
    suspend fun discovery(releaseId: Int): Discovery {
        synchronized(discoveryCache) { discoveryCache[releaseId] }?.takeIf { android.os.SystemClock.elapsedRealtime()-it.first < 86400000 }?.let { return it.second }
        val result = supervisorScope {
            suspend fun <T> optional(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
            val groups = async { optional { parseFranchiseGroups(JSONArray(request("/anime/franchises/release/$releaseId"))) } }
            val recommendations = async { optional { uniqueRecommendations(releaseId,JSONArray(request("/anime/releases/recommended?release_id=$releaseId&limit=12")).objects().map(::parseRelease)) } }
            Discovery(groups.await(), recommendations.await())
        }
        if(result.franchises != null && result.recommendations != null) synchronized(discoveryCache) {
            if(discoveryCache.size>=32)discoveryCache.remove(discoveryCache.keys.first())
            discoveryCache[releaseId]=android.os.SystemClock.elapsedRealtime() to result
        }
        return result
    }
    suspend fun franchise(id: String): List<Release> = JSONObject(request("/anime/franchises/${URLEncoder.encode(id,"UTF-8")}")).optJSONArray("franchise_releases").objects().sortedBy { it.optInt("sort_order") }.mapNotNull { it.optJSONObject("release")?.let(::parseRelease) }
    suspend fun libraryReleases(ids:List<Int>):List<Release> = ids.distinct().chunked(30).flatMap{batch->
        JSONObject(request("/anime/releases/list?ids=${batch.joinToString(",")}&limit=30&exclude=episodes,torrents,members,sponsors")).optJSONArray("data").objects().map(::parseRelease)
    }.sortedBy{ids.indexOf(it.id)}
    suspend fun latest(limit:Int=9): List<Release> = JSONArray(request("/anime/releases/latest?limit=$limit")).objects().map(::parseRelease)
    suspend fun catalog(screen:Screen.Catalog,page:Int=screen.page):CatalogPage {
        val result=parseCatalogPage(request(catalogPath(screen,page)),page)
        val ids=result.releases.filter{it.ongoing==true}.map{it.id}
        if(ids.isEmpty())return result
        // One batch per catalogue page; planned episodes_total is never a released count.
        val counts=try {
            parseReleasedEpisodeCounts(request("/anime/releases/list?ids=${ids.joinToString(",")}&limit=${ids.size}&include=id,episodes.id"))
        } catch(cancelled:CancellationException){throw cancelled}
          catch(_:Exception){emptyMap<Int,Int>()}
        return result.copy(releases=result.releases.map{release->
            if(release.ongoing==true)release.copy(releasedEpisodes=counts[release.id])else release
        })
    }
    private var references:Map<String,List<CatalogChoice>>?=null
    suspend fun catalogReferences():Map<String,List<CatalogChoice>>{
        references?.let{return it}
        val result=supervisorScope{
            catalogReferencePaths.keys.associateWith{key->async{parseCatalogChoices(request("/anime/catalog/references/$key"),key=="years")}}.mapValues{it.value.await()}
        }
        references=result
        return result
    }
    suspend fun release(id: Int, refresh:Boolean=false): Release {
        if(!refresh)synchronized(cache) { cache[id] }?.let { return it }
        val item = parseRelease(JSONObject(request("/anime/releases/$id?exclude=torrents")))
        synchronized(cache) { if (cache.size >= 24) cache.remove(cache.keys.first()); cache[id] = item }
        return item
    }
}


data class Promotion(val id: String, val image: String?, val release: Release)
data class Feature(val id: String, val title: String, val subtitle: String, val image: String?)
data class HomeData(val promotions: List<Promotion>?, val latest: List<Release>, val schedule: List<List<Release>>?, val franchises: List<Feature>?, val genres: List<Feature>?, val videos: List<HomeVideo>? = null)

data class HomeVideo(val id: String, val title: String, val subtitle: String, val image: String?, val url: String)

data class ExternalIds(val shikimori: Int? = null, val mal: Int? = null)
data class Rating(val source: String, val value: Double, val votes: Int?)
data class FranchiseGroup(val id: String, val title: String, val releases: List<Release>)
data class Discovery(val franchises: List<FranchiseGroup>?, val recommendations: List<Release>?)
fun uniqueRecommendations(currentId: Int, releases: List<Release>): List<Release> = releases.filter { it.id != currentId }.distinctBy { it.id }
fun parseFranchiseGroups(json: JSONArray): List<FranchiseGroup> = json.objects().map { group ->
    FranchiseGroup(group.text("id"), group.text("name"), group.optJSONArray("franchise_releases").objects().sortedBy { it.optInt("sort_order") }.mapNotNull { it.optJSONObject("release")?.let(::parseRelease) }.distinctBy { it.id })
}


enum class HomeSection(val title:String){
    LATEST("Новые эпизоды"),SCHEDULE("Расписание релизов"),FRANCHISES("Франшизы"),GENRES("Жанры"),VIDEOS("Видео команды")
}
data class SectionData(val releases:List<Release> = emptyList(),val features:List<Feature> = emptyList(),val videos:List<HomeVideo> = emptyList(),val more:Boolean=false)

data class TorrentLink(val title:String,val description:String,val magnet:String?)

fun replaceHomeFeatures(home:HomeData,section:HomeSection,features:List<Feature>):HomeData = when(section){
    HomeSection.GENRES->home.copy(genres=features.take(4))
    HomeSection.FRANCHISES->home.copy(franchises=features.take(3))
    else->error("Раздел не является случайной подборкой")
}


/** /media/videos returns an array, as consumed by the website's videoContentProxy. */
fun parseHomeVideos(body:String):List<HomeVideo>{
    val data=if(body.trimStart().startsWith("["))JSONArray(body)else JSONObject(body).optJSONArray("data")?:JSONArray()
    return data.objects().map{item->
        val origin=item.optJSONObject("origin")
        val direct=item.text("url").takeIf{it.startsWith("https://")}
        val youtubeId=item.text("video_id").takeIf{Regex("[A-Za-z0-9_-]{11}").matches(it)}
        val url=direct?:if(origin?.optJSONObject("type")?.text("value")?.startsWith("YOUTUBE")==true && youtubeId!=null)"https://www.youtube.com/watch?v=$youtubeId"else ""
        HomeVideo(item.text("id"),item.text("title"),
            listOf(origin?.text("title").orEmpty(),item.text("views").takeIf{it.isNotBlank()}?.let{"$it просмотров"}.orEmpty(),item.text("comments").takeIf{it.isNotBlank()}?.let{"$it комментариев"}.orEmpty(),updateTimestamp(item.text("created_at"))).filter(String::isNotBlank).joinToString(" · "),
            item.optJSONObject("image")?.image(),url)
    }.distinctBy{it.id}
}

internal fun mergeSectionData(previous:SectionData,next:SectionData):SectionData {
    val releases=(previous.releases+next.releases).distinctBy{it.id}
    val features=(previous.features+next.features).distinctBy{it.id}
    val videos=(previous.videos+next.videos).distinctBy{it.id}
    val grew=releases.size>previous.releases.size || features.size>previous.features.size || videos.size>previous.videos.size
    return SectionData(releases,features,videos,next.more && grew)
}

internal fun filterSectionData(data:SectionData,query:String):SectionData {
    val tokens=query.trim().lowercase().replace('ё','е').split(Regex("\\s+")).filter{it.isNotBlank()}
    fun matches(vararg fields:String):Boolean {
        val text=fields.joinToString(" ").lowercase().replace('ё','е')
        return tokens.all{text.contains(it)}
    }
    return data.copy(releases=data.releases.filter{matches(it.title,it.english)},features=data.features.filter{matches(it.title)},videos=data.videos.filter{matches(it.title)})
}
