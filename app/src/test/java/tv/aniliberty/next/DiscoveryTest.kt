package tv.aniliberty.next

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class DiscoveryTest {
    @Test fun franchiseUsesExplicitSortOrderAndKeepsCurrentRelease() {
        val groups=parseFranchiseGroups(JSONArray("""[{"id":"f","name":"Франшиза","franchise_releases":[
            {"sort_order":3,"release":{"id":30,"name":{"main":"Фильм"}}},
            {"sort_order":1,"release":{"id":10,"name":{"main":"Первый сезон"}}},
            {"sort_order":2,"release":{"id":20,"name":{"main":"Второй сезон"}}}
        ]}]"""))
        assertEquals(listOf(10,20,30),groups.single().releases.map{it.id})
    }
    @Test fun recommendationsAreDeduplicatedByAnilibertyIdOnly() {
        val sameTitle= listOf(1,2,2,3).map{parseRelease(JSONObject().put("id",it).put("name",JSONObject().put("main","Одинаковое название")))}
        assertEquals(listOf(2,3),uniqueRecommendations(1,sameTitle).map{it.id})
    }
    @Test fun externalIdsAndRatingsDoNotBorrowValuesFromAnotherSource() {
        val r=parseRelease(JSONObject("""{"id":1,"shikimori":{"id":101,"rating":7.2,"votes":15},"mal":{"id":202,"rating":null}}"""))
        assertEquals(101,r.externalIds.shikimori)
        assertEquals(202,r.externalIds.mal)
        assertEquals("Shikimori",r.ratings.single().source)
        assertEquals(7.2,r.ratings.single().value,0.0)
        val missing=parseRelease(JSONObject("""{"id":2,"shikimori":{"id":null},"mal":null}"""))
        assertNull(missing.externalIds.shikimori);assertNull(missing.externalIds.mal)
        assertTrue(missing.ratings.isEmpty())
    }
}
