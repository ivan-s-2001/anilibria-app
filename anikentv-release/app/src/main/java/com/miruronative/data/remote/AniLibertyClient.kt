package com.miruronative.data.remote

import com.miruronative.data.model.CoverImage
import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.MediaListGroup
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.UserAvatar
import com.miruronative.data.model.Viewer
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
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

    data class OtpChallenge(val code: String, val remainingSeconds: Int)

    suspend fun requestOtp(deviceId: String): OtpChallenge {
        val response = send(
            "/accounts/otp/get", "POST",
            buildJsonObject { put("device_id", deviceId) },
        ) as? JsonObject ?: error("Empty OTP response")
        return OtpChallenge(
            code = response.obj("otp")?.string("code") ?: error("OTP code missing"),
            remainingSeconds = response.int("remaining_time") ?: 120,
        )
    }

    /** The user confirms the visible code in an authenticated AniLiberty web session. */
    suspend fun loginWithOtp(deviceId: String, code: String): String {
        val response = send(
            "/accounts/otp/login", "POST",
            buildJsonObject {
                put("device_id", deviceId)
                put("code", code.toIntOrNull() ?: error("Invalid OTP"))
            },
        ) as? JsonObject ?: error("Empty login response")
        return response.string("token")?.takeIf(String::isNotBlank) ?: error("Login token missing")
    }

    suspend fun viewer(): Viewer? {
        val response = get("/accounts/users/me/profile", authenticated = true) as? JsonObject ?: return null
        val id = response.int("id") ?: return null
        return Viewer(
            id = id,
            name = response.string("nickname") ?: response.string("login") ?: "AniLiberty",
            avatar = UserAvatar(large = response.image("avatar")),
            createdAt = response.string("created_at")?.let { date ->
                runCatching { OffsetDateTime.parse(date).toEpochSecond() }.getOrNull()
            },
        )
    }

    suspend fun userAnimeList(): MediaListCollection = coroutineScope {
        val categories = COLLECTION_STATUSES.map { (libertyStatus, uiStatus) ->
            async {
                val releases = accountReleases(
                    "/accounts/users/me/collections/releases",
                    mapOf("type_of_collection" to libertyStatus),
                )
                MediaListGroup(
                    name = libertyStatus,
                    status = uiStatus,
                    entries = releases.map { media ->
                        MediaListEntry(id = media.id, status = uiStatus, media = media)
                    },
                )
            }
        }
        MediaListCollection(categories.map { it.await() })
    }

    suspend fun favouriteAnime(): List<Media> =
        accountReleases("/accounts/users/me/favorites/releases")

    /** Local watchlist corresponds to PLANNED. Do not overwrite another collection status. */
    suspend fun syncSavedAnime(releaseId: Int, saved: Boolean) {
        val known = get("/accounts/users/me/collections/ids", authenticated = true)
            .asList().mapNotNull { item ->
                val pair = item as? JsonArray ?: return@mapNotNull null
                val id = (pair.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val category = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                id to category
            }.toMap()
        val category = known[releaseId]
        if (saved && category == null) {
            send("/accounts/users/me/collections", "POST", buildJsonArray {
                add(buildJsonObject {
                    put("release_id", releaseId)
                    put("type_of_collection", "PLANNED")
                })
            }, authenticated = true)
        } else if (!saved && category == "PLANNED") {
            send("/accounts/users/me/collections", "DELETE", buildJsonArray {
                add(buildJsonObject { put("release_id", releaseId) })
            }, authenticated = true)
        }
    }

    suspend fun saveTimecode(episodeId: String, seconds: Double, watched: Boolean) {
        send("/accounts/users/me/views/timecodes", "POST", buildJsonArray {
            add(buildJsonObject {
                put("release_episode_id", episodeId)
                put("time", seconds.coerceAtLeast(0.0))
                put("is_watched", watched)
            })
        }, authenticated = true)
    }

    private suspend fun accountReleases(path: String, extra: Map<String, String> = emptyMap()): List<Media> {
        val results = mutableListOf<Media>()
        var page = 1
        do {
            val response = get(path, extra + mapOf("page" to page.toString(), "limit" to "100"), authenticated = true)
            results += response.asList().mapNotNull { (it as? JsonObject)?.toMedia() }
            val pages = (response as? JsonObject)?.obj("meta")?.obj("pagination")?.int("total_pages") ?: 1
            if (page >= pages) break
            page++
        } while (page <= 100)
        return results
    }

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

    private suspend fun get(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
    ): JsonElement = send(path, "GET", parameters = parameters, authenticated = authenticated)

    private suspend fun send(
        path: String,
        method: String,
        body: JsonElement? = null,
        parameters: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val url = root.toHttpUrl().newBuilder().apply {
                addPathSegments(path.trimStart('/'))
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (authenticated) {
                builder.header("Authorization", "Bearer ${AuthManager.current() ?: error("Not signed in")}")
            }
            if (method != "GET") {
                val requestBody = (body ?: JsonObject(emptyMap())).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                builder.method(method, requestBody)
            }
            val request = builder.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("AniLiberty HTTP ${response.code} on $path")
                val payload = response.body?.string()?.takeIf(String::isNotBlank) ?: "{}"
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
        private val COLLECTION_STATUSES = listOf(
            "WATCHING" to "CURRENT",
            "PLANNED" to "PLANNING",
            "POSTPONED" to "PAUSED",
            "WATCHED" to "COMPLETED",
            "ABANDONED" to "DROPPED",
        )
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
