package tv.aniliberty.next

import android.content.Context
import android.view.Gravity

internal class QualityEnhancementDialog(
    context: Context,
    selected: Anime4kMode,
    secondaryAvailable: Boolean = true,
    onSelected: (Anime4kMode) -> Unit,
) : TvSidePanel(context, "Улучшение качества") {
    private var selectedView: android.view.View? = null

    init {
        message(
            "Экспериментально · Anime4K\n\nСравните A, B и C на одном кадре. Усиленные режимы работают при увеличении от ×2; при меньшем масштабе применяется основной. Возможны артефакты и замедления."
        )
        Anime4kMode.entries.forEach { mode ->
            val description = mode.description
            val choice =
                addChoice("${mode.title}\n$description", selected == mode) {
                    onSelected(mode)
                    dismiss()
                }
            if (mode.secondary && !secondaryAvailable) {
                choice.isEnabled = false
                choice.isFocusable = false
                choice.alpha = .45f
            }
            if (selected == mode && choice.isEnabled) selectedView = choice
        }
        addAction("Назад") { dismiss() }
    }

    override fun show() {
        super.show()
        window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(
                (context.resources.displayMetrics.widthPixels * .58f).toInt(),
                (context.resources.displayMetrics.heightPixels * .88f).toInt(),
            )
        }
        selectedView?.requestFocus()
    }
}
