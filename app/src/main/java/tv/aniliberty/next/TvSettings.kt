package tv.aniliberty.next

import android.content.Context

class TvSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("tv-settings", Context.MODE_PRIVATE)

    var watchedPercent: Int
        get() = watchedPercent(playbackPreferences(context))
        set(value) {
            playbackPreferences(context).edit()
                .putInt(WATCHED_PERCENT_KEY, value.coerceIn(1, 100)).apply()
        }

    fun enabled(key: String, default: Boolean = true) = prefs.getBoolean(key, default)

    fun toggle(key: String, default: Boolean = true): Boolean =
        (!enabled(key, default)).also { prefs.edit().putBoolean(key, it).apply() }
}

internal fun showWatchedPercent(context: Context, changed: () -> Unit) {
    val settings = TvSettings(context)
    val panel = TvSidePanel(context, "Считать серию просмотренной после")
    var selected: android.view.View? = null
    (50..100 step 5).forEach { percent ->
        val button = panel.addChoice("$percent%", selected = percent == settings.watchedPercent) {
            settings.watchedPercent = percent
            panel.dismiss()
            changed()
        }
        if (percent == settings.watchedPercent) selected = button
    }
    panel.show()
    selected?.requestFocus()
}
