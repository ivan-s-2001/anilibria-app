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
    private val body="""[{"release":{"id":42,"added_in_users_favorites":1400,"publish_day":{"value":3,"description":"Среда"},"shikimori":{"rating":8.1}},"next_release_episode_number":7},{"release":{"id":43,"publish_day":{"value":5}},"published_release_episode":{"id":"ep","ordinal":6}},{"release":{"id":44},"full_season_is_released":true}]"""
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
    @Test fun favoritesAndEpisodeStayVisibleWithoutFocusAndDoNotLeakOnRebind(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{})
        val release=parseScheduleWeek(body).first()
        adapter.submit(listOf(Row("schedule:42",Kind.POSTER,release=release,selected=3)))
        val holder=adapter.onCreateViewHolder(android.widget.FrameLayout(org.robolectric.RuntimeEnvironment.getApplication()),Kind.POSTER.ordinal)
        adapter.onBindViewHolder(holder,0)
        val box=holder.itemView as android.widget.FrameLayout
        val rating=box.findViewWithTag<android.widget.TextView>("schedule-favorites")
        assertEquals("1.4к",rating.text.toString())
        assertNotNull(rating.compoundDrawablesRelative[0])
        assertEquals("В избранном у 1400 пользователей",rating.contentDescription)
        assertEquals(android.view.View.VISIBLE,rating.visibility)
        assertEquals("7 эп.",(box.getChildAt(3)as android.widget.TextView).text.toString())
        assertEquals(android.view.View.VISIBLE,box.getChildAt(3).visibility)
        box.onFocusChangeListener?.onFocusChange(box,false)
        assertEquals(android.view.View.VISIBLE,box.getChildAt(3).visibility)
        adapter.submit(listOf(Row("catalog:42",Kind.POSTER,release=release,selected=2)))
        adapter.onBindViewHolder(holder,0)
        assertEquals(android.view.View.GONE,rating.visibility)
    }

    @Test fun favoritesAndCollectionsAreIndependentUnionFilters(){
        val items=(1..4).map{parseRelease(org.json.JSONObject().put("id",it))}
        val favorites=setOf(1,2);val collections=setOf(2,3)
        fun ids(a:Boolean,b:Boolean)=filterScheduleSources(items,favorites,collections,a,b).map{it.id}
        assertEquals(listOf(1,2,3,4),ids(false,false))
        assertEquals(listOf(1,2),ids(true,false))
        assertEquals(listOf(2,3),ids(false,true))
        assertEquals(listOf(1,2,3),ids(true,true))
        assertEquals(listOf(1,2),ids(true,false))
    }

}
