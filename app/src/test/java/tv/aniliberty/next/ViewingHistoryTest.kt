package tv.aniliberty.next

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class ViewingHistoryTest {
    private val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("continue-test",Context.MODE_PRIVATE)
    private val history=ViewingHistory(prefs)
    private val release=parseRelease(JSONObject("""{"id":42,"name":{"main":"Тайтл"},"episodes":[
        {"id":"first","ordinal":1,"duration":1200,"preview":{"preview":"/first.webp"},"hls_720":"https://example.org/private-stream.m3u8"},
        {"id":"special","ordinal":12.5,"name":"Особая серия","duration":1200,"preview":{"preview":"/special.webp"},"hls_720":"https://example.org/next.m3u8"}
    ]}"""))
    @Before fun clear(){prefs.edit().clear().commit()}
    @Test fun unfinishedEpisodeKeepsExactUuidAndActualPreview(){
        history.save(release,release.episodes[0],300000,1200000,10)
        val item=history.items().single()
        assertEquals("first",item.episodeId);assertFalse(item.nextUp)
        assertEquals("https://cdn.anilibria.top/first.webp",item.preview)
        assertEquals(300000L,item.position)
        assertFalse(prefs.getString("continue-episodes","")!!.contains("private-stream"))
    }
    @Test fun completedEpisodeOffersNextInServerOrderIncludingFractionalOrdinal(){
        history.save(release,release.episodes[0],1200000,1200000,10)
        val item=history.items().single()
        assertEquals("special",item.episodeId);assertTrue(item.nextUp)
        assertEquals(0L,item.position)
        assertTrue(item.episodeTitle.contains("12.5"))
        assertEquals("https://cdn.anilibria.top/special.webp",item.preview)
    }
    @Test fun allFinishedHidesReleaseAndNewSessionReplacesNextUp(){
        history.save(release,release.episodes[0],1200000,1200000,10)
        history.save(release,release.episodes[1],60000,1200000,11)
        assertEquals(1,history.items().size);assertFalse(history.items().single().nextUp)
        history.save(release,release.episodes[1],1200000,1200000,12)
        assertTrue(history.items().isEmpty())
    }
    @Test fun unavailableNextEpisodeDoesNotProduceWatchTarget(){
        val unavailable=release.copy(episodes=listOf(release.episodes[0],release.episodes[1].copy(streams=emptyList())))
        history.save(unavailable,unavailable.episodes[0],1200000,1200000,10)
        assertTrue(history.items().isEmpty())
    }
    @Test fun scheduleHeaderBelongsToItsOwnShelf(){
        val rows=listOf(Row("latest",Kind.HEADING),Row("latest-shelf",Kind.SHELF),Row("continue",Kind.HEADING),Row("continue-shelf",Kind.SHELF),Row("schedule",Kind.DAYS),Row("schedule-shelf",Kind.SHELF))
        assertEquals(0,homeSectionHeader(rows,1))
        assertEquals(2,homeSectionHeader(rows,3))
        assertEquals(4,homeSectionHeader(rows,5))
    }
}
