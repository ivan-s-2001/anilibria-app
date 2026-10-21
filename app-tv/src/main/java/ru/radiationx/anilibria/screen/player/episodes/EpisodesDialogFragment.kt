package ru.radiationx.anilibria.screen.player.episodes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.databinding.FragmentTvEpisodesBinding
import ru.radiationx.anilibria.screen.player.PlayerExtra
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtra
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo

class EpisodesDialogFragment : DialogFragment() {
    companion object {
        private const val RELEASE = "release"
        private const val EPISODE = "episode"
        private const val FOCUSED = "focused_episode"
        fun newInstance(releaseId: ReleaseId, episodeId: EpisodeId?) = EpisodesDialogFragment().putExtra {
            putParcelable(RELEASE, releaseId)
            putParcelable(EPISODE, episodeId)
        }
    }

    private val viewModel by viewModel<PlayerEpisodesViewModel> {
        PlayerExtra(getExtraNotNull(RELEASE), getExtra(EPISODE))
    }
    private var descending = false
    private var hideWatched = false
    private var focusedEpisode: EpisodeId? = null
    private var initialFocusApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_TvEpisodes)
        descending = savedInstanceState?.getBoolean("descending") ?: false
        hideWatched = savedInstanceState?.getBoolean("hide_watched") ?: false
        @Suppress("DEPRECATION")
        focusedEpisode = savedInstanceState?.getParcelable(FOCUSED)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_tv_episodes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentTvEpisodesBinding.bind(view)
        initialFocusApplied = false
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        val episodeAdapter = EpisodeListAdapter(
            play = { episode -> dismiss(); viewModel.applyEpisode(episode.id) },
            focused = { focusedEpisode = it },
        )
        binding.episodesList.layoutManager = LinearLayoutManager(requireContext())
        binding.episodesList.adapter = episodeAdapter

        fun restoreEpisodeFocus() {
            val id = focusedEpisode ?: viewModel.selectedAction.value?.episodeId
            val index = episodeAdapter.rows.indexOfFirst { it.episode?.episodeId == id }
                .takeIf { it >= 0 } ?: episodeAdapter.rows.indexOfFirst { it.episode != null }
            if (index < 0) return
            binding.episodesList.doOnNextLayout {
                if (this.view === view) {
                    val row = binding.episodesList.findViewHolderForAdapterPosition(index)?.itemView
                    initialFocusApplied = row?.requestFocus() == true
                }
            }
            (binding.episodesList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        }

        fun render() {
            val groups = viewModel.episodesData.value
            val listHadFocus = binding.episodesList.hasFocus()
            episodeAdapter.submit(episodeRows(groups, descending, hideWatched))
            val total = groups.sumOf { it.actions.size }
            val visible = episodeAdapter.rows.count { it.episode != null }
            binding.episodesTitle.text = viewModel.releaseTitle.value.ifBlank { "Серии" }
            binding.episodesSort.text = if (descending) "Сначала последние" else "Сначала первые"
            binding.episodesFilter.text = if (hideWatched) "Не просмотрено" else "Все серии"
            binding.episodesLoading.isVisible = viewModel.loading.value
            binding.episodesRetry.isVisible = viewModel.loadError.value != null
            binding.episodesSummary.text = when {
                viewModel.loading.value -> "Загрузка серий…"
                viewModel.loadError.value != null -> viewModel.loadError.value
                total == 0 -> "Нет доступных серий"
                visible == 0 -> "Все серии просмотрены. Выберите «Все серии», чтобы показать их."
                else -> "Серий: $visible из $total · OK — смотреть"
            }
            if (!viewModel.loading.value && (listHadFocus || !initialFocusApplied)) restoreEpisodeFocus()
        }

        binding.episodesClose.setOnClickListener { dismiss() }
        binding.episodesSort.setOnClickListener { descending = !descending; render() }
        binding.episodesFilter.setOnClickListener { hideWatched = !hideWatched; render() }
        binding.episodesRetry.setOnClickListener { viewModel.loadEpisodes() }
        subscribeTo(viewModel.episodesData) { render() }
        subscribeTo(viewModel.loading) { render() }
        subscribeTo(viewModel.loadError) { render() }
        subscribeTo(viewModel.releaseTitle) { render() }
        subscribeTo(viewModel.selectedAction) {
            if (!initialFocusApplied) { restoreEpisodeFocus() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("descending", descending)
        outState.putBoolean("hide_watched", hideWatched)
        outState.putParcelable(FOCUSED, focusedEpisode)
        super.onSaveInstanceState(outState)
    }
}
