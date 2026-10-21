package ru.radiationx.anilibria.screen.player.episodes

import android.os.Bundle
import android.view.View
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import androidx.leanback.widget.GuidanceStylist.Guidance
import kotlinx.coroutines.flow.filterNotNull
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.screen.player.BasePlayerGuidedFragment
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class PlayerEpisodesGuidedFragment : BasePlayerGuidedFragment() {

    companion object {
        private const val RETRY_ID = -2L
    }

    private val viewModel by viewModel<PlayerEpisodesViewModel> { argExtra }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        "Серии", "Выберите серию для просмотра", null, null,
    )

    override fun onCreateActionsStylist(): GuidedActionsStylist = EpisodeActionsStylist { id ->
        viewModel.episodesData.value.asSequence().flatMap { it.actions.asSequence() }
            .firstOrNull { it.id == id }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        guidanceStylist.titleView?.textSize = 26f
        guidanceStylist.descriptionView?.textSize = 16f

        fun updateSummary() {
            val count = viewModel.episodesData.value.sumOf { it.actions.size }
            guidanceStylist.descriptionView?.text = when {
                viewModel.loading.value -> "Загрузка серий…"
                viewModel.loadError.value != null -> viewModel.loadError.value
                count == 0 -> "Нет доступных серий"
                else -> "Доступно серий: $count · OK — смотреть"
            }
        }

        subscribeTo(viewModel.episodesData) {
            actions = createGroupedActions(it)
            updateSummary()
        }
        subscribeTo(viewModel.loading) { updateSummary() }
        subscribeTo(viewModel.loadError) { error ->
            actions = if (error == null) createGroupedActions(viewModel.episodesData.value) else listOf(
                GuidedAction.Builder(requireContext()).id(RETRY_ID).title("Повторить").build()
            )
            updateSummary()
        }

        subscribeTo(viewModel.releaseTitle) {
            guidanceStylist.titleView?.text = it.ifBlank { "Серии" }
        }

        subscribeTo(viewModel.selectedAction.filterNotNull()) { action ->
            val position = findActionPositionById(action.id)
            if (position >= 0) selectedActionPosition = position
        }
    }

    private fun createGroupedActions(groups: List<PlayerEpisodesViewModel.Group>): List<GuidedAction> {
        if (groups.size <= 1) {
            return groups.getOrNull(0)?.let { createEpisodesActions(it.actions) }.orEmpty()
        }
        return buildList {
            groups.forEach { group ->
                val groupAction = GuidedAction.Builder(requireContext())
                    .id(group.id)
                    .title(group.title)
                    .multilineDescription(true)
                    .infoOnly(true)
                    .enabled(false)
                    .focusable(false)
                    .build()
                add(groupAction)
                addAll(createEpisodesActions(group.actions))
            }
        }
    }

    private fun createEpisodesActions(
        episodes: List<PlayerEpisodesViewModel.Action>,
    ): List<GuidedAction> {
        return episodes.map { action ->
            GuidedAction.Builder(requireContext())
                .id(action.id)
                .title(action.title)
                .description(action.description)
                .build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == RETRY_ID) {
            viewModel.loadEpisodes()
            return
        }
        if (!action.hasSubActions()) {
            viewModel.applyEpisode(action.id)
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        viewModel.applyEpisode(action.id)
        return super.onSubGuidedActionClicked(action)
    }
}
