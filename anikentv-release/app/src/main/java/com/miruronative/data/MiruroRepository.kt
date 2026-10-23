package com.miruronative.data

import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.cache.AppCache
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.remote.AniLibertyClient
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.AppNotification
import com.miruronative.data.settings.SettingsStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/** Presentation-facing repository. Public metadata and playback are sourced from AniLiberty v1. */
class MiruroRepository(
    private val liberty: AniLibertyClient,
    private val cache: AppCache,
) {
    /** User preference: keep hentai out of every browsing surface. */
    private val hideAdult: Boolean get() = SettingsStore.hideAdultContent.value

    // ---- public discovery (AniLiberty) ----
    suspend fun trending(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("trending:$page", COLLECTION_TTL, force) {
        if (page == 1) liberty.recommended() else liberty.catalog(
            DiscoverFilters(sort = "POPULARITY_DESC"), page = page,
        )
    }
    suspend fun popular(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("popular:$page", COLLECTION_TTL, force) {
        liberty.catalog(DiscoverFilters(sort = "POPULARITY_DESC"), page = page)
    }
    suspend fun topRated(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("top:$page", COLLECTION_TTL, force) {
        liberty.catalog(DiscoverFilters(sort = "SCORE_DESC"), page = page)
    }
    suspend fun recentlyReleased(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("recent:$page", AIRING_TTL, force) {
            if (page == 1) liberty.latest() else liberty.catalog(
                DiscoverFilters(sort = "START_DATE_DESC"), page = page,
            )
        }

    suspend fun airing(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("airing:$page", AIRING_TTL, force) {
            liberty.catalog(DiscoverFilters(status = "RELEASING"), page = page, limit = 40)
        }

    suspend fun schedule(dayOffset: Int, force: Boolean = false): List<AiringSchedule> {
        val day = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).plusDays(dayOffset.toLong())
        // Cached unfiltered and filtered at read time, so toggling the setting applies instantly.
        val schedules = cache.getOrFetch(
            key = "schedule:liberty:v1:$day",
            serializer = ListSerializer(AiringSchedule.serializer()),
            ttlMs = SCHEDULE_TTL,
            forceRefresh = force,
        ) { liberty.schedule(day) }
        return if (hideAdult) schedules.filterNot { it.media?.isAdult == true } else schedules
    }

    suspend fun search(query: String, page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("search:${query.trim().lowercase()}:$page", SEARCH_TTL, force) {
            liberty.catalog(DiscoverFilters(query = query), page = page)
        }

    suspend fun discover(filters: DiscoverFilters, page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("discover:${filters.cacheKey()}:$page", COLLECTION_TTL, force) {
            liberty.catalog(filters, page = page)
        }

    suspend fun discoverOptions(): DiscoverOptions = cache.getOrFetch(
        key = "discover-options:liberty:v1",
        serializer = DiscoverOptions.serializer(),
        ttlMs = OPTIONS_TTL,
    ) { liberty.genreOptions() }

    // ---- authenticated (AniLiberty account; release IDs are NOT AniList IDs) ----
    suspend fun requestLoginCode() = liberty.requestOtp(AuthManager.deviceId())
    suspend fun completeLogin(code: String) = liberty.loginWithOtp(AuthManager.deviceId(), code)
    suspend fun viewer() = liberty.viewer()
    // AniLiberty v1 has no social notifications endpoint. Do not send its token to AniList.
    suspend fun notifications(@Suppress("UNUSED_PARAMETER") markAllRead: Boolean = false): Pair<List<AppNotification>, Int> =
        emptyList<AppNotification>() to 0
    suspend fun favouriteAnime() = liberty.favouriteAnime()
    suspend fun userAnimeList(@Suppress("UNUSED_PARAMETER") userId: Int) = liberty.userAnimeList()
    suspend fun syncSavedAnime(mediaId: Int, saved: Boolean) = liberty.syncSavedAnime(mediaId, saved)
    suspend fun saveTimecode(episodeId: String, positionSeconds: Double, watched: Boolean) =
        liberty.saveTimecode(episodeId, positionSeconds, watched)

    suspend fun animeInfo(id: Int, force: Boolean = false): Media? = cache.getOrFetch(
        key = "anime:liberty:v1:$id",
        serializer = Media.serializer().nullable,
        ttlMs = INFO_TTL,
        forceRefresh = force,
    ) { liberty.release(id) }

    // ---- streaming (AniLiberty direct HLS) ----
    /** AniLiberty provides the episode UUIDs and HLS URLs itself. */
    suspend fun miruroEpisodes(anilistId: Int, force: Boolean = false): EpisodesResult = cache.getOrFetch(
        key = "episodes:liberty:v1:$anilistId",
        serializer = EpisodesResult.serializer(),
        ttlMs = EPISODES_TTL,
        forceRefresh = force,
    ) { liberty.episodes(anilistId) }

    /** Existing watch screen consumes the same EpisodesResult presentation shape. */
    suspend fun episodes(anilistId: Int): EpisodesResult = miruroEpisodes(anilistId)

    data class ResolvedSources(val sources: SourcesResult, val provider: String)

    /** Resolve a playable stream from available AniLiberty episodes. */
    suspend fun resolveSources(
        anilistId: Int,
        number: Double,
        preferred: String,
        category: Category,
        excludedProviders: Set<String> = emptySet(),
        maxAttempts: Int = 5,
    ): ResolvedSources? {
        val merged = episodes(anilistId)
        val ordered = (listOf(preferred) + merged.providerNames).distinct()

        var attempts = 0
        for (name in ordered) {
            if (attempts >= maxAttempts) break
            if (name in excludedProviders) continue
            val provider = merged.provider(name) ?: continue
            val ep = provider.episodes(category).firstOrNull { it.number == number } ?: continue
            attempts++
            if (name != AniLibertyClient.PROVIDER) continue
            val result = runCatching { liberty.sources(ep.pipeId) }.getOrNull()
            if (result != null && result.streams.isNotEmpty()) {
                return ResolvedSources(result, name)
            }
        }
        return null
    }

    private suspend fun mediaPage(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        fetch: suspend () -> MediaPage,
    ): MediaPage {
        // The adult preference is part of the cache key: filtered and unfiltered pages differ.
        val hideAdult = hideAdult
        val page = cache.getOrFetch(
            key = "media:liberty:v1:$key:${if (hideAdult) "sfw" else "all"}",
            serializer = MediaPage.serializer(),
            ttlMs = ttlMs,
            forceRefresh = force,
            fetch = fetch,
        )
        // Apply the age preference locally across all AniLiberty catalog surfaces.
        return if (hideAdult) page.copy(items = page.items.filterNot { it.isAdult }) else page
    }

    private fun DiscoverFilters.cacheKey(): String = listOf(
        query.trim().lowercase(),
        genres.sorted().joinToString(","),
        tags.sorted().joinToString(","),
        year.orEmpty(),
        status.orEmpty(),
        format.orEmpty(),
        minimumScore.orEmpty(),
        sort,
    ).joinToString("|")

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private companion object {
        const val SCHEDULE_TTL = 15L * 60 * 1000
        const val SEARCH_TTL = 30L * 60 * 1000
        const val AIRING_TTL = 30L * 60 * 1000
        const val COLLECTION_TTL = 4L * 60 * 60 * 1000
        const val EPISODES_TTL = 2L * 60 * 60 * 1000
        const val INFO_TTL = 24L * 60 * 60 * 1000
        const val OPTIONS_TTL = 7L * 24 * 60 * 60 * 1000
    }
}
