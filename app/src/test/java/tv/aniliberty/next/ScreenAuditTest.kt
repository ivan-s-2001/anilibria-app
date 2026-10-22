package tv.aniliberty.next

import android.content.Context
import android.os.Looper
import android.view.View
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
class ScreenAuditTest {
    @Test fun everyContentScreenAndDetailTabSurvivesLayoutAndReturnToHome(){
        RuntimeEnvironment.getApplication().getSharedPreferences("viewing",Context.MODE_PRIVATE).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get()
        val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity)as TvModel
        val list=MainActivity::class.java.getDeclaredField("list").apply{isAccessible=true}.get(activity)as RecyclerView
        val release=parseRelease(JSONObject("""{"id":1,"name":{"main":"Очень длинное название для проверки компоновки страницы тайтла"},"episodes":[{"id":"episode-1","ordinal":1,"name":"Начало","hls_720":"https://example.test/1.m3u8","duration":1440}]}"""))
        val states=listOf(
            UiState(Screen.Catalog(),releases=listOf(release)),
            UiState(Screen.Profile),
            UiState(Screen.Settings),
            UiState(Screen.AccountSettings("identity")),
            UiState(Screen.AccountSettings("sync")),
            UiState(Screen.Favorites()),
            UiState(Screen.Collections()),
            UiState(Screen.AccountHistory),
            UiState(Screen.AccountSessions),
            UiState(Screen.Library(emptyList())),
            UiState(Screen.Library(listOf(1)),releases=listOf(release)),
            UiState(Screen.Franchise("one","Франшиза"),releases=listOf(release)),
            UiState(Screen.Section(HomeSection.GENRES),section=SectionData()),
            UiState(Screen.Section(HomeSection.LATEST),section=SectionData()),
        )+(0..3).map{UiState(Screen.Detail(1,it),detail=release)}+
            UiState(Screen.Home,home=HomeData(null,emptyList(),listOf(emptyList(),emptyList(),emptyList()),emptyList(),emptyList(),emptyList()))
        states.forEach{state->
            model.state.value=state;shadowOf(Looper.getMainLooper()).idle()
            activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY))
            activity.window.decorView.layout(0,0,1920,1080)
            val grid=list.layoutManager as androidx.recyclerview.widget.GridLayoutManager
            assertEquals(grid.spanCount,grid.spanSizeLookup.getSpanSize(RecyclerView.NO_POSITION))
            val rows=(list.adapter as PageAdapter).rows
            assertTrue(rows.isNotEmpty())
            if(state.screen==Screen.Profile){
                assertTrue(rows.any{it.key=="profile-settings"})
                assertFalse(rows.any{it.title=="Обновить список"})
                assertFalse(rows.any{it.key=="profile-favorites" || it.key.startsWith("profile-collection:")})
            }
            if(state.screen==Screen.Settings){
                assertTrue(rows.any{it.key=="setting:skip-opening"})
                assertFalse(rows.any{it.key.contains("fullscreen")})
            }
            if(isAccountList(state.screen))assertEquals(20,grid.spanCount)
            if(state.screen is Screen.Library || state.screen is Screen.Franchise)assertEquals(20,grid.spanCount)
        }
        controller.pause().stop().destroy()
    }
}
