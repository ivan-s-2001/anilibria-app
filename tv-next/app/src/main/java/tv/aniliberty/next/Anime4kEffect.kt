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
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        mode == Anime4kMode.OFF ||
            anime4kOutputSize(inputWidth, inputHeight, screenWidth, screenHeight) ==
                Anime4kSize(inputWidth, inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        try {
            // These upstream models are SDR models. Never reinterpret HDR as BT.709.
            check(!useHdr) { "Anime4K supports SDR video only" }
            val source =
                context.assets.open("anime4k/${requireNotNull(mode.asset)}").bufferedReader().use {
                    it.readText()
                }
            return Anime4kShaderProgram(parseAnime4kShader(source), screenWidth, screenHeight)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class Anime4kShaderProgram(
    private val passes: List<Anime4kPass>,
    private val screenWidth: Int,
    private val screenHeight: Int,
) : BaseGlShaderProgram(false, 1) {
    private data class Target(val texture: Int, val fbo: Int, val width: Int, val height: Int)

    private val targets = mutableListOf<Target>()
    private val programs = mutableListOf<GlProgram>()
    private var inputSize = Anime4kSize(1, 1)
    private var outputSize = Anime4kSize(1, 1)
    private var copy: GlProgram? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            clearResources()
            inputSize = Anime4kSize(inputWidth, inputHeight)
            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            outputSize =
                anime4kOutputSize(inputWidth, inputHeight, screenWidth, screenHeight, maxTexture[0])
            if (outputSize == inputSize) {
                copy =
                    program(
                        """#version 300 es
precision highp float;
in vec2 vTexCoord;
uniform sampler2D uInput;
out vec4 outColor;
void main() { outColor = texture(uInput, vTexCoord); }
"""
                    )
            } else {
                passes.forEachIndexed { index, pass ->
                    programs += program(pass.fragmentShader(index == passes.lastIndex))
                    if (index != passes.lastIndex) {
                        val size = if (pass.upscale) outputSize else inputSize
                        // Signed gradients and CNN feature maps must not be clipped to RGBA8.
                        val texture = GlUtil.createTexture(size.width, size.height, true)
                        try {
                            targets +=
                                Target(
                                    texture,
                                    GlUtil.createFboForTexture(texture),
                                    size.width,
                                    size.height,
                                )
                        } catch (e: Exception) {
                            GlUtil.deleteTexture(texture)
                            throw e
                        }
                        check(
                            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                                GLES20.GL_FRAMEBUFFER_COMPLETE
                        ) {
                            "GPU cannot render Anime4K floating-point textures"
                        }
                    }
                }
            }
            return Size(outputSize.width, outputSize.height)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun program(fragment: String): GlProgram {
        val program = GlProgram(ANIME4K_VERTEX, fragment)
        program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        return program
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            copy?.let {
                it.use()
                it.setSamplerTexIdUniform("uInput", inputTexId, 0)
                it.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                return
            }
            val framebuffer = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
            val original = Target(inputTexId, 0, inputSize.width, inputSize.height)
            val textures = mutableMapOf("MAIN" to original, "HOOKED" to original)
            try {
                passes.forEachIndexed { index, pass ->
                    val target = targets.getOrNull(index)
                    GlUtil.focusFramebufferUsingCurrentContext(
                        target?.fbo ?: framebuffer[0],
                        target?.width ?: outputSize.width,
                        target?.height ?: outputSize.height,
                    )
                    val program = programs[index]
                    program.use()
                    pass.bindings.forEachIndexed { unit, name ->
                        val texture = requireNotNull(textures[name])
                        // Unused uniforms may have been eliminated by the GLSL compiler.
                        if (program.getUniformLocation("u_$name") >= 0)
                            program.setSamplerTexIdUniform("u_$name", texture.texture, unit)
                        program.setFloatsUniformIfPresent(
                            "u_${name}_size",
                            floatArrayOf(texture.width.toFloat(), texture.height.toFloat()),
                        )
                    }
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                    if (target != null) pass.save?.let { textures[it] = target }
                }
            } finally {
                GlUtil.focusFramebufferUsingCurrentContext(
                    framebuffer[0],
                    outputSize.width,
                    outputSize.height,
                )
            }
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun clearResources() {
        // Attempt every deletion even after a driver error; propagate the first failure afterwards.
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
        copy?.let { clean { it.delete() } }
        copy = null
        targets.forEach {
            clean { GlUtil.deleteFbo(it.fbo) }
            clean { GlUtil.deleteTexture(it.texture) }
        }
        targets.clear()
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
