package tv.aniliberty.next

internal enum class Anime4kMode(val key: String, val title: String, val description: String) {
    OFF("off", "Выключено", "Исходное изображение"),
    A("a", "A", "Размытые линии и следы сжатия"),
    B("b", "B", "Более мягкое восстановление, ореолы и неровные края"),
    C("c", "C", "Увеличение с подавлением шума"),
    AA("aa", "A+A", "Повторное восстановление · увеличение от ×2"),
    BB("bb", "B+B", "Повторное мягкое восстановление · увеличение от ×2"),
    CA("ca", "C+A", "Подавление шума и восстановление · увеличение от ×2");

    val primary: Anime4kMode
        get() =
            when (this) {
                AA -> A
                BB -> B
                CA -> C
                else -> this
            }

    val secondary: Boolean
        get() = primary != this

    /** Exact file order from the upstream Windows Low-end input.conf (M followed by S). */
    val assets: List<String>
        get() {
            if (this == OFF) return emptyList()
            val clamp = "Anime4K_Clamp_Highlights.glsl"
            val restore = "Anime4K_Restore_CNN_M.glsl"
            val soft = "Anime4K_Restore_CNN_Soft_M.glsl"
            val up = "Anime4K_Upscale_CNN_x2_M.glsl"
            val denoise = "Anime4K_Upscale_Denoise_CNN_x2_M.glsl"
            val down =
                listOf("Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl")
            val last = "Anime4K_Upscale_CNN_x2_S.glsl"
            return when (this) {
                A -> listOf(clamp, restore, up) + down + last
                B -> listOf(clamp, soft, up) + down + last
                C -> listOf(clamp, denoise) + down + last
                AA -> listOf(clamp, restore, up, "Anime4K_Restore_CNN_S.glsl") + down + last
                BB ->
                    listOf(clamp, soft, up) + down + listOf("Anime4K_Restore_CNN_Soft_S.glsl", last)
                CA -> listOf(clamp, denoise) + down + listOf("Anime4K_Restore_CNN_S.glsl", last)
                OFF -> emptyList()
            }
        }

    companion object {
        // Retire the old light/quality experiments instead of silently enabling a new preset.
        fun fromKey(key: String?) = entries.find { it.key == key } ?: OFF
    }
}

internal data class Anime4kSize(val width: Int, val height: Int)

internal fun anime4kOutputSize(
    width: Int,
    height: Int,
    screenWidth: Int,
    screenHeight: Int,
): Anime4kSize {
    require(width > 0 && height > 0 && screenWidth > 0 && screenHeight > 0)
    // Preserve native resolution on smaller screens. Let Media3 handle the final presentation.
    val scale =
        maxOf(
            1.0,
            minOf(
                screenWidth.toDouble() / width,
                screenHeight.toDouble() / height,
                3840.0 / width,
                2160.0 / height,
            ),
        )
    return Anime4kSize((width * scale).toInt(), (height * scale).toInt())
}

internal fun effectiveAnime4kMode(
    mode: Anime4kMode,
    input: Anime4kSize,
    output: Anime4kSize,
): Anime4kMode =
    if (
        mode.secondary &&
            minOf(output.width.toDouble() / input.width, output.height.toDouble() / input.height) <
                2.0
    )
        mode.primary
    else mode

/** The RPN subset used by the bundled upstream WHEN/WIDTH/HEIGHT directives. */
internal fun anime4kExpression(expression: String, sizes: Map<String, Anime4kSize>): Double {
    val stack = mutableListOf<Double>()
    expression.trim().split(Regex("\\s+")).forEach { token ->
        when (token) {
            "+",
            "-",
            "*",
            "/",
            ">",
            "<" -> {
                require(stack.size >= 2) { "Invalid Anime4K expression: $expression" }
                val b = stack.removeAt(stack.lastIndex)
                val a = stack.removeAt(stack.lastIndex)
                stack +=
                    when (token) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> {
                            require(b != 0.0)
                            a / b
                        }
                        ">" -> if (a > b) 1.0 else 0.0
                        else -> if (a < b) 1.0 else 0.0
                    }
            }
            else ->
                stack +=
                    token.toDoubleOrNull()
                        ?: run {
                            val name = token.substringBeforeLast('.')
                            val size =
                                requireNotNull(sizes[name]) { "Unknown Anime4K texture $name" }
                            when (token.substringAfterLast('.')) {
                                "w" -> size.width.toDouble()
                                "h" -> size.height.toDouble()
                                else -> error("Invalid dimension $token")
                            }
                        }
        }
    }
    require(stack.size == 1 && stack[0].isFinite())
    return stack[0]
}

internal data class Anime4kPass(
    val hook: String,
    val bindings: List<String>,
    val save: String?,
    val width: String?,
    val height: String?,
    val condition: String?,
    val body: String,
) {
    fun fragmentShader(): String = buildString {
        append(ANIME4K_FRAGMENT_HEADER)
        bindings.forEach { name ->
            append("uniform sampler2D u_$name;\nuniform vec2 u_${name}_size;\n")
            append("#define ${name}_pos vTexCoord\n#define ${name}_size u_${name}_size\n")
            append("#define ${name}_pt (1.0 / u_${name}_size)\n")
            append("vec4 ${name}_tex(vec2 p) { return texture(u_$name, p); }\n")
            append("#define ${name}_texOff(p) ${name}_tex(vTexCoord + (p) * ${name}_pt)\n")
        }
        // mpv aliases MAIN and HOOKED at the MAIN hook. Clamp_Highlights uses this alias.
        if ("HOOKED" in bindings && "MAIN" !in bindings) {
            append(
                "#define MAIN_tex HOOKED_tex\n#define MAIN_texOff HOOKED_texOff\n#define MAIN_pos HOOKED_pos\n#define MAIN_size HOOKED_size\n#define MAIN_pt HOOKED_pt\n"
            )
        }
        append(body)
        append("\nvoid main() { outColor = hook(); }\n")
    }
}

internal fun parseAnime4kShader(source: String): List<Anime4kPass> =
    source
        .split("//!DESC")
        .drop(1)
        .map { block ->
            val lines = block.lines()
            val directives =
                lines
                    .filter { it.startsWith("//!") }
                    .map { it.removePrefix("//!").substringBefore(' ') }
            require(
                directives.all {
                    it in listOf("HOOK", "BIND", "SAVE", "WIDTH", "HEIGHT", "WHEN", "COMPONENTS")
                }
            )
            fun values(name: String) =
                lines
                    .filter { it.startsWith("//!$name ") }
                    .map { it.substringAfter("//!$name ").trim() }
            val hook = values("HOOK").single()
            require(hook in listOf("MAIN", "PREKERNEL"))
            val bindings = values("BIND")
            require(
                bindings.isNotEmpty() &&
                    bindings.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }
            )
            Anime4kPass(
                hook,
                bindings,
                values("SAVE").singleOrNull(),
                values("WIDTH").singleOrNull(),
                values("HEIGHT").singleOrNull(),
                values("WHEN").singleOrNull(),
                lines.drop(1).filterNot { it.startsWith("//!") }.joinToString("\n"),
            )
        }
        .also { require(it.isNotEmpty()) }

internal data class Anime4kNode(
    val name: String,
    val fragment: String,
    val bindings: Map<String, Int>,
    val output: Anime4kSize,
)

/** SSA texture versions keep repeated CNN stages independent and allow safe lifetime reuse. */
internal fun planAnime4k(
    requested: Anime4kMode,
    input: Anime4kSize,
    output: Anime4kSize,
    load: (String) -> String,
): List<Anime4kNode> {
    if (requested == Anime4kMode.OFF) return emptyList()
    val mode = effectiveAnime4kMode(requested, input, output)
    val nodes =
        mutableListOf(
            Anime4kNode("Encode", anime4kTransferShader(true), mapOf("Input" to -1), input)
        )
    val textures = mutableMapOf("MAIN" to 0, "NATIVE" to 0)
    val deferred = mutableListOf<Pair<String, Anime4kPass>>()
    fun append(asset: String, pass: Anime4kPass) {
        val main = requireNotNull(textures["MAIN"])
        textures["HOOKED"] = main
        val sizes = textures.mapValues { nodes[it.value].output } + ("OUTPUT" to output)
        if (pass.condition != null && anime4kExpression(pass.condition, sizes) == 0.0) return
        val hookedSize = nodes[main].output
        val size =
            Anime4kSize(
                pass.width?.let { anime4kExpression(it, sizes).toInt() } ?: hookedSize.width,
                pass.height?.let { anime4kExpression(it, sizes).toInt() } ?: hookedSize.height,
            )
        require(size.width > 0 && size.height > 0)
        val bindings =
            pass.bindings.associateWith {
                requireNotNull(textures[it]) { "Unbound texture $it in $asset" }
            }
        nodes += Anime4kNode(asset, pass.fragmentShader(), bindings, size)
        textures[pass.save ?: "MAIN"] = nodes.lastIndex
    }
    mode.assets.forEach { asset ->
        parseAnime4kShader(load(asset)).forEach { pass ->
            if (pass.hook == "PREKERNEL") deferred += asset to pass else append(asset, pass)
        }
    }
    deferred.forEach { (asset, pass) -> append(asset, pass) }
    nodes +=
        Anime4kNode(
            "Decode",
            anime4kTransferShader(false),
            mapOf("Input" to requireNotNull(textures["MAIN"])),
            output,
        )
    return nodes
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

private const val ANIME4K_FRAGMENT_HEADER =
    "#version 300 es\nprecision highp float;\nin vec2 vTexCoord;\nout vec4 outColor;\n"

/** Convert only at pipeline boundaries; interpolation and every CNN pass stay gamma-coded. */
internal fun anime4kTransferShader(encode: Boolean): String =
    ANIME4K_FRAGMENT_HEADER +
        """
uniform sampler2D u_Input;
void main() {
    vec3 x = clamp(texture(u_Input, vTexCoord).rgb, 0.0, 1.0);
    vec3 result = ${if (encode) "mix(1.099 * pow(x, vec3(0.45)) - 0.099, 4.5 * x, lessThan(x, vec3(0.018)))" else "mix(pow((x + 0.099) / 1.099, vec3(1.0 / 0.45)), x / 4.5, lessThan(x, vec3(0.0812)))"};
    outColor = vec4(result, 1.0);
}
"""
