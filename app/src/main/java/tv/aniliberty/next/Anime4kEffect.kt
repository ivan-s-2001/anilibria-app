package tv.aniliberty.next

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Anime4kEffect(
    private val mode: Anime4kMode,
    private val screenWidth: Int,
    private val screenHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = mode == Anime4kMode.OFF

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        try {
            // These upstream models are SDR models. Never reinterpret HDR as BT.709.
            check(!useHdr) { "Anime4K supports SDR video only" }
            val sources =
                (mode.assets + mode.primary.assets).distinct().associateWith { asset ->
                    context.assets.open("anime4k/$asset").bufferedReader().use { it.readText() }
                }
            return Anime4kShaderProgram(mode, sources, screenWidth, screenHeight)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class Anime4kShaderProgram(
    private val mode: Anime4kMode,
    private val sources: Map<String, String>,
    private val screenWidth: Int,
    private val screenHeight: Int,
) : BaseGlShaderProgram(true, 1) {
    private data class Target(val texture: Int, val fbo: Int, val size: Anime4kSize)

    private val allocated = mutableListOf<Target>()
    private val outputs = mutableListOf<Target?>()
    private val programs = mutableListOf<GlProgram>()
    private var nodes = emptyList<Anime4kNode>()
    private var outputSize = Anime4kSize(1, 1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            clearResources()
            val input = Anime4kSize(inputWidth, inputHeight)
            outputSize = anime4kOutputSize(inputWidth, inputHeight, screenWidth, screenHeight)
            nodes = planAnime4k(mode, input, outputSize) { requireNotNull(sources[it]) }
            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            val lastUse = IntArray(nodes.size) { it }
            nodes.forEachIndexed { index, node ->
                node.bindings.values.filter { it >= 0 }.forEach { lastUse[it] = index }
            }
            val free = mutableListOf<Target>()
            // Include the Media3 output pool. Reuse intermediates only after their final consumer.
            var bytes = outputSize.width.toLong() * outputSize.height * 8
            nodes.forEachIndexed { index, node ->
                check(node.output.width <= maxTexture[0] && node.output.height <= maxTexture[0]) {
                    "Anime4K intermediate exceeds GPU texture size"
                }
                val program = GlProgram(ANIME4K_VERTEX, node.fragment)
                programs += program
                program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                if (index == nodes.lastIndex) outputs += null
                else {
                    var target = free.firstOrNull { it.size == node.output }
                    if (target != null) free.remove(target)
                    else {
                        bytes += node.output.width.toLong() * node.output.height * 8
                        check(bytes <= 256L * 1024 * 1024) {
                            "Anime4K texture memory budget exceeded"
                        }
                        val texture =
                            GlUtil.createTexture(node.output.width, node.output.height, true)
                        try {
                            target =
                                Target(texture, GlUtil.createFboForTexture(texture), node.output)
                            allocated += target
                        } catch (e: Exception) {
                            GlUtil.deleteTexture(texture)
                            throw e
                        }
                        check(
                            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                                GLES20.GL_FRAMEBUFFER_COMPLETE
                        ) {
                            "GPU cannot render floating-point Anime4K textures"
                        }
                    }
                    outputs += target
                }
                lastUse.forEachIndexed { id, last ->
                    if (last == index && id <= index) outputs[id]?.let { free += it }
                }
            }
            return Size(outputSize.width, outputSize.height)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val framebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
        try {
            nodes.forEachIndexed { index, node ->
                GlUtil.focusFramebufferUsingCurrentContext(
                    outputs[index]?.fbo ?: framebuffer[0],
                    node.output.width,
                    node.output.height,
                )
                val program = programs[index]
                program.use()
                node.bindings.entries.forEachIndexed { unit, (name, id) ->
                    val texture = if (id < 0) inputTexId else requireNotNull(outputs[id]).texture
                    if (program.getUniformLocation("u_$name") >= 0)
                        program.setSamplerTexIdUniform("u_$name", texture, unit)
                    if (id >= 0) {
                        val size = nodes[id].output
                        program.setFloatsUniformIfPresent(
                            "u_${name}_size",
                            floatArrayOf(size.width.toFloat(), size.height.toFloat()),
                        )
                    }
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            }
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(
                framebuffer[0],
                outputSize.width,
                outputSize.height,
            )
        }
    }

    private fun clearResources() {
        var failure: Exception? = null
        fun clean(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        programs.forEach { clean { it.delete() } }
        programs.clear()
        allocated.forEach {
            clean { GlUtil.deleteFbo(it.fbo) }
            clean { GlUtil.deleteTexture(it.texture) }
        }
        allocated.clear()
        outputs.clear()
        nodes = emptyList()
        failure?.let { throw it }
    }

    override fun release() {
        try {
            clearResources()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        } finally {
            super.release()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun isAnime4kProcessingFailure(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
        generateSequence<Throwable>(error) { it.cause }.any { it is VideoFrameProcessingException }
