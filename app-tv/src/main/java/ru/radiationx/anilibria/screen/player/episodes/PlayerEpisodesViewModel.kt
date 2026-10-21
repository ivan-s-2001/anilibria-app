package ru.radiationx.anilibria.screen.player.episodes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.player.PlayerController
import ru.radiationx.anilibria.screen.player.PlayerExtra
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.shared.ktx.asTimeSecString
import java.util.Date
import java.math.BigDecimal
import javax.inject.Inject

class PlayerEpisodesViewModel @Inject constructor(
    private val argExtra: PlayerExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val guidedRouter: GuidedRouter,
    private val playerController: PlayerController,
) : LifecycleViewModel() {

    val episodesData = MutableStateFlow<List<Group>>(emptyList())
    val selectedAction = MutableStateFlow<Action?>(null)
    val releaseTitle = MutableStateFlow("")
    val loading = MutableStateFlow(true)
    val loadError = MutableStateFlow<String?>(null)
    private var applyingEpisode = false
    private var loadJob: Job? = null

    init {
        loadEpisodes()
    }

    fun loadEpisodes() {
        loadJob?.cancel()
        loading.value = true
        loadError.value = null
        val playerData = playerController.data.value
        if (playerData?.any { it.id == argExtra.releaseId } == true) {
            loadJob = viewModelScope.launch {
                try {
                    updateEpisodes(playerData)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    loadError.value = "Не удалось загрузить серии. Нажмите «Повторить»."
                    loading.value = false
                }
            }
        } else {
            loadJob = releaseInteractor
                .observeFull(argExtra.releaseId)
                .onEach {
                    updateEpisodes(listOf(it))
                }
                .catch {
                    loadError.value = "Не удалось загрузить серии. Нажмите «Повторить»."
                    loading.value = false
                }
                .launchIn(viewModelScope)
        }
    }

    fun applyEpisode(actionId: Long) {
        val action = episodesData.value.findAction { it.id == actionId } ?: return
        if (applyingEpisode) return
        applyingEpisode = true
        guidedRouter.close()
        playerController.selectEpisodeRelay.emit(action.episodeId)
    }

    private suspend fun updateEpisodes(releases: List<Release>) {
        val accesses = releases
            .flatMap { releaseInteractor.getAccesses(it.id) }
            .associateBy { it.id }
        val groups = releases.toGroups(accesses)
        releaseTitle.value = releases.firstOrNull { it.id == argExtra.releaseId }?.title.orEmpty()
        episodesData.value = groups
        loading.value = false
        val available = groups.flatMap { it.actions }
        val targetRelease = available.filter { it.episodeId.releaseId == argExtra.releaseId }
        selectedAction.value = available.find { it.episodeId == argExtra.episodeId }
            ?: targetRelease.filter { accesses[it.episodeId]?.lastValidAccess != null }
                .maxByOrNull { accesses[it.episodeId]?.lastAccessRaw ?: 0L }
            ?: targetRelease.firstOrNull { !it.watched }
            ?: targetRelease.firstOrNull()
            ?: available.firstOrNull()
    }

    private fun List<Group>.findAction(block: (Action) -> Boolean): Action? {
        forEach {
            val action = it.actions.find(block)
            if (action != null) {
                return action
            }
        }
        return null
    }

    private fun List<Release>.toGroups(accesses: Map<EpisodeId, EpisodeAccess>): List<Group> {
        var id = 0L
        return map { release ->
            val groupId = id++
            val actions = release.episodes.sortedBy {
                it.id.id.toBigDecimalOrNull() ?: BigDecimal.ZERO
            }.map { episode ->
                val access = accesses[episode.id]
                val description = listOfNotNull(
                    "Текущая серия".takeIf { episode.id == argExtra.episodeId },
                    "Просмотрено".takeIf { access?.isViewed == true },
                    access?.seek?.takeIf { it > 0 }?.let { "Продолжить с ${Date(it).asTimeSecString()}" },
                ).joinToString(" · ").ifEmpty { "Не просмотрено" }
                Action(
                    id = id++,
                    episodeId = episode.id,
                    title = episode.title?.takeIf { it.isNotBlank() } ?: "Серия ${episode.id.id}",
                    description = description,
                    number = episode.id.id,
                    watched = access?.isViewed == true,
                )
            }
            Group(
                id = groupId,
                title = release.title.orEmpty(),
                actions = actions
            )
        }
    }

    data class Group(
        val id: Long,
        val title: String,
        val actions: List<Action>,
    )

    data class Action(
        val id: Long,
        val episodeId: EpisodeId,
        val title: String,
        val description: String?,
        val number: String,
        val watched: Boolean,
    )
}
