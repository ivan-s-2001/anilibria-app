package tv.aniliberty.next

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class DetailFocusTest {
    @Test fun threeColumnEpisodesClampShortLastRowAndNeverWrapAtHorizontalEdges(){
        val rows=listOf(Row("hero",Kind.HERO),Row("tabs",Kind.TABS))+(0..7).map{Row("ep:$it",Kind.EPISODE)}
        assertNull(detailGridTarget(rows,2,View.FOCUS_LEFT))
        assertNull(detailGridTarget(rows,4,View.FOCUS_RIGHT))
        assertNull(detailGridTarget(rows,2,View.FOCUS_UP))
        assertEquals(5,detailGridTarget(rows,2,View.FOCUS_DOWN))
        assertEquals(9,detailGridTarget(rows,7,View.FOCUS_DOWN))
        assertNull(detailGridTarget(rows,9,View.FOCUS_DOWN))
        assertNull(detailGridTarget(rows,RecyclerView.NO_POSITION,View.FOCUS_DOWN))
    }
    @Test fun titleUpdatesPreserveExactActionTabAndEpisodeWithoutMovingViewport(){
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("viewing",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("account-session",Context.MODE_PRIVATE).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity=controller.get()
        val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity)as TvModel
        val list=MainActivity::class.java.getDeclaredField("list").apply{isAccessible=true}.get(activity)as RecyclerView
        val release=parseRelease(JSONObject("""{"id":42,"name":{"main":"Тайтл","english":"Title"},"updated_at":"2026-10-22T03:15:00Z","episodes":[{"id":"ep1","ordinal":1,"duration":1200,"hls_720":"https://example.test/a.m3u8"},{"id":"ep2","ordinal":2,"duration":1200,"hls_720":"https://example.test/b.m3u8"},{"id":"ep3","ordinal":3,"duration":1200,"hls_720":"https://example.test/c.m3u8"}]}"""))
        fun layout(){
            shadowOf(Looper.getMainLooper()).idle()
            val root=activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));root.layout(0,0,1920,1080)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun render(state:UiState){model.state.value=state;layout()}
        fun key(code:Int){activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code));layout()}
        val state=UiState(Screen.Detail(42),detail=release)
        render(state)
        val hero=list.findViewHolderForAdapterPosition(0)!!.itemView
        val favorite=hero.findViewWithTag<View>("hero-action:${R.drawable.ic_favorite}")
        favorite.requestFocus();layout()
        val before=hero.top
        render(state.copy(discoveryLoading=true))
        val rebound=list.findViewHolderForAdapterPosition(0)!!.itemView.findViewWithTag<View>("hero-action:${R.drawable.ic_favorite}")
        assertSame("Action view identity",favorite,rebound)
        assertTrue("Focus after update: ${list.findFocus()?.tag}",rebound.hasFocus())
        assertEquals("Viewport anchor",before,list.findViewHolderForAdapterPosition(0)!!.itemView.top)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("detail-tab:0",list.findFocus()?.tag)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertSame(rebound,list.findFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("episode:ep1",list.findFocus()?.tag)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("episode:ep2",list.findFocus()?.tag)
        render(model.state.value.copy(discovery=Discovery(emptyList(),emptyList()),discoveryLoading=false))
        assertEquals("episode:ep2",list.findFocus()?.tag)
        controller.pause().stop().start().resume().windowFocusChanged(true);layout()
        assertEquals("episode:ep2",list.findFocus()?.tag)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("detail-tab:0",list.findFocus()?.tag)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("episode:ep2",list.findFocus()?.tag)
        controller.pause().stop().destroy()
    }
    @Test fun panelArrowsStayWithinOrderedActionsAndDismissReturnsToCaller(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity=controller.get()
        val caller=android.widget.TextView(activity).apply{isFocusable=true;isFocusableInTouchMode=true}
        activity.setContentView(caller);caller.requestFocus()
        val panel=TvSidePanel(activity,"Действия")
        val first=panel.addChoice("Первое"){}
        val second=panel.addChoice("Второе"){}
        val last=panel.addAction("Закрыть"){panel.dismiss()}
        panel.show();first.requestFocus()
        fun key(code:Int){panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))}
        key(KeyEvent.KEYCODE_DPAD_UP);assertTrue(first.hasFocus())
        key(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(first.hasFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(second.hasFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(last.hasFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(last.hasFocus())
        panel.dismiss();shadowOf(Looper.getMainLooper()).idle()
        assertTrue(caller.hasFocus())
        controller.pause().stop().destroy()
    }
}
