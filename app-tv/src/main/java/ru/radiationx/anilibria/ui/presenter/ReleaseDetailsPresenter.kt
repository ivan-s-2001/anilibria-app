package ru.radiationx.anilibria.ui.presenter

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.leanback.widget.RowPresenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.DetailsState
import ru.radiationx.anilibria.common.LibriaDetails
import ru.radiationx.anilibria.common.LibriaDetailsRow
import ru.radiationx.anilibria.databinding.RowDetailReleaseBinding
import ru.radiationx.shared.ktx.android.getCompatColor
import ru.radiationx.shared.ktx.android.getCompatDrawable
import ru.radiationx.shared_app.imageloader.showImageUrl

class ReleaseDetailsPresenter(
    private val continueClickListener: () -> Unit,
    private val playClickListener: () -> Unit,
    private val favoriteClickListener: () -> Unit,
    private val otherClickListener: () -> Unit,
) : RowPresenter() {

    init {
        headerPresenter = null
    }

    override fun isUsingDefaultSelectEffect(): Boolean {
        return false
    }

    override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.row_detail_release, parent, false)
        return LibriaReleaseViewHolder(
            view,
            continueClickListener,
            playClickListener,
            favoriteClickListener,
            otherClickListener
        )
    }

    override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
        super.onBindRowViewHolder(vh, item)
        vh as LibriaReleaseViewHolder
        item as LibriaDetailsRow
        vh.bind(item)
    }

}

class LibriaReleaseViewHolder(
    itemView: View,
    private val continueClickListener: () -> Unit,
    private val playClickListener: () -> Unit,
    private val favoriteClickListener: () -> Unit,
    private val otherClickListener: () -> Unit,
) : RowPresenter.ViewHolder(itemView) {

    private val binding by lazy {
        RowDetailReleaseBinding.bind(view)
    }

    private var lastState: DetailsState? = null
    private var lastDetails: LibriaDetails? = null

    init {
        binding.rowReleaseActionContinue.setOnClickListener { continueClickListener.invoke() }
        binding.rowReleaseActionPlay.setOnClickListener { playClickListener.invoke() }
        binding.rowReleaseActionOther.setOnClickListener { otherClickListener.invoke() }
        binding.rowReleaseActionFavorite.setOnClickListener { favoriteClickListener.invoke() }
        binding.rowReleaseDescriptionCard.setOnClickListener {
            val details = lastDetails ?: return@setOnClickListener
            AlertDialog.Builder(view.context)
                .setTitle(details.titleRu)
                .setMessage(details.description)
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    fun bind(item: LibriaDetailsRow) {
        item.state?.also { bindState(it) }
        item.details?.also { bindDetails(it) }
    }

    private fun bindState(state: DetailsState) {
        if (lastState == state) {
            return
        }

        lastState = state
        binding.rowReleaseRoot.isFocusable = state.loadingProgress

        binding.rowReleaseActions.isInvisible = state.loadingProgress
        binding.rowReleaseImageCard.isInvisible = state.loadingProgress

        binding.rowReleaseLoadingProgress.isVisible = state.loadingProgress
        binding.rowReleaseUpdateProgress.isVisible = state.updateProgress && !state.loadingProgress
    }

    private fun bindDetails(details: LibriaDetails) {
        if (lastDetails == details) {
            return
        }
        val firstBind = lastDetails == null
        lastDetails = details

        binding.rowReleaseTitleRu.text = details.titleRu
        binding.rowReleaseTitleEn.text = details.titleEn
        binding.rowReleaseTitleEn.isVisible = details.titleEn.isNotBlank()
        binding.rowReleaseExtra.text = details.extra
        binding.rowReleaseDescription.text = details.description
        binding.rowReleaseDescriptionCard.isVisible = details.description.isNotBlank()
        binding.rowReleaseAnnounce.text = details.announce
        binding.rowReleaseAnnounce.isVisible = details.announce.isNotEmpty()
        binding.rowReleaseFavoriteCount.text = details.favoriteCount
        binding.rowReleaseFavoriteCount.isVisible = details.favoriteCount != "0"

        val favoriteDrawable = if (details.isFavorite) {
            binding.rowReleaseFavoriteCount.getCompatDrawable(R.drawable.ic_details_favorite_filled)
        } else {
            binding.rowReleaseFavoriteCount.getCompatDrawable(R.drawable.ic_details_favorite)
        }
        binding.rowReleaseFavoriteCount.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null,
            null,
            favoriteDrawable,
            null
        )
        TextViewCompat.setCompoundDrawableTintList(
            binding.rowReleaseFavoriteCount,
            ColorStateList.valueOf(binding.rowReleaseFavoriteCount.getCompatColor(R.color.dark_textDefault))
        )
        binding.rowReleaseHQMarker.isVisible = details.hasFullHd

        binding.rowReleaseActionContinue.text = details.continueLabel
        binding.rowReleaseActionPlay.text = "Серии"
        binding.rowReleaseActionPlay.isVisible = details.hasEpisodes
        binding.rowReleaseActionContinue.isVisible = details.hasEpisodes
        binding.rowReleaseActionContinue.text = if (details.hasViewed) details.continueLabel else "Смотреть"
        binding.rowReleaseActionOther.isVisible = details.hasEpisodes || details.hasViewed
        binding.rowReleaseActionFavorite.text = if (details.isFavorite) {
            "В избранном"
        } else {
            "В избранное"
        }

        binding.rowReleaseActionFavorite.contentDescription = if (details.isFavorite) {
            "Убрать из избранного"
        } else {
            "Добавить в избранное"
        }
        val actions = listOf(
            binding.rowReleaseActionContinue,
            binding.rowReleaseActionPlay,
            binding.rowReleaseActionFavorite,
            binding.rowReleaseActionOther,
        ).filter { it.isVisible }
        actions.forEachIndexed { index, action ->
            action.nextFocusLeftId = actions.getOrNull(index - 1)?.id ?: action.id
            action.nextFocusRightId = actions.getOrNull(index + 1)?.id ?: action.id
            action.nextFocusUpId = action.id
            action.nextFocusDownId = if (binding.rowReleaseDescriptionCard.isVisible) {
                binding.rowReleaseDescriptionCard.id
            } else View.NO_ID
        }
        binding.rowReleaseDescriptionCard.nextFocusUpId = actions.firstOrNull()?.id ?: View.NO_ID
        if (firstBind && (!binding.root.hasFocus() || binding.root.isFocused)) {
            when {
                details.hasEpisodes -> binding.rowReleaseActionContinue.requestFocus()
                else -> binding.rowReleaseActionFavorite.requestFocus()
            }
        }

        binding.rowReleaseImageCard.showImageUrl(details.image)
        binding.detailBackdrop.showImageUrl(details.image)
    }
}
