package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private fun episode(id: String, order: Double, available: Boolean = true) =
        Episode(id, order, "", null, 1200,
            if (available) listOf("720p" to "https://video.example/$id.m3u8") else emptyList(), order)

    private fun release(vararg episodes: Episode, blocked: Boolean = false) =
        Release(1, "", "Тайтл", "", null, "", "", "", "", emptyList(), "",
            episodes.toList(), emptyList(), blocked)

    @Test
    fun automaticContinuationSkipsUnavailableEpisodesInServerOrder() {
        val release = release(episode("first", 1.0), episode("gap", 1.5, false), episode("next", 3.0))
        assertEquals("next", playbackEpisode(release, "first") { 900L to 1000L }?.id)
        assertEquals("first", playbackEpisode(release, "first", watchedPercent = 95) { 900L to 1000L }?.id)
        assertEquals("next", playbackEpisode(release, "gap") { 10L to 1000L }?.id)
        assertEquals("first", playbackEpisode(release, "removed") { 0L to 0L }?.id)
    }

    @Test
    fun explicitChoicesNeverSilentlyLaunchAnotherEpisode() {
        val release = release(episode("first", 1.0), episode("gap", 2.0, false), episode("next", 3.0))
        assertEquals("first", playbackEpisode(release, "next", "first") { 1000L to 1000L }?.id)
        assertNull(playbackEpisode(release, "first", "gap") { 0L to 0L })
        assertNull(playbackEpisode(release, "first", "removed") { 0L to 0L })
    }

    @Test
    fun unavailableReleasesNeverStartAndLastAvailableEpisodeCanBeReplayed() {
        assertNull(playbackEpisode(release(episode("first", 1.0), blocked = true), null) { 0L to 0L })
        assertNull(playbackEpisode(release(episode("first", 1.0, false)), null) { 0L to 0L })
        assertNull(playbackEpisode(release(), null) { 0L to 0L })
        val release = release(episode("first", 1.0), episode("last", 2.0, false))
        assertEquals("first", playbackEpisode(release, "first") { 1000L to 1000L }?.id)
    }
}
