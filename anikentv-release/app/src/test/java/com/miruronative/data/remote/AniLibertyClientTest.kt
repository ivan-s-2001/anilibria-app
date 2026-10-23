package com.miruronative.data.remote

import com.miruronative.data.model.DiscoverFilters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AniLibertyClientTest {
    private val release = """
        {
          "id":7439,
          "name":{"main":"Мастера меча онлайн","english":"Sword Art Online"},
          "year":2018,
          "poster":{"preview":"/poster.webp","optimized":{"preview":"/small.webp"}},
          "background_covers":[{"preview":"/banner.webp"}],
          "type":{"value":"TV"},
          "rating":{"average":8.25},
          "mal":{"id":11757},
          "genres":[{"id":1,"name":"Приключения"}],
          "episodes_total":12,
          "episodes":[{"id":"ep-1","ordinal":1,"name":"Первая серия"},
                      {"id":"ep-2","ordinal":2.5,"name":"Вторая серия"}]
        }
    """.trimIndent()

    private fun client(handler: (String) -> String): AniLibertyClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val payload = handler(chain.request().url.toString())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return AniLibertyClient(http, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun releaseUsesAniLibertyIdAndRussianTitle() = runBlocking {
        val media = client { release }.release(7439)!!
        assertEquals(7439, media.id)
        assertEquals(11757, media.idMal)
        assertEquals("Мастера меча онлайн", media.title.preferred)
        assertEquals("https://aniliberty.top/small.webp", media.coverImage.best)
        assertEquals("https://aniliberty.top/banner.webp", media.bannerImage)
        assertEquals(82, media.averageScore)
    }

    @Test
    fun episodesRetainFractionalOrdinalsAndEpisodeUuids() = runBlocking {
        val episodes = client { release }.episodes(7439)
        assertEquals(1, episodes.providers.size)
        assertEquals("AniLiberty", episodes.providers.single().name)
        assertEquals("ep-2", episodes.providers.single().dub.last().pipeId)
        assertEquals(2.5, episodes.providers.single().dub.last().number, 0.0)
        assertTrue(episodes.providers.single().sub.isEmpty())
    }

    @Test
    fun sourcesAreResolvedDirectlyWithoutForeignScrapers() = runBlocking {
        val sources = client { url ->
            assertTrue(url.contains("/anime/releases/episodes/ep-1"))
            """{"hls_720":"https://cdn.example.test/video.m3u8",
                "opening":{"start":12,"stop":85}}"""
        }.sources("ep-1")
        assertEquals(1, sources.hlsStreams.size)
        assertEquals(720, sources.hlsStreams.single().height)
        assertEquals(12.0, sources.skip!!.introStart!!, 0.0)
        assertFalse(sources.hlsStreams.single().isEmbed)
    }

    @Test
    fun catalogUsesDocumentedSortingParameters() = runBlocking {
        val client = client { url ->
            assertTrue(url.contains("f%5Bsorting%5D=RATING_DESC"))
            """{"data":[$release],
                "meta":{"pagination":{"total_pages":4,"current_page":1}}}"""
        }
        val result = client.catalog(DiscoverFilters(sort = "SCORE_DESC"))
        assertEquals(1, result.items.size)
        assertTrue(result.hasNextPage)
    }

    @Test
    fun scheduleUsesAniLibertyReleaseIdsAndDoesNotInventAirTimes() = runBlocking {
        val client = client { url ->
            assertTrue(url.contains("/anime/schedule/week"))
            """{"data":[
                {"release":{"id":7439,"name":{"main":"Пятничный релиз"},
                    "publish_day":{"value":5}},"next_release_episode_number":8},
                {"release":{"id":7440,"name":{"main":"Субботний релиз"},
                    "publish_day":{"value":6}},"next_release_episode_number":4}
            ]}"""
        }
        val friday = client.schedule(LocalDate.of(2026, 10, 23))
        assertEquals(1, friday.size)
        assertEquals(7439, friday.single().media?.id)
        assertEquals(8, friday.single().episode)
        assertEquals(0L, friday.single().airingAt)
    }
}
