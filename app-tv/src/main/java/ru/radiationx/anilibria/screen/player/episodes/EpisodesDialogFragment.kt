package ru.radiationx.anilibria.screen.player.episodes

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.core.widget.addTextChangedListener
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
        private const val FROM_PLAYER = "from_player"
        fun newInstance(releaseId: ReleaseId, episodeId: EpisodeId?, fromPlayer: Boolean = false) = EpisodesDialogFragment().putExtra {
            putParcelable(RELEASE, releaseId)
            putParcelable(EPISODE, episodeId)
            putBoolean(FROM_PLAYER, fromPlayer)
        }
    }

    private val viewModel by viewModel<PlayerEpisodesViewModel> {
        PlayerExtra(getExtraNotNull(RELEASE), getExtra(EPISODE), fromPlayer)
    }
    private var descending = false
    private var hideWatched = false
    private var focusedEpisode: EpisodeId? = null
    private var initialFocusApplied = false
    private var query = ""
    private val fromPlayer: Boolean get() = arguments?.getBoolean(FROM_PLAYER) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme_TvEpisodes)
        descending = savedInstanceState?.getBoolean("descending") ?: false
        hideWatched = savedInstanceState?.getBoolean("hide_watched") ?: false
        query = savedInstanceState?.getString("query").orEmpty()
        @Suppress("DEPRECATION")
        focusedEpisode = savedInstanceState?.getParcelable(FOCUSED)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_tv_episodes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTvEpisodesBinding.bind(view)
        binding.root.requestFocus()
        if (fromPlayer) {
            val density = resources.displayMetrics.density
            binding.root.setPadding((16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt())
            binding.episodesTitle.textSize = 20f
            binding.episodesSummary.textSize = 14f
            binding.episodesFilter.isVisible = false
        }
        initialFocusApplied = false
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        val episodeAdapter = EpisodeListAdapter(
            play = { episode -> dismiss(); viewModel.applyEpisode(episode.id) },
            focused = { focusedEpisode = it },
            compact = fromPlayer,
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
            episodeAdapter.submit(episodeRows(groups, descending, hideWatched, query))
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
                visible == 0 && query.isNotBlank() -> "Ничего не найдено. Измените название или номер."
                visible == 0 -> "Все серии просмотрены. Выберите «Все серии», чтобы показать их."
                else -> "Серий: $visible из $total · OK — смотреть"
            }
            if (!viewModel.loading.value && !binding.episodesSearch.hasFocus() &&
                (listHadFocus || !initialFocusApplied)) restoreEpisodeFocus()
        }

        binding.episodesClose.setOnClickListener { dismiss() }
        binding.episodesSort.setOnClickListener { descending = !descending; render() }
        binding.episodesFilter.setOnClickListener { hideWatched = !hideWatched; render() }
        binding.episodesRetry.setOnClickListener { viewModel.loadEpisodes() }
        binding.episodesSearch.setText(query)
        binding.episodesSearch.addTextChangedListener { query = it?.toString().orEmpty(); render() }
        binding.episodesSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.episodesSearch.windowToken, 0)
                restoreEpisodeFocus()
                true
            } else false
        }
        dialog?.setOnKeyListener { _, key, event ->
            if (fromPlayer && key == KeyEvent.KEYCODE_DPAD_LEFT && binding.episodesList.hasFocus()) {
                if (event.action == KeyEvent.ACTION_UP) dismiss()
                true
            } else false
        }
        subscribeTo(viewModel.episodesData) { render() }
        subscribeTo(viewModel.loading) { render() }
        subscribeTo(viewModel.loadError) { render() }
        subscribeTo(viewModel.releaseTitle) { render() }
        subscribeTo(viewModel.selectedAction) {
            if (!initialFocusApplied && !binding.episodesSearch.hasFocus()) { restoreEpisodeFocus() }
        }
    }

    override fun onStart() {
        super.onStart()
        val display = requireActivity().resources.displayMetrics
        dialog?.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setLayout(episodePickerWidth(display.widthPixels,
                display.density, fromPlayer), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(if (fromPlayer) Gravity.END else Gravity.CENTER)
            if (fromPlayer) {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0f }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("descending", descending)
        outState.putBoolean("hide_watched", hideWatched)
        outState.putString("query", query)
        outState.putParcelable(FOCUSED, focusedEpisode)
        super.onSaveInstanceState(outState)
    }
}
