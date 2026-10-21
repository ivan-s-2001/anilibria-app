package ru.radiationx.anilibria.common

import androidx.core.text.parseAsHtml
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.schedule.ScheduleDay
import ru.radiationx.shared.ktx.capitalizeDefault
import java.text.NumberFormat
import java.util.Calendar
import javax.inject.Inject

class DetailDataConverter @Inject constructor() {

    fun toDetail(
        releaseItem: Release,
        isFull: Boolean,
        accesses: List<EpisodeAccess>,
    ): LibriaDetails = releaseItem.run {
        val lastAccess = accesses
            .filter { access -> episodes.any { it.id == access.id } }
            .filter { it.lastValidAccess != null || it.seek > 0 }
            .maxByOrNull { it.lastAccessRaw }
        LibriaDetails(
            id = id,
            titleRu = title.orEmpty(),
            titleEn = titleEng.orEmpty(),
            extra = listOf(
                "Жанры: ${genres.joinToString(" • ")}".takeIf { genres.isNotEmpty() },
                "${year.orEmpty()} • ${season.orEmpty()}".trim(' ', '•'),
                types.firstOrNull()?.trim()?.let { "Тип: $it" },
                "Серии: ${series?.trim() ?: "Не доступно"}"
            ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n"),
            description = description.orEmpty().parseAsHtml().toString().trim()
                .trim('"')/*.replace('\n', ' ')*/,
            announce = getAnnounce(isFull),
            image = poster.orEmpty(),
            favoriteCount = NumberFormat.getNumberInstance().format(favoriteInfo.rating),
            hasFullHd = episodes.any { PlayerQuality.FULLHD in it.qualityInfo },
            isFavorite = favoriteInfo.isAdded,
            hasEpisodes = episodes.isNotEmpty(),
            hasViewed = lastAccess != null,
            continueLabel = lastAccess?.let {
                "Продолжить · серия ${it.id.id}"
            } ?: "Продолжить",
            hasWebPlayer = moonwalkLink != null
        )
    }

    private fun Release.getAnnounce(isFull: Boolean): String {
        if (!isFull) return ""
        val announceText = if (statusCode == Release.STATUS_CODE_COMPLETE) {
            "Релиз завершен"
        } else {
            val originalAnnounce = announce?.trim()?.trim('.')?.capitalizeDefault()
            val scheduleAnnounce = days.firstOrNull()?.toAnnounce2().orEmpty()
            originalAnnounce ?: scheduleAnnounce
        }
        val episodesWarning = if (episodes.isEmpty()) {
            "Нет доступных для просмотра серий"
        } else {
            null
        }
        return listOfNotNull(announceText, episodesWarning).joinToString(" • ")
    }

    private fun String.toAnnounce2(): String {
        val calendarDay = ScheduleDay.toCalendarDay(this)
        val prefix = calendarDay.dayIterationPrefix2()
        return "Серии выходят $prefix"
    }

    private fun Int.dayIterationPrefix(): String = when (this) {
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.THURSDAY,
            -> "каждый"

        Calendar.WEDNESDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
            -> "каждую"

        Calendar.SUNDAY -> "каждое"
        else -> throw Exception("Not found day by $this")
    }

    private fun Int.dayIterationPrefix2(): String = when (this) {
        Calendar.MONDAY -> "в понедельник"
        Calendar.TUESDAY -> "во вторник"
        Calendar.WEDNESDAY -> "в среду"
        Calendar.THURSDAY -> "в четверг"
        Calendar.FRIDAY -> "в пятницу"
        Calendar.SATURDAY -> "в субботу"
        Calendar.SUNDAY -> "в воскресенье"
        else -> throw Exception("Not found day by $this")
    }
}
