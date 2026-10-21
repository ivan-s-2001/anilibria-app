package ru.radiationx.anilibria.screen.details

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.screen.main.MainFeedViewModel
import ru.radiationx.anilibria.screen.player.PlayerExtra
import ru.radiationx.anilibria.screen.player.episodes.EpisodeListAdapter
import ru.radiationx.anilibria.screen.player.episodes.PlayerEpisodesViewModel
import ru.radiationx.anilibria.screen.player.episodes.episodeRows
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.QuillExtra
import ru.radiationx.quill.inject
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo

data class DetailExtra(val id: ReleaseId) : QuillExtra

/** A single virtualized page: title, synopsis, tabs and their content share one scroll. */
class DetailFragment : Fragment(R.layout.fragment_release_page) {
    companion object {
        private const val ARG_ID = "id"
        fun newInstance(releaseId: ReleaseId) = DetailFragment().putExtra { putParcelable(ARG_ID, releaseId) }
    }
    private val extra by lazy { DetailExtra(getExtraNotNull(ARG_ID)) }
    private val backgroundManager by inject<GradientBackgroundManager>()
    private val pageData by viewModel<DetailsViewModel> { extra }
    private val header by viewModel<DetailHeaderViewModel> { extra }
    private val related by viewModel<DetailRelatedViewModel> { extra }
    private val recommends by viewModel<DetailRecommendsViewModel> { extra }
    private val newEpisodes by viewModel<MainFeedViewModel>()
    private val episodes by viewModel<PlayerEpisodesViewModel> { PlayerExtra(extra.id, null) }
    private var selectedTab = 0
    private var descending = false
    private var focusedEpisode: EpisodeId? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedTab = state?.getInt("tab") ?: 0
        descending = state?.getBoolean("descending") ?: false
        @Suppress("DEPRECATION")
        focusedEpisode = state?.getParcelable("episode")
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val list = view as RecyclerView
        list.itemAnimator = null
        backgroundManager.clearGradient()
        listOf(pageData, header, related, recommends, episodes, newEpisodes).forEach { viewLifecycleOwner.lifecycle.addObserver(it) }
        val content = ReleaseTextAdapter()
        val relatedCards = ReleaseCardsAdapter(related::onLibriaCardClick)
        var pendingEpisodeRestore = focusedEpisode != null && selectedTab == 0
        val freshHeading = ReleaseTextAdapter()
        val freshCards = ReleaseCardsAdapter(newEpisodes::onLibriaCardClick)
        val recommendationHeading = ReleaseTextAdapter()
        val recommendationCards = ReleaseCardsAdapter(recommends::onLibriaCardClick)
        val episodeCards = EpisodeListAdapter(
            play = { episodes.applyEpisode(it.id) }, focused = { focusedEpisode = it },
        ).apply { previewMode = true }
        val toolbar = ReleaseTextAdapter()
        lateinit var page: ConcatAdapter
        lateinit var tabs: ReleaseTabsAdapter
        lateinit var title: ReleaseHeaderAdapter
        val grid = GridLayoutManager(requireContext(), 3)
        list.layoutManager = grid

        fun positionOf(adapter: RecyclerView.Adapter<*>): Int =
            page.adapters.takeWhile { it !== adapter }.sumOf { it.itemCount }

        fun focusEpisode() {
            val index = episodeCards.rows.indexOfFirst { it.episode?.episodeId == focusedEpisode }
                .takeIf { it >= 0 } ?: episodeCards.rows.indexOfFirst { it.episode != null }
            if (index < 0) return
            val target = positionOf(episodeCards) + index
            list.doOnNextLayout { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            grid.scrollToPositionWithOffset(target, list.dp(12))
        }

        fun renderEpisodes() {
            val hadEpisodeFocus = list.findFocus()?.let { focused ->
                list.findContainingViewHolder(focused)?.bindingAdapter === episodeCards
            } == true
            episodeCards.submit(episodeRows(episodes.episodesData.value, descending, false))
            val count = episodeCards.rows.count { it.episode != null }
            toolbar.rows = listOf(ReleasePageText(
                when {
                    episodes.loading.value -> "Загрузка эпизодов…"
                    episodes.loadError.value != null -> "Не удалось загрузить эпизоды · Повторить"
                    count == 0 -> "Эпизоды пока не доступны"
                    else -> "$count эпизодов · ${if (descending) "Сначала новые" else "Сначала первые"}"
                }, if (count > 0) "OK — изменить порядок" else "",
                { if (episodes.loadError.value != null) episodes.loadEpisodes() else { descending = !descending; renderEpisodes() } },
            ))
            if (hadEpisodeFocus || (pendingEpisodeRestore && count > 0)) {
                pendingEpisodeRestore = false
                focusEpisode()
            }
        }

        fun renderTextContent() {
            val release = header.fullRelease.value
            content.rows = when (selectedTab) {
                1 -> if (related.cardsData.value.any { it is LoadingCard })
                    listOf(ReleasePageText("Связанное", "Загрузка… / OK — повторить", related::onRefreshClick))
                    else if (relatedCards.itemCount == 0) listOf(ReleasePageText("Связанное", "Другие релизы не найдены")) else emptyList()
                2 -> listOf(ReleasePageText("Комментарии", "Открыть обсуждение на сайте", {
                    openLink("https://aniliberty.top/anime/releases/${release?.code?.code ?: extra.id.id}")
                }))
                3 -> release?.members?.let { members ->
                    listOf("Озвучивание" to members.voicing, "Перевод" to members.translating,
                        "Тайминг" to members.timing, "Редактирование" to members.editing,
                        "Оформление" to members.decorating)
                        .filter { it.second.isNotEmpty() }.map { ReleasePageText(it.first, it.second.joinToString(" • ")) }
                }?.takeIf { it.isNotEmpty() } ?: listOf(ReleasePageText("Работа над релизом", "Состав команды пока не указан"))
                4 -> release?.torrents?.map { torrent ->
                    ReleasePageText(listOfNotNull(torrent.quality, torrent.series?.let { "Эпизоды $it" }).joinToString(" · "),
                        "%.2f ГБ · Раздают: %d · Скачивают: %d".format(torrent.size / 1073741824.0, torrent.seeders, torrent.leechers),
                        torrent.url?.let { url -> { openLink(url) } })
                }?.takeIf { it.isNotEmpty() } ?: listOf(ReleasePageText("Торренты", "Раздачи пока не доступны"))
                else -> emptyList()
            }
        }

        fun selectTab(index: Int) {
            selectedTab = index
            tabs.selected = index
            val desired = mutableListOf<RecyclerView.Adapter<*>>(title, tabs)
            if (index == 0) desired.addAll(listOf(toolbar, episodeCards)) else {
                desired.add(content)
                if (index == 1) desired.add(relatedCards)
            }
            desired.addAll(listOf(freshHeading, freshCards, recommendationHeading, recommendationCards))
            page.adapters.filter { it !in desired }.forEach(page::removeAdapter)
            desired.forEachIndexed { position, adapter ->
                if (adapter !in page.adapters) page.addAdapter(position, adapter)
            }
            renderTextContent()
        }
        tabs = ReleaseTabsAdapter(::selectTab).apply { selected = selectedTab }
        title = ReleaseHeaderAdapter(header::onContinueClick, { selectTab(0); focusEpisode() }, header::onFavoriteClick, header::onOtherClick)
        page = ConcatAdapter(title, tabs)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                var offset = position
                page.adapters.forEach { adapter ->
                    if (offset < adapter.itemCount) return if (adapter === episodeCards) {
                        if (episodeCards.rows.getOrNull(offset)?.episode != null) 1 else 3
                    } else if (adapter === relatedCards || adapter === recommendationCards || adapter === freshCards) 1 else 3
                    offset -= adapter.itemCount
                }
                return 3
            }
        }
        list.adapter = page
        selectTab(selectedTab)
        renderEpisodes()
        subscribeTo(header.releaseData) { title.row.details = it; title.notifyItemChanged(0); renderTextContent() }
        subscribeTo(header.progressState) { title.row.state = it; title.notifyItemChanged(0) }
        subscribeTo(header.fullRelease) { renderTextContent() }
        subscribeTo(episodes.episodesData) { renderEpisodes() }
        subscribeTo(episodes.loading) { renderEpisodes() }
        subscribeTo(episodes.loadError) { renderEpisodes() }
        subscribeTo(related.cardsData) {
            relatedCards.cards = it.filterIsInstance<LibriaCard>(); if (selectedTab == 1) renderTextContent()
        }
        subscribeTo(newEpisodes.cardsData) {
            freshCards.cards = it.filterIsInstance<LibriaCard>().filter { card ->
                val type = card.type as? LibriaCard.Type.Release
                type != null && type.releaseId != extra.id
            }.take(3)
            freshHeading.rows = if (freshCards.itemCount > 0) listOf(ReleasePageText("Новые эпизоды", "Свежие обновления релизов", heading = true)) else emptyList()
        }
        subscribeTo(recommends.cardsData) {
            recommendationCards.cards = it.filterIsInstance<LibriaCard>().take(9)
            recommendationHeading.rows = when {
                recommendationCards.itemCount > 0 -> listOf(ReleasePageText("Рекомендуем", "Похожие по жанрам", heading = true))
                it.any { card -> card is LoadingCard } -> listOf(ReleasePageText("Рекомендации · Повторить загрузку", click = recommends::onRefreshClick))
                else -> emptyList()
            }
        }
    }

    private fun openLink(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://") && !url.startsWith("magnet:")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(requireContext(), "Нет приложения для открытия ссылки", Toast.LENGTH_LONG).show() }
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("tab", selectedTab); state.putBoolean("descending", descending)
        state.putParcelable("episode", focusedEpisode)
        super.onSaveInstanceState(state)
    }
}
