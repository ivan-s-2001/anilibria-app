package tv.aniliberty.next

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SupportAdsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        context.getSharedPreferences("tv-settings", Context.MODE_PRIVATE).edit().clear().commit()
        context
            .getSharedPreferences("account-session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun supportScreenHasThreeRealSwitchesAndContinuesAfterChoice() {
        context.getSharedPreferences("viewing", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        var continued = 0
        val callback: () -> Unit = { continued++ }
        MainActivity::class
            .java
            .getDeclaredMethod("offerSupportAds", kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
            .invoke(activity, callback)
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        fun views(view: android.view.View): List<android.view.View> =
            listOf(view) +
                if (view is android.view.ViewGroup)
                    (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
                else emptyList()
        val nodes = views(dialog.window!!.decorView)
        val switches = nodes.filterIsInstance<android.widget.Switch>()
        assertEquals(3, switches.size)
        assertTrue(switches.none { it.isChecked })
        switches.first().performClick()
        assertTrue(SupportAds(context).enabled)
        assertEquals(0, continued)
        nodes
            .filterIsInstance<android.widget.TextView>()
            .single { it.text.toString() == "Продолжить" }
            .performClick()
        assertEquals(1, continued)
        assertTrue(SupportAds(context).decided)
        controller.pause().stop().destroy()
    }

    @Test
    fun disabledByDefaultDoesNotContactAdService() = runBlocking {
        val ads =
            SupportAds(
                context,
                AccountTransport { _, _, _, _ -> error("No request before opt-in") },
            )
        assertFalse(ads.enabled)
        assertFalse(ads.decided)
        assertNull(ads.campaign())
        ads.completeChoice()
        assertTrue(SupportAds(context).decided)
    }

    @Test
    fun tvSettingsWinOverAccountAndPersistAcrossLoginLogout() {
        val settings = TvSettings(context)
        settings.toggle("support-video-ads", false)
        val session = AccountSession(context)
        session.saveProfile(
            AccountProfile(42, "User", videoAds = false, videoAdsAlways = true, contentAds = true)
        )
        context
            .getSharedPreferences("account-session", Context.MODE_PRIVATE)
            .edit()
            .putInt("user", 42)
            .commit()
        val ads = SupportAds(context)
        assertTrue(ads.enabled)
        assertFalse(ads.everyEpisode)
        assertFalse(ads.content)
        ads.shown(1000)
        assertFalse(ads.shouldRequest(2000))
        assertTrue(ads.shouldRequest(1801000))
        settings.toggle("support-video-ads-always", false)
        assertTrue(ads.shouldRequest(2000))
        session.clear()
        assertTrue(SupportAds(context).enabled)
        assertTrue(SupportAds(context).everyEpisode)
    }

    @Test
    fun vastListKeepsAttributionAndRejectsNonHttpsUrls() {
        val items =
            parseVastCampaigns(
                """[{"id":"a","url":"https://ads.example/vast","ad_erid":"erid","ad_company_name":"Company"},{"url":"http://ads.example/vast"},{"url":"javascript:alert(1)"}]"""
            )
        assertEquals(
            VastCampaign("a", "https://ads.example/vast", "Company", "erid"),
            items.single(),
        )
    }

    @Test
    fun syncWritesThreeTvValuesAndRetryDoesNotToggleThemBack() = runBlocking {
        val calls = mutableListOf<String>()
        var profile =
            AccountProfile(42, "User", videoAds = false, videoAdsAlways = true, contentAds = true)
        val api =
            AccountApi(
                AccountTransport { path, _, _, token ->
                    assertEquals("session", token)
                    calls += path
                    profile =
                        when {
                            path.endsWith("toggleIsWithVideoAdsAlways") ->
                                profile.copy(videoAdsAlways = !profile.videoAdsAlways!!)
                            path.endsWith("toggleIsWithVideoAds") ->
                                profile.copy(videoAds = !profile.videoAds!!)
                            else -> profile.copy(contentAds = !profile.contentAds!!)
                        }
                    """{"id":42,"nickname":"User","is_with_video_ads":${profile.videoAds},"is_with_video_ads_always":${profile.videoAdsAlways},"is_with_content_ads":${profile.contentAds}}"""
                }
            )
        val desired = mapOf("video" to true, "always" to false, "content" to false)
        profile = syncSupportAds(api, "session", profile, desired)
        assertEquals(3, calls.size)
        profile = syncSupportAds(api, "session", profile, desired)
        assertEquals(3, calls.size)
        assertEquals(true, profile.videoAds)
        assertEquals(false, profile.videoAdsAlways)
        assertEquals(false, profile.contentAds)
    }
}
