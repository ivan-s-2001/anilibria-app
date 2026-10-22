package tv.aniliberty.next

internal enum class Anime4kMode(val key: String, val title: String, val asset: String?) {
    OFF("off", "Выключено", null),
    LIGHT("light", "Лёгкий", "Anime4K_Upscale_DoG_x2.glsl"),
    QUALITY("quality", "Качественный", "Anime4K_Upscale_CNN_x2_S.glsl");

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: OFF
    }
}

internal data class Anime4kSize(val width: Int, val height: Int)

/** Never process native-size video or allocate beyond the screen, 4K, or the GPU limit. */
internal fun anime4kOutputSize(
    width: Int,
    height: Int,
    screenWidth: Int,
    screenHeight: Int,
    maxTextureSize: Int = 4096,
): Anime4kSize {
    require(width > 0 && height > 0)
    val scale =
        minOf(
            2.0,
            screenWidth.toDouble() / width,
            screenHeight.toDouble() / height,
            3840.0 / width,
            2160.0 / height,
            maxTextureSize.toDouble() / maxOf(width, height),
        )
    return if (scale <= 1.2) Anime4kSize(width, height)
    else Anime4kSize((width * scale).toInt(), (height * scale).toInt())
}

internal data class Anime4kPass(
    val bindings: List<String>,
    val save: String?,
    val upscale: Boolean,
    val body: String,
) {
    fun fragmentShader(final: Boolean): String = buildString {
        append("#version 300 es\nprecision highp float;\nin vec2 vTexCoord;\nout vec4 outColor;\n")
        append(ANIME4K_TRANSFER)
        bindings.forEach { name ->
            append("uniform sampler2D u_$name;\nuniform vec2 u_${name}_size;\n")
            append("#define ${name}_pos vTexCoord\n")
            append("#define ${name}_size u_${name}_size\n")
            append("#define ${name}_pt (1.0 / u_${name}_size)\n")
            val sample = "texture(u_$name, p)"
            // Media3 supplies linear BT.709; upstream Anime4K operates on gamma-coded RGB.
            val color =
                if (name in listOf("MAIN", "HOOKED")) "vec4(toGamma($sample.rgb), 1.0)" else sample
            append("vec4 ${name}_tex(vec2 p) { return $color; }\n")
            append("#define ${name}_texOff(p) ${name}_tex(vTexCoord + (p) * ${name}_pt)\n")
        }
        append(body)
        append("\nvoid main() {\nvec4 result = hook();\n")
        append(
            if (final) "outColor = vec4(toLinear(result.rgb), 1.0);\n" else "outColor = result;\n"
        )
        append("}\n")
    }
}

/** Adapter for the two bundled upstream shaders, not an arbitrary mpv shader interpreter. */
internal fun parseAnime4kShader(source: String): List<Anime4kPass> {
    val passes =
        source.split("//!DESC").drop(1).map { block ->
            val lines = block.lines()
            fun directive(name: String) =
                lines
                    .filter { it.startsWith("//!$name ") }
                    .map { it.substringAfter("//!$name ").trim() }
            require(directive("HOOK") == listOf("MAIN"))
            val bindings = directive("BIND")
            require(
                bindings.isNotEmpty() &&
                    bindings.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }
            )
            val widths = directive("WIDTH")
            val heights = directive("HEIGHT")
            require(widths.size == heights.size && widths.size <= 1)
            val upscale = widths.singleOrNull()?.endsWith(" 2 *") == true
            require(upscale == (heights.singleOrNull()?.endsWith(" 2 *") == true))
            Anime4kPass(
                bindings,
                directive("SAVE").singleOrNull(),
                upscale,
                lines.drop(1).filterNot { it.startsWith("//!") }.joinToString("\n"),
            )
        }
    require(passes.isNotEmpty())
    val available = mutableSetOf("MAIN", "HOOKED")
    passes.forEachIndexed { index, pass ->
        require(available.containsAll(pass.bindings))
        require(index == passes.lastIndex || pass.save != null)
        pass.save?.let { available += it }
    }
    return passes
}

internal const val ANIME4K_VERTEX =
    """#version 300 es
in vec4 aPosition;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aPosition.xy * 0.5 + 0.5;
}
"""

private const val ANIME4K_TRANSFER =
    """
vec3 toGamma(vec3 x) {
    x = clamp(x, 0.0, 1.0);
    return mix(1.099 * pow(x, vec3(0.45)) - 0.099, 4.5 * x, lessThan(x, vec3(0.018)));
}
vec3 toLinear(vec3 x) {
    x = clamp(x, 0.0, 1.0);
    return mix(pow((x + 0.099) / 1.099, vec3(1.0 / 0.45)), x / 4.5, lessThan(x, vec3(0.081)));
}
"""
