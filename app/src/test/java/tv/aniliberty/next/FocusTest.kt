package tv.aniliberty.next

import android.app.Application
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class FocusTest {
    @Test fun tabsEnterContentOnOkOrDownButNotOnFocus() {
        var selected=-1
        val adapter=PageAdapter({}, {selected=it},{},{},{},{0L to 0L},{null},{})
        adapter.submit(listOf(Row("tabs",Kind.TABS)))
        val parent=FrameLayout(RuntimeEnvironment.getApplication())
        val holder=adapter.onCreateViewHolder(parent,Kind.TABS.ordinal)
        adapter.onBindViewHolder(holder,0)
        val line=(holder.itemView as HorizontalScrollView).getChildAt(0) as LinearLayout
        val tab=(line.getChildAt(3)as LinearLayout).getChildAt(0)
        tab.requestFocus();assertEquals(-1,selected)
        tab.performClick();assertEquals(3,selected)
        selected=-1
        tab.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(-1,selected)
        tab.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(3,selected)
    }
    @Test fun headingsDoNotConsumeRemoteFocus() {
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{})
        adapter.submit(listOf(Row("heading",Kind.HEADING,"Новые эпизоды")))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.HEADING.ordinal)
        adapter.onBindViewHolder(holder,0)
        assertFalse(holder.itemView.isFocusable)
        assertFalse(holder.itemView.isClickable)
    }
}
