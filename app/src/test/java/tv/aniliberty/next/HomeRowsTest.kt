package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test

class HomeRowsTest {
    private val item = ContinueEpisode(1, "episode-uuid", "Тайтл", 1.5, "", null, 30000, 90000, 1)
    private val home = HomeData(emptyList(), listOf(emptyList(), emptyList(), emptyList()), emptyList(), emptyList(), emptyList())

    private fun rows(continuing: List<ContinueEpisode>, play: (ContinueEpisode) -> Unit = {}, more: (ContinueEpisode) -> Unit = {}) =
        homeRows(UiState(Screen.Home, home = home), continuing, {}, play, more,
            shelf = { _, cards, _, key -> Row("shelf:$key", Kind.SHELF, cards = cards) },
            features = { _, _ -> emptyList() }, videos = { emptyList() })

    @Test
    fun continuationComesFirstAndPassesTheExactUuidToBothActions() {
        var selected: ContinueEpisode? = null
        var actions: ContinueEpisode? = null
        val rows = rows(listOf(item), { selected = it }, { actions = it })
        assertEquals("continue-heading", rows.first().key)
        val shelf = rows[1]
        assertEquals("shelf:continue", shelf.key)
        val card = shelf.cards.single()
        assertEquals("continue:1:episode-uuid", card.key)
        assertTrue(card.subtitle.contains("Осталось 1 мин"))
        card.click!!.invoke()
        card.longClick!!.invoke()
        assertEquals(item, selected)
        assertEquals(item, actions)
    }

    @Test
    fun removingContinuationPreservesOtherRowKeysAndNavigationBoundaries() {
        val withHistory = rows(listOf(item))
        val without = rows(emptyList())
        assertEquals("latest-heading", without.first().key)
        assertEquals(withHistory.drop(2).map { it.key }, without.map { it.key })
        assertEquals(1, homeFocusDestination(withHistory, 0, 1))
        assertEquals(2, homeFocusDestination(withHistory, 1, 1)) // Refresh remains reachable.
        assertEquals(3, homeFocusDestination(withHistory, 2, 1))
        assertEquals(1, homeFocusDestination(without, 0, 1))
    }
}
