package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import ru.radiationx.data.SimpleClient
import ru.radiationx.data.datasource.remote.IClient
import javax.inject.Inject

/** Public v1 presentation data; playback IDs and account state stay separate. */
class ReleaseEpisodeMetadataApi @Inject constructor(
    @SimpleClient private val client: IClient,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter(ReleaseEpisodeMetadataResponse::class.java)

    suspend fun get(releaseId: Int): List<ReleaseEpisodeMetadata> {
        require(releaseId > 0)
        val body = client.get("https://aniliberty.top/api/v1/anime/releases/$releaseId", emptyMap())
        return requireNotNull(adapter.fromJson(body)).episodes.filter {
            it.ordinal.isFinite() && it.ordinal > 0
        }
    }
}

@JsonClass(generateAdapter = true)
data class ReleaseEpisodeMetadataResponse(
    @Json(name = "episodes") val episodes: List<ReleaseEpisodeMetadata> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ReleaseEpisodeMetadata(
    @Json(name = "id") val apiId: String? = null,
    @Json(name = "ordinal") val ordinal: Double,
    @Json(name = "name") val name: String? = null,
    @Json(name = "duration") val duration: Double? = null,
    @Json(name = "sort_order") val sortOrder: Double? = null,
    @Json(name = "preview") val preview: EpisodePreviewImage? = null,
) {
    val previewUrl: String? get() = (preview?.optimized?.preview ?: preview?.preview)?.let {
        when {
            it.startsWith("https://") -> it
            it.startsWith("//") -> "https:$it"
            it.startsWith("/") -> "https://cdn.anilibria.top$it"
            else -> null
        }
    }
    val durationSeconds: Long? get() = duration?.takeIf { it.isFinite() && it > 0 }?.toLong()
}

@JsonClass(generateAdapter = true)
data class EpisodePreviewImage(
    @Json(name = "preview") val preview: String? = null,
    @Json(name = "optimized") val optimized: EpisodeOptimizedPreview? = null,
)

@JsonClass(generateAdapter = true)
data class EpisodeOptimizedPreview(@Json(name = "preview") val preview: String? = null)
