package ru.radiationx.anilibria.screen.details

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.radiationx.anilibria.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReleasePageAdaptersTest {
    private fun parent() = FrameLayout(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme_TvEpisodes))

    @Test fun titlePageIncludesAllFiveWebSectionsAndSelectsOnClickOnly() {
        var selected = -1
        val tabs = ReleaseTabsAdapter { selected = it }
        val holder = tabs.onCreateViewHolder(parent(), 0)
        tabs.onBindViewHolder(holder, 0)
        val line = (holder.itemView as HorizontalScrollView).getChildAt(0) as LinearLayout
        assertEquals(5, line.childCount)
        line.getChildAt(3).requestFocus()
        assertEquals(-1, selected)
        line.getChildAt(3).performClick()
        assertEquals(3, selected)
        assertTrue(line.getChildAt(3).isActivated)
        assertFalse(line.getChildAt(0).isActivated)
    }

    @Test fun synopsisSpansPageBelowPosterAndActions() {
        val adapter = ReleaseHeaderAdapter({}, {}, {}, {})
        val holder = adapter.onCreateViewHolder(parent(), 0)
        val synopsis = holder.itemView.findViewById<android.view.View>(R.id.rowReleaseDescriptionCard)
        val constraints = synopsis.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(R.id.guideline_left, constraints.startToStart)
        assertEquals(R.id.heroBottom, constraints.topToBottom)
        assertNotNull(holder.itemView.findViewById<android.view.View>(R.id.detailBackdrop))
    }

    @Test fun torrentAndMemberRowsExposeTheirOwnActions() {
        var opened = false
        val adapter = ReleaseTextAdapter()
        adapter.rows = listOf(ReleasePageText("1080p · Эпизоды 1–2", "1.25 ГБ", { opened = true }))
        val holder = adapter.onCreateViewHolder(parent(), 0)
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.itemView.isFocusable)
        holder.itemView.performClick()
        assertTrue(opened)
        adapter.rows = listOf(ReleasePageText("Озвучивание", "Команда"))
        adapter.onBindViewHolder(holder, 0)
        opened = false
        holder.itemView.performClick()
        assertFalse(opened)
    }
}
