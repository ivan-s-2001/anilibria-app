package tv.aniliberty.next

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ScheduleTest {
    private val body="""[{"release":{"id":42,"publish_day":{"value":3,"description":"Среда"},"shikimori":{"rating":8.1}},"next_release_episode_number":7},{"release":{"id":43,"publish_day":{"value":5}},"published_release_episode":{"id":"ep","ordinal":6}},{"release":{"id":44},"full_season_is_released":true}]"""
    @Test fun handlesWebsiteArrayAndDocumentedEnvelope(){
        assertEquals(parseScheduleWeek(body),parseScheduleWeek("""{"data":$body}"""))
        val releases=parseScheduleWeek(body)
        assertEquals(3,releases[0].publishDay)
        assertEquals("7 эп.",scheduleEpisodeLabel(releases[0]))
        assertEquals(8.1,releases[0].ratings.single().value,0.0)
        assertEquals("6 эп.",scheduleEpisodeLabel(releases[1]))
        assertEquals("ep",releases[1].latestEpisode?.id)
        assertEquals("Полный сезон",scheduleEpisodeLabel(releases[2]))
    }
    @Test fun missingNumberDoesNotInventEpisodeOne(){
        val release=parseScheduleEntries(JSONArray("""[{"release":{"id":42},"next_release_episode_number":null}]""")).single()
        assertEquals("Эпизод —",scheduleEpisodeLabel(release))
        assertNull(release.publishDay)
    }
    @Test fun publishedEpisodeIsNotReplacedByNextExpectedEpisode(){
        val release=parseScheduleEntries(JSONArray("""[{"release":{"id":42},"published_release_episode":{"id":"e","ordinal":3.5},"next_release_episode_number":4}]""")).single()
        assertEquals("3.5 эп.",scheduleEpisodeLabel(release))
    }
}
