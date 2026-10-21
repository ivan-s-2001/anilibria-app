package ru.radiationx.anilibria.common

import ru.radiationx.data.entity.domain.types.ReleaseId

data class LibriaCard(
    val title: String,
    val description: String,
    val image: String,
    val type: Type,
    val preview: Preview? = null,
) : CardItem {

    override fun getId(): Int {
        return type.hashCode()
    }

    data class Preview(
        val alternateTitle: String,
        val metadata: String,
        val genres: String,
        val synopsis: String,
        val status: String,
    )

    sealed class Type {
        data class Release(val releaseId: ReleaseId) : Type()
        data class Youtube(val link: String) : Type()
    }
}