package tv.aniliberty.next

import android.content.Context

class TvSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tv-settings", Context.MODE_PRIVATE)

    fun enabled(key: String, default: Boolean = true) = prefs.getBoolean(key, default)

    fun toggle(key: String, default: Boolean = true): Boolean =
        (!enabled(key, default)).also { prefs.edit().putBoolean(key, it).apply() }
}

data class EpisodeSkip(val start: Double, val stop: Double)

internal fun skipTarget(range: EpisodeSkip?, positionMs: Long, durationMs: Long): Long? {
    if (
        range == null ||
            !range.start.isFinite() ||
            !range.stop.isFinite() ||
            range.start < 0 ||
            range.stop <= range.start
    )
        return null
    val start = (range.start * 1000).toLong()
    val end = (range.stop * 1000).toLong()
    if (durationMs <= 0 || end > durationMs || positionMs < start || positionMs >= end) return null
    return end
}
