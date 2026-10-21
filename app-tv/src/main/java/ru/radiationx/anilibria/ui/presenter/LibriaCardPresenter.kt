package ru.radiationx.anilibria.ui.presenter

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.ui.widget.LauncherCardView
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.shared_app.imageloader.showImageUrl

class LibriaCardPresenter(private val homeStyle: Boolean = false) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = LauncherCardView(parent.context)
        return LibriaCardViewHolder(cardView, homeStyle)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        item ?: return
        item as LibriaCard
        viewHolder as LibriaCardViewHolder
        viewHolder.bind(item)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder as LibriaCardViewHolder
        viewHolder.unbind()
    }
}

class LibriaCardViewHolder(
    private val containerView: ImageCardView,
    private val homeStyle: Boolean = false,
) : Presenter.ViewHolder(containerView) {

    private val cardHeight by lazy {
        containerView.context.resources.getDimension(R.dimen.card_height).toInt()
    }
    private val cardReleaseWidth by lazy {
        containerView.context.resources.getDimension(R.dimen.card_release_width).toInt()
    }
    private val cardYoutubeWidth by lazy {
        containerView.context.resources.getDimension(R.dimen.card_youtube_width).toInt()
    }

    private fun dimension(id: Int) = containerView.resources.getDimensionPixelSize(id)

    fun bind(item: LibriaCard) {
        (containerView as? LauncherCardView)?.previewCard = item
        when (item.type) {
            is LibriaCard.Type.Release -> containerView.setMainImageDimensions(
                if (homeStyle) dimension(R.dimen.home_poster_width) else cardReleaseWidth,
                if (homeStyle) dimension(R.dimen.home_poster_height) else cardHeight
            )

            is LibriaCard.Type.Youtube -> containerView.setMainImageDimensions(
                cardYoutubeWidth,
                cardHeight
            )
        }
        containerView.titleText = if (homeStyle && item.type is LibriaCard.Type.Release) null else item.title
        containerView.contentText = if (homeStyle && item.type is LibriaCard.Type.Release) null else item.description
        containerView.contentDescription = listOf(item.title, item.description)
            .filter { it.isNotBlank() }.joinToString(". ")
        containerView.mainImageView?.showImageUrl(item.image)
    }

    fun unbind() {
        (containerView as? LauncherCardView)?.previewCard = null
        containerView.mainImage = null
        containerView.titleText = null
        containerView.contentText = null
        containerView.contentDescription = null
    }
}