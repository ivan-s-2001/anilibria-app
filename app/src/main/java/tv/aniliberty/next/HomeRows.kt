package tv.aniliberty.next

/** Home composition stays separate from the Activity's account and navigation lifecycle. */
internal fun homeRows(
    state: UiState,
    continuing: List<ContinueEpisode>,
    refresh: () -> Unit,
    play: (ContinueEpisode) -> Unit,
    continueActions: (ContinueEpisode) -> Unit,
    shelf: (HomeSection, List<Row>, Boolean, String) -> Row,
    features: (List<Feature>, HomeSection) -> List<Row>,
    videos: (List<HomeVideo>) -> List<Row>,
): List<Row> {
    val home = state.home ?: return emptyList()
    return buildList {
        if (continuing.isNotEmpty()) {
            add(Row("continue-heading", Kind.HEADING, "Продолжить просмотр", selected = 1))
            add(
                Row(
                    "shelf:continue",
                    Kind.SHELF,
                    selected = 5,
                    cards = continuing.map { item ->
                        val status = when {
                            item.nextUp -> "Следующая серия"
                            item.duration > 0 -> "Осталось ${(item.remaining + 59999) / 60000} мин"
                            else -> "Продолжить"
                        }
                        Row(
                            "continue:${item.releaseId}:${item.episodeId}",
                            Kind.CONTINUE,
                            item.releaseTitle,
                            "Серия ${number(item.ordinal)} · $status",
                            image = item.preview,
                            watching = item,
                            click = { play(item) },
                            longClick = { continueActions(item) },
                        )
                    },
                )
            )
        }
        add(Row("latest-heading", Kind.HEADING, "Новые эпизоды", selected = 1, click = refresh))
        add(shelf(HomeSection.LATEST, releaseCards(home.latest, "latest"), false, HomeSection.LATEST.name))
        add(Row("schedule-days", Kind.DAYS, "Расписание релизов", selected = state.homeDay))
        val schedule = home.schedule?.getOrNull(state.homeDay)
        if (schedule == null)
            add(Row("schedule-retry", Kind.ACTION, "Не удалось загрузить расписание · Повторить", click = refresh))
        else
            add(shelf(HomeSection.SCHEDULE, releaseCards(schedule, "schedule:${state.homeDay}"), false, "schedule:${state.homeDay}"))
        add(Row("franchises-heading", Kind.HEADING, "Франшизы", selected = 1))
        add(shelf(HomeSection.FRANCHISES, features(home.franchises.orEmpty(), HomeSection.FRANCHISES), true, HomeSection.FRANCHISES.name))
        add(Row("genres-heading", Kind.HEADING, "Жанры", selected = 1))
        add(shelf(HomeSection.GENRES, features(home.genres.orEmpty(), HomeSection.GENRES), true, HomeSection.GENRES.name))
        add(Row("videos-heading", Kind.HEADING, "Видео от AniLiberty", selected = 1))
        if (home.videos == null)
            add(Row("videos-retry", Kind.ACTION, "Не удалось загрузить видео · Повторить", click = refresh))
        else
            add(shelf(HomeSection.VIDEOS, videos(home.videos), true, HomeSection.VIDEOS.name))
    }
}
