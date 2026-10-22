package tv.aniliberty.next

enum class CollectionType(val title: String) {
    PLANNED("В планах"),
    WATCHING("Смотрю"),
    WATCHED("Просмотрено"),
    POSTPONED("Отложено"),
    ABANDONED("Брошено"),
}

data class AccountProfile(
    val id: Int,
    val name: String,
    val login: String = "",
    val email: String = "",
    val avatar: String? = null,
    val createdAt: String = "",
    val banned: Boolean = false,
    val videoAds: Boolean? = null,
    val videoAdsAlways: Boolean? = null,
    val contentAds: Boolean? = null,
)

data class AccountLists(val favorites: Set<Int>, val collections: Map<Int, CollectionType>)

data class AccountTimecode(val episodeId: String, val seconds: Double, val watched: Boolean)

data class AccountHistoryItem(val releaseId: Int, val episodeId: String, val updatedAt: Long)

data class DeviceCode(val code: String, val remainingSeconds: Int)
