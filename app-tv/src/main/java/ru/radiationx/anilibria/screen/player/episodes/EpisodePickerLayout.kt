package ru.radiationx.anilibria.screen.player.episodes

/** The playback picker must leave most of the video visible, at any TV density. */
internal fun episodePickerWidth(screenWidth: Int, density: Float, fromPlayer: Boolean): Int {
    require(screenWidth > 0 && density > 0 && density.isFinite())
    return if (fromPlayer) minOf((screenWidth * .44f).toInt(), (420 * density).toInt()).coerceAtLeast(1)
    else screenWidth
}
