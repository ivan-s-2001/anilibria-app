package tv.aniliberty.next

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ApiTest {
    @Test fun usesUuidAndSortOrderInsteadOfArrayPosition() {
        val release=parseRelease(JSONObject("""{"id":7439,"name":{"main":"Тайтл"},"episodes":[
            {"id":"uuid-special","ordinal":12.5,"sort_order":2,"name":null,"duration":1580,"preview":{"preview":"/images/preview.jpg"},"hls_720":"https://video.example/episode.m3u8"},
            {"id":"uuid-first","ordinal":1,"sort_order":1,"name":"  Пролог  "}
        ]}"""))
        assertEquals(listOf("uuid-first","uuid-special"),release.episodes.map{it.id})
        assertEquals("Эпизод 12.5",release.episodes[1].label)
        assertEquals("Пролог",release.episodes[0].label)
        assertEquals("https://cdn.anilibria.top/images/preview.jpg",release.episodes[1].preview)
        assertEquals("720p",release.episodes[1].streams.single().first)
    }
    @Test fun latestEpisodeKeepsItsOwnPreviewAndUuid() {
        val r=parseRelease(JSONObject("""{"id":10,"latest_episode":{"id":"latest-uuid","ordinal":3,"duration":1440,"preview":{"preview":"/episode.webp"}}}"""))
        assertEquals("latest-uuid",r.latestEpisode?.id)
        assertEquals(3.0,r.latestEpisode!!.ordinal,0.0)
        assertTrue(r.episodes.isEmpty())
    }
    @Test fun nullableMetadataDoesNotBecomeLiteralNull() {
        val release=parseRelease(JSONObject("""{"id":1,"name":{"main":"Название","english":null},"poster":null,"year":2026,"genres":[],"episodes":[]}"""))
        assertEquals("",release.english);assertNull(release.poster);assertEquals("2026",release.metadata)
    }
    @Test fun optimizedImageAndBlockedStateArePreserved() {
        val r=parseRelease(JSONObject("""{"id":1,"is_blocked_by_geo":true,"poster":{"preview":"/large.jpg","optimized":{"preview":"/small.webp"}}}"""))
        assertTrue(r.blocked);assertEquals("https://cdn.anilibria.top/small.webp",r.poster)
        assertNull(imageUrl("javascript:alert(1)"))
    }
}
