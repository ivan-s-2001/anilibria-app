package tv.aniliberty.next

/** Playback follows the server's ordered episode list, never ordinal + 1. */
fun nextEpisode(episodes: List<Episode>, currentId: String?): Episode? {
    val index = episodes.indexOfFirst { it.id == currentId }
    return if (index >= 0) episodes.getOrNull(index + 1) else null
}

/** Continue the saved episode, or the next server-ordered episode after completion. */
fun resumeEpisode(
    episodes: List<Episode>,
    lastId: String?,
    watchedPercent: Int = DEFAULT_WATCHED_PERCENT,
    available: (Episode) -> Boolean = { true },
    progress: (String) -> Pair<Long, Long>,
): Episode? {
    val index = episodes.indexOfFirst { it.id == lastId }
    val saved = episodes.getOrNull(index) ?: return episodes.firstOrNull(available)
    val (position, duration) = progress(saved.id)
    if (available(saved) && !isEpisodeWatched(position, duration, watchedPercent)) return saved
    return episodes.drop(index + 1).firstOrNull(available)
        ?: saved.takeIf(available)
        ?: episodes.firstOrNull(available)
}

/** Explicit episode choices stay exact; automatic continuation skips unavailable streams. */
internal fun playbackEpisode(
    release: Release,
    lastId: String?,
    requestedId: String? = null,
    watchedPercent: Int = DEFAULT_WATCHED_PERCENT,
    progress: (String) -> Pair<Long, Long>,
): Episode? {
    if (release.blocked) return null
    if (requestedId != null)
        return release.episodes.firstOrNull { it.id == requestedId && it.streams.isNotEmpty() }
    return resumeEpisode(release.episodes, lastId, watchedPercent, { it.streams.isNotEmpty() }, progress)
}
