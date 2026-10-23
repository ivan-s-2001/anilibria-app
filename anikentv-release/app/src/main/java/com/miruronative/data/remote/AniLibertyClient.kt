package com.miruronative.data.remote

import com.miruronative.data.model.CoverImage
import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * AniLiberty v1 adapter. Everything exposed to the UI uses the old presentation models;
 * API field names, URLs and IDs never leak into a composable.
 *
 * An AniLiberty release ID must never be treated as an AniList ID, even when both are Ints.
 */
class AniLibertyClient(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val genres = ConcurrentHashMap<String, Int>()
    private val root = "https://aniliberty.top/api/v1/"

    suspend fun latest(limit: Int = 30): MediaPage =
        list("/anime/releases/latest", mapOf("limit" to limit.toString()))

    suspend fun recommended(limit: Int = 30): MediaPage =
        list("/anime/releases/recommended", mapOf("limit" to limit.toString()))

    suspend fun catalog(filters: DiscoverFilters, page: Int = 1, limit: Int = 30): MediaPage {
        if (filters.genres.isNotEmpty() && genres.isEmpty()) genreOptions()
        val params = linkedMapOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "f[sorting]" to when (filters.sort) {
                "SCORE_DESC" -> "RATING_DESC"
                "START_DATE" -> "YEAR_ASC"
                "START_DATE_DESC" -> "YEAR_DESC"
                else -> "FRESH_AT_DESC"
            },
        )
        filters.query.takeIf { it.isNotBlank() }?.let { params["f[search]"] = it }
        filters.year?.let {
            params["f[years][from_year]"] = it.toString()
            params["f[years][to_year]"] = it.toString()
        }
        filters.format?.let {
            val normalized = when (it) {
                "TV_SHORT" -> "TV"
                else -> it
            }
            if (normalized in RELEASE_TYPES) params["f[types]"] = normalized
        }
        filters.status?.let {
            when (it) {
                "RELEASING" -> params["f[publish_statuses]"] = "IS_ONGOING"
                "FINISHED" -> params["f[publish_statuses]"] = "IS_NOT_ONGOING"
            }
        }
        val ids = filters.genres.mapNotNull { name ->
            genres[name] ?: GENRE_ALIASES[name]?.firstNotNullOfOrNull(genres::get)
        }
        if (ids.isNotEmpty()) params["f[genres]"] = ids.joinToString(",")

        // AniLiberty has no tag or minimum-score filter. Filter locally, and never send
        // invented query parameters to the public API.
        val result = list("/anime/catalog/releases", params)
        return if (filters.minimumScore != null) {
            result.copy(items = result.items.filter { (it.averageScore ?: 0) >= filters.minimumScore })
        } else result
    }

    suspend fun genreOptions(): DiscoverOptions {
        val payload = get("/anime/genres")
        val list = payload.asList()
        val names = list.mapNotNull { item ->
            val genre = item as? JsonObject ?: return@mapNotNull null
            val id = genre.int("id") ?: return@mapNotNull null
            val name = genre.string("name") ?: return@mapNotNull null
            genres[name] = id
            name
        }
        return DiscoverOptions(genres = names)
    }

    suspend fun release(id: Int): Media? {
        val response = get("/anime/releases/$id") as? JsonObject ?: return null
        return response.toMedia()
    }

    /**
     * AniLiberty schedules by weekday, not by a precise airing timestamp.
     * A zero airingAt means "time not provided"; it must never trigger a timed reminder.
     * Keeping the release's AniLiberty ID ensures schedule cards open the right title.
     */
    suspend fun schedule(day: LocalDate): List<AiringSchedule> {
        val weekday = day.dayOfWeek.value
        return get("/anime/schedule/week").asList().mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val release = row.obj("release") ?: return@mapNotNull null
            if (release.obj("publish_day")?.int("value") != weekday) return@mapNotNull null
            val media = release.toMedia() ?: return@mapNotNull null
            val episode = row.int("next_release_episode_number")
                ?: row.obj("published_release_episode")?.double("ordinal")?.toInt()
                ?: 0
            AiringSchedule(episode = episode, airingAt = 0, media = media)
        }
    }

    suspend fun episodes(id: Int): EpisodesResult {
        val response = get("/anime/releases/$id") as? JsonObject
            ?: return EpisodesResult(emptyList())
        val episodes = response.array("episodes")
            .mapNotNull { item ->
                val episode = item as? JsonObject ?: return@mapNotNull null
                val episodeId = episode.string("id") ?: return@mapNotNull null
                EpisodeItem(
                    pipeId = episodeId,
                    number = episode.double("ordinal") ?: return@mapNotNull null,
                    title = episode.string("name"),
                    image = episode.image("preview"),
                    filler = false,
                )
            }
            .sortedBy(EpisodeItem::number)
        return EpisodesResult(
            listOf(ProviderData(name = PROVIDER, sub = emptyList(), dub = episodes)),
        )
    }

    suspend fun sources(episodeId: String): SourcesResult {
        val response = get("/anime/releases/episodes/$episodeId") as? JsonObject
            ?: return SourcesResult(emptyList(), emptyList(), null, null)
        val streams = listOf(480, 720, 1080).mapNotNull { resolution ->
            val url = response.string("hls_$resolution")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            StreamItem(
                url = absolute(url),
                type = "hls",
                quality = "${resolution}p",
                audio = "ru",
                referer = null,
                isActive = true,
                width = null,
                height = resolution,
            )
        }
        val opening = response.obj("opening")
        val ending = response.obj("ending")
        val skip = if (opening != null || ending != null) SkipTimes(
            introStart = opening?.double("start"),
            introEnd = opening?.double("stop") ?: opening?.double("end"),
            outroStart = ending?.double("start"),
            outroEnd = ending?.double("stop") ?: ending?.double("end"),
        ) else null
        return SourcesResult(streams, emptyList(), skip, null)
    }

    private suspend fun list(path: String, parameters: Map<String, String> = emptyMap()): MediaPage {
        val body = get(path, parameters)
        val entries = body.asList()
        val page = parameters["page"]?.toIntOrNull() ?: 1
        val pagination = (body as? JsonObject)?.obj("meta")?.obj("pagination")
        val hasNext = pagination?.obj("links")?.string("next")?.isNotBlank() == true ||
            (pagination?.int("total_pages") ?: 0) > page
        return MediaPage(entries.mapNotNull { (it as? JsonObject)?.toMedia() }, hasNext, page)
    }

    private suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = root.toHttpUrl().newBuilder().apply {
                addPathSegments(path.trimStart('/'))
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("AniLiberty HTTP ${response.code} on $path")
                val payload = response.body?.string() ?: throw IOException("Empty AniLiberty response")
                json.parseToJsonElement(payload)
            }
        }

    private fun JsonObject.toMedia(): Media? {
        val id = int("id") ?: return null
        val name = obj("name")
        val rating = obj("rating")?.double("average") ?: obj("mal")?.double("rating")
        val poster = image("poster")
        val backdrop = array("background_covers").firstOrNull()
            ?.let { (it as? JsonObject)?.imageSelf() }
        val genres = array("genres").mapNotNull { (it as? JsonObject)?.string("name") }
        val age = obj("age_rating")?.string("value")
        return Media(
            id = id,
            idMal = obj("mal")?.int("id"),
            title = MediaTitle(
                english = name?.string("main") ?: name?.string("english"),
                romaji = name?.string("english"),
            ),
            description = string("description"),
            coverImage = CoverImage(large = poster, extraLarge = poster),
            bannerImage = backdrop ?: poster,
            format = obj("type")?.string("value"),
            season = obj("season")?.string("value"),
            seasonYear = int("year"),
            episodes = int("episodes_total"),
            duration = int("average_duration_of_episode"),
            status = if (bool("is_ongoing") == true) "RELEASING" else "FINISHED",
            averageScore = rating?.let { (it * 10).toInt().coerceIn(0, 100) },
            popularity = int("added_in_users_favorites"),
            favourites = int("added_in_users_favorites"),
            isAdult = age == "R18_PLUS",
            genres = genres,
        )
    }

    private fun JsonElement.asList(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> array("data")
        else -> emptyList()
    }

    private fun JsonObject.image(key: String): String? = obj(key)?.imageSelf()
    private fun JsonObject.imageSelf(): String? =
        (obj("optimized")?.string("preview") ?: string("preview")
            ?: obj("optimized")?.string("thumbnail") ?: string("thumbnail"))?.let(::absolute)

    private fun absolute(value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> "https:$value"
        else -> "https://aniliberty.top/${value.trimStart('/')}"
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.array(key: String): List<JsonElement> =
        (get(key) as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.let { value ->
            value.intOrNull ?: value.doubleOrNull
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
                ?.toInt()
        }
    private fun JsonObject.double(key: String): Double? =
        (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val PROVIDER = "AniLiberty"
        private val RELEASE_TYPES = setOf("TV", "ONA", "WEB", "OVA", "OAD", "MOVIE", "DORAMA", "SPECIAL")
        private val GENRE_ALIASES = mapOf(
            "Action" to listOf("Экшен", "Боевик"),
            "Adventure" to listOf("Приключения"),
            "Comedy" to listOf("Комедия"),
            "Drama" to listOf("Драма"),
            "Fantasy" to listOf("Фэнтези"),
            "Romance" to listOf("Романтика"),
            "Sci-Fi" to listOf("Фантастика", "Научная фантастика"),
            "Slice of Life" to listOf("Повседневность"),
            "Sports" to listOf("Спорт"),
            "Supernatural" to listOf("Сверхъестественное"),
            "Mystery" to listOf("Мистика", "Детектив"),
            "Thriller" to listOf("Триллер"),
        )
    }
}
