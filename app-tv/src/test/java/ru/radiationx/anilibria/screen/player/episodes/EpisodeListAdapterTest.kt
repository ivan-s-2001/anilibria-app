package ru.radiationx.anilibria.screen.player.episodes

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.radiationx.anilibria.R
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class EpisodeListAdapterTest {
    private fun episode(number: String, watched: Boolean = false) = PlayerEpisodesViewModel.Action(
        number.replace(".", "").toLong(), EpisodeId(number, ReleaseId(7439)),
        episodeDisplayTitle(null, number), "Продолжить с 05:20", number, watched,
    )

    @Test fun rowInflatesBindsAndStartsExactFractionalEpisode() {
        val episode = episode("12.5")
        var clicked: EpisodeId? = null
        val adapter = EpisodeListAdapter({ clicked = it.episodeId }, {})
        adapter.submit(listOf(EpisodeListRow(episode = episode)))
        val parent = FrameLayout(ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.AppTheme_TvEpisodes,
        ))
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        assertEquals("12.5", holder.itemView.findViewById<TextView>(R.id.episode_number).text.toString())
        assertEquals("Серия 12.5", holder.itemView.findViewById<TextView>(R.id.episode_title).text.toString())
        assertEquals("Продолжить с 05:20", holder.itemView.findViewById<TextView>(R.id.episode_description).text.toString())
        holder.itemView.performClick()
        assertEquals(episode.episodeId, clicked)
    }

    @Test fun unnamedEpisodesHaveReadableTitlesWithoutChangingTheirNumbers() {
        assertEquals("Серия 1", episodeDisplayTitle(null, "1"))
        assertEquals("Серия 12.5", episodeDisplayTitle("  ", "12.5"))
        assertEquals("Настоящее название", episodeDisplayTitle("Настоящее название", "2"))
    }

    @Test fun filteringAndReverseOrderPreservePlaybackIdentifiers() {
        val first = episode("1", watched = true)
        val special = episode("1.5")
        val last = episode("2")
        val groups = listOf(PlayerEpisodesViewModel.Group(0, "Сезон", listOf(first, special, last)))
        assertEquals(listOf(last.episodeId, special.episodeId),
            episodeRows(groups, descending = true, hideWatched = true).map { it.episode!!.episodeId })
        assertEquals(listOf(first.episodeId, special.episodeId, last.episodeId),
            episodeRows(groups, descending = false, hideWatched = false).map { it.episode!!.episodeId })
        assertTrue(episodeRows(listOf(groups.single().copy(actions = listOf(first))), false, true).isEmpty())
    }

    @Test fun groupHeadersAreNotPlaybackActions() {
        val groups = listOf(
            PlayerEpisodesViewModel.Group(0, "Первый сезон", listOf(episode("1"))),
            PlayerEpisodesViewModel.Group(2, "Второй сезон", listOf(episode("2"))),
        )
        val rows = episodeRows(groups, false, false)
        assertEquals("Первый сезон", rows.first().groupTitle)
        assertNull(rows.first().episode)
        assertEquals(4, rows.size)
    }
}
