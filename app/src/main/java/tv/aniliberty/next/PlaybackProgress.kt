package tv.aniliberty.next

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

internal const val WATCHED_PERCENT_KEY = "watched-percent"
internal const val DEFAULT_WATCHED_PERCENT = 90

internal fun isEpisodeWatched(position: Long, duration: Long, percent: Int = DEFAULT_WATCHED_PERCENT): Boolean =
    position > 0 && duration > 0 &&
        position.toDouble() / duration >= percent.coerceIn(1, 100) / 100.0
