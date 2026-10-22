package tv.aniliberty.next

import android.content.Context
import android.view.Gravity

internal class QualityEnhancementDialog(
    context: Context,
    selected: Anime4kMode,
    onSelected: (Anime4kMode) -> Unit,
) : TvSidePanel(context, "Улучшение качества") {
    private var selectedView: android.view.View? = null

    init {
        message(
            "Экспериментально · Anime4K\n\nУлучшает контуры при увеличении видео. На слабом устройстве возможны замедления — выберите лёгкий режим или выключите обработку."
        )
        Anime4kMode.entries.forEach { mode ->
            val description =
                when (mode) {
                    Anime4kMode.OFF -> "Исходное изображение"
                    Anime4kMode.LIGHT -> "Меньше вычислений · улучшение контуров"
                    Anime4kMode.QUALITY -> "Нейросетевое увеличение · выше нагрузка"
                }
            val choice =
                addChoice("${mode.title}\n$description", selected == mode) {
                    onSelected(mode)
                    dismiss()
                }
            if (selected == mode) selectedView = choice
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
