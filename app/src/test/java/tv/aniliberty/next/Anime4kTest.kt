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
    private fun plan(mode: Anime4kMode, scale: Int = 2): List<Anime4kNode> {
        val context = RuntimeEnvironment.getApplication()
        return planAnime4k(mode, Anime4kSize(32, 24), Anime4kSize(32 * scale, 24 * scale)) {
            context.assets.open("anime4k/$it").bufferedReader().use { it.readText() }
        }
    }

    @Test
    fun presetsMatchEveryFileInOfficialLowEndConfiguration() {
        val reference =
            javaClass.getResourceAsStream("/anime4k/input.conf")!!.bufferedReader().use {
                it.readText()
            }
        val lines = reference.lines().filter { it.startsWith("CTRL+") && !it.startsWith("CTRL+0") }
        val modes =
            listOf(
                Anime4kMode.A,
                Anime4kMode.B,
                Anime4kMode.C,
                Anime4kMode.AA,
                Anime4kMode.BB,
                Anime4kMode.CA,
            )
        assertEquals(6, lines.size)
        lines.zip(modes).forEach { (line, mode) ->
            val files =
                line
                    .substringAfter("set \"󠀀".replace("󠀀", ""))
                    .substringBefore('"')
                    .split(';')
                    .map { it.substringAfterLast('/') }
            assertEquals(mode.title, files, mode.assets)
        }
    }

    @Test
    fun nativeResolutionStillRestoresAndSecondaryPresetsFallBackBelowTwoTimes() {
        for (mode in listOf(Anime4kMode.A, Anime4kMode.B)) {
            val nodes = plan(mode, 1)
            assertTrue(nodes.any { it.name.contains("Restore_CNN") })
            assertFalse(nodes.any { it.name.startsWith("Anime4K_Upscale_CNN") })
        }
        assertEquals(
            Anime4kMode.A,
            effectiveAnime4kMode(Anime4kMode.AA, Anime4kSize(32, 24), Anime4kSize(48, 36)),
        )
        assertEquals(
            Anime4kMode.AA,
            effectiveAnime4kMode(Anime4kMode.AA, Anime4kSize(32, 24), Anime4kSize(64, 48)),
        )
        assertEquals(
            plan(Anime4kMode.A, 1).map { it.name },
            plan(Anime4kMode.AA, 1).map { it.name },
        )
        assertEquals(Anime4kMode.OFF, Anime4kMode.fromKey("light"))
        assertEquals(Anime4kMode.OFF, Anime4kMode.fromKey("quality"))
    }

    @Test
    fun dimensionsAndConditionsUseOriginalRpnWithoutTruncatingCnnScale() {
        val context = RuntimeEnvironment.getApplication()
        val nodes =
            planAnime4k(Anime4kMode.A, Anime4kSize(32, 24), Anime4kSize(96, 72)) {
                context.assets.open("anime4k/$it").bufferedReader().use { it.readText() }
            }
        assertEquals(
            Anime4kSize(64, 48),
            nodes.last { it.name == "Anime4K_Upscale_CNN_x2_M.glsl" }.output,
        )
        assertEquals(
            Anime4kSize(48, 36),
            nodes.single { it.name == "Anime4K_AutoDownscalePre_x4.glsl" }.output,
        )
        assertEquals(
            Anime4kSize(96, 72),
            nodes.last { it.name == "Anime4K_Upscale_CNN_x2_S.glsl" }.output,
        )
        assertFalse(nodes.any { it.name == "Anime4K_AutoDownscalePre_x2.glsl" })
        val sizes = mapOf("MAIN" to Anime4kSize(1920, 1080), "OUTPUT" to Anime4kSize(3840, 2160))
        assertEquals(
            1.0,
            anime4kExpression("OUTPUT.w MAIN.w / 1.2 > OUTPUT.h MAIN.h / 1.2 > *", sizes),
            0.0,
        )
    }

    @Test
    fun clampRunsAfterRestorationAndAllTextureVersionsReferToEarlierPasses() {
        Anime4kMode.entries
            .filter { it != Anime4kMode.OFF }
            .forEach { mode ->
                val nodes = plan(mode, 4)
                assertEquals("Encode", nodes.first().name)
                assertEquals("Decode", nodes.last().name)
                assertEquals("Anime4K_Clamp_Highlights.glsl", nodes[nodes.lastIndex - 1].name)
                val statistics = nodes[nodes.lastIndex - 1].bindings.getValue("STATSMAX")
                assertTrue(
                    statistics <
                        (nodes.indexOfFirst { it.name.contains("Restore_CNN") }.takeIf { it >= 0 }
                            ?: nodes.size)
                )
                nodes.forEachIndexed { index, node ->
                    assertTrue(node.bindings.values.all { it < index })
                }
                assertEquals(1, nodes.count { it.name == "Encode" })
                assertEquals(1, nodes.count { it.name == "Decode" })
                assertTrue(
                    nodes
                        .filter { it.name !in listOf("Encode", "Decode") }
                        .none { it.fragment.contains("toGamma") }
                )
            }
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
    fun secondaryChoicesCannotBeSelectedBelowTwoTimes() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dialog = QualityEnhancementDialog(controller.get(), Anime4kMode.A, false) {}
        dialog.show()
        val choices =
            (0 until dialog.body.childCount)
                .map { dialog.body.getChildAt(it) }
                .filterIsInstance<TextView>()
                .filter { it.isClickable }
        assertEquals(7, choices.size)
        assertTrue(choices.take(4).all { it.isEnabled && it.isFocusable })
        assertTrue(choices.drop(4).all { !it.isEnabled && !it.isFocusable })
        dialog.dismiss()
        controller.pause().stop().destroy()
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
        val dialog = QualityEnhancementDialog(activity, Anime4kMode.A) { selected = it }
        dialog.show()
        assertTrue(parent.isShowing)
        assertEquals(Gravity.CENTER, dialog.window!!.attributes.gravity)
        val choices = dialog.body.getFocusables(View.FOCUS_FORWARD).filterIsInstance<TextView>()
        assertEquals(7, choices.size)
        assertTrue(choices[1].hasFocus())
        assertTrue(choices[1].text.startsWith("✓"))
        choices[2].performClick()
        assertEquals(Anime4kMode.B, selected)
        assertFalse(dialog.isShowing)
        assertTrue(parent.isShowing)
        parent.dismiss()
        controller.pause().stop().destroy()
    }
}
