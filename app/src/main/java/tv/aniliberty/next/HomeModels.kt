package tv.aniliberty.next

data class Feature(val id: String, val title: String, val subtitle: String, val image: String?)

data class HomeData(
    val latest: List<Release>,
    val schedule: List<List<Release>>?,
    val franchises: List<Feature>?,
    val genres: List<Feature>?,
    val videos: List<HomeVideo>? = null,
)

internal val HomeData.featuredRelease: Release?
    get() = latest.firstOrNull { !it.blocked } ?: latest.firstOrNull()

data class HomeVideo(
    val id: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
)

data class FranchiseGroup(val id: String, val title: String, val releases: List<Release>)

data class Discovery(val franchises: List<FranchiseGroup>?, val recommendations: List<Release>?)

enum class HomeSection(val title: String) {
    LATEST("Новые эпизоды"),
    SCHEDULE("Расписание релизов"),
    FRANCHISES("Франшизы"),
    GENRES("Жанры"),
    VIDEOS("Видео команды"),
}

data class SectionData(
    val releases: List<Release> = emptyList(),
    val features: List<Feature> = emptyList(),
    val videos: List<HomeVideo> = emptyList(),
    val more: Boolean = false,
)
