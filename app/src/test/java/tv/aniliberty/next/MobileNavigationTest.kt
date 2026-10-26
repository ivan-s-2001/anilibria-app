package tv.aniliberty.next

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import tv.aniliberty.next.mobile.MobileActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MobileNavigationTest {
    @Test
    fun phoneGridOpensDetailsAndKeepsNavigationUsableInBothOrientations() {
        val controller = Robolectric.buildActivity(MobileActivity::class.java).setup().visible()
        val activity = controller.get()
        val model = MobileActivity::class.java.getDeclaredMethod("getModel")
            .apply { isAccessible = true }.invoke(activity) as AppModel
        val list = MobileActivity::class.java.getDeclaredField("list")
            .apply { isAccessible = true }.get(activity) as RecyclerView
        val navigation = MobileActivity::class.java.getDeclaredField("navigation")
            .apply { isAccessible = true }.get(activity) as LinearLayout
        val releases = (1..6).map { parseRelease(JSONObject().put("id", it).put("name", JSONObject().put("main", "Тайтл $it"))) }
        model.state.value = UiState(Screen.Catalog(), releases = releases)
        fun layout(width: Int, height: Int) {
            shadowOf(Looper.getMainLooper()).idle()
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        layout(375, 812)
        assertEquals(3, navigation.childCount)
        assertTrue(list.height > 0)
        assertTrue((0 until 3).all { navigation.getChildAt(it).height >= 48 })
        val poster = list.findViewHolderForAdapterPosition(0)!!.itemView as FrameLayout
        assertTrue(poster.width < list.width)
        assertTrue(poster.getChildAt(0).performClick())
        assertEquals(Screen.Detail(1), model.state.value.screen)
        model.state.value = UiState(Screen.Detail(1), detail = releases.first())
        layout(812, 375)
        assertEquals("О тайтле", (MobileActivity::class.java.getDeclaredField("title")
            .apply { isAccessible = true }.get(activity) as android.widget.TextView).text.toString())
        assertTrue(navigation.isShown)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(Screen.Catalog(), model.state.value.screen)
        controller.pause().stop().destroy()
    }
}
