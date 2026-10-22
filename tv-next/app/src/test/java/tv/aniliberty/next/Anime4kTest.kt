package tv.aniliberty.next

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Anime4kTest {
    @Test
    fun outputFitsScreenAndGpuAndDoesNotProcessNativeResolution() {
        assertEquals(Anime4kSize(3840, 2160), anime4kOutputSize(1920, 1080, 3840, 2160))
        assertEquals(Anime4kSize(1920, 1080), anime4kOutputSize(1920, 1080, 1920, 1080))
        assertEquals(Anime4kSize(3840, 2160), anime4kOutputSize(3840, 2160, 3840, 2160))
        assertEquals(Anime4kSize(1280, 720), anime4kOutputSize(640, 360, 3840, 2160))
        assertEquals(Anime4kSize(1920, 1080), anime4kOutputSize(1920, 1080, 3840, 2160, 2048))
        assertEquals(Anime4kSize(3840, 1600), anime4kOutputSize(1920, 800, 3840, 2160))
    }

    @Test
    fun upstreamShadersHaveValidDependencyGraphsAndSignedIntermediatePasses() {
        val context = RuntimeEnvironment.getApplication()
        listOf(Anime4kMode.LIGHT to 4, Anime4kMode.QUALITY to 5).forEach { (mode, count) ->
            val source =
                context.assets.open("anime4k/${mode.asset}").bufferedReader().use { it.readText() }
            val passes = parseAnime4kShader(source)
            assertEquals(count, passes.size)
            assertTrue(passes.last().upscale)
            passes.forEachIndexed { i, pass ->
                val shader = pass.fragmentShader(i == passes.lastIndex)
                assertTrue(shader.startsWith("#version 300 es"))
                assertFalse(shader.contains("//!"))
                assertEquals(i == passes.lastIndex, shader.contains("outColor = vec4(toLinear"))
            }
        }
    }

    @Test
    fun invalidModeFallsBackToOffAndSelectionPersists() {
        assertEquals(Anime4kMode.OFF, Anime4kMode.fromKey(null))
        assertEquals(Anime4kMode.OFF, Anime4kMode.fromKey("unknown"))
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("player-options", 0)
        preferences.edit().putString("anime4k", Anime4kMode.QUALITY.key).commit()
        assertEquals(
            Anime4kMode.QUALITY,
            Anime4kMode.fromKey(preferences.getString("anime4k", "off")),
        )
    }

    @Test
    fun processingErrorsTriggerFallbackButNetworkErrorsDoNot() {
        fun error(code: Int, cause: Throwable? = null) =
            androidx.media3.common.PlaybackException("test", cause, code)
        assertTrue(isAnime4kProcessingFailure(error(7000)))
        assertTrue(isAnime4kProcessingFailure(error(7001)))
        assertTrue(
            isAnime4kProcessingFailure(
                error(
                    1000,
                    androidx.media3.common.VideoFrameProcessingException(
                        IllegalStateException("GPU")
                    ),
                )
            )
        )
        assertFalse(isAnime4kProcessingFailure(error(2001)))
        assertFalse(isAnime4kProcessingFailure(error(4003)))
    }

    @Test
    fun enhancementIsCenteredModalWithSelectedModeAndReturnsToParent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = TvSidePanel(activity, "Настройки воспроизведения")
        val caller = parent.addChoice("Улучшение качества") {}
        parent.show()
        caller.requestFocus()
        var selected: Anime4kMode? = null
        val dialog = QualityEnhancementDialog(activity, Anime4kMode.LIGHT) { selected = it }
        dialog.show()
        assertTrue(parent.isShowing)
        assertEquals(Gravity.CENTER, dialog.window!!.attributes.gravity)
        val choices = dialog.body.getFocusables(View.FOCUS_FORWARD).filterIsInstance<TextView>()
        assertEquals(3, choices.size)
        assertTrue(choices[1].hasFocus())
        assertTrue(choices[1].text.startsWith("✓"))
        choices[2].performClick()
        assertEquals(Anime4kMode.QUALITY, selected)
        assertFalse(dialog.isShowing)
        assertTrue(parent.isShowing)
        parent.dismiss()
        controller.pause().stop().destroy()
    }
}
