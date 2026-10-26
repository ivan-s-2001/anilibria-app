package tv.aniliberty.next

import android.view.View
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeHeroViewTest {
    @Test
    fun phoneAndTvKeepThePosterVerticalAndOpenTheSameTitle() {
        val release = Release(1, "", "Название тайтла", "", null, "2026", "", "", "", emptyList(), "Описание", emptyList(), emptyList(), false)
        listOf(false to 375, true to 960).forEach { (tv, width) ->
            var opened = false
            val view = HomeHeroView(RuntimeEnvironment.getApplication(), tv)
            view.bind(release) { opened = true }
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
            val poster = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<ImageView>().single()
            assertTrue(poster.height > poster.width)
            assertTrue(poster.left >= 0 && poster.right <= width)
            assertTrue(view.contentDescription.contains(release.title))
            assertTrue(view.performClick())
            assertTrue(opened)
        }
    }
}
