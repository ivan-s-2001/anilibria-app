package ru.radiationx.anilibria.screen.player.episodes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.player.PlayerController
import ru.radiationx.anilibria.screen.player.PlayerExtra
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.datasource.remote.api.ReleaseEpisodeMetadataApi
import ru.radiationx.data.datasource.remote.api.ReleaseEpisodeMetadata
import ru.radiationx.shared.ktx.asTimeSecString
import java.util.Date
import java.math.BigDecimal
import javax.inject.Inject

class PlayerEpisodesViewModel @Inject constructor(
    private val argExtra: PlayerExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val playerController: PlayerController,
    private val episodeMetadataApi: ReleaseEpisodeMetadataApi,
) : LifecycleViewModel() {

    val episodesData = MutableStateFlow<List<Group>>(emptyList())
    val selectedAction = MutableStateFlow<Action?>(null)
    val releaseTitle = MutableStateFlow("")
    val loading = MutableStateFlow(true)
    val loadError = MutableStateFlow<String?>(null)
    private var applyingEpisode = false
    private var loadJob: Job? = null
    private var metadata: Map<Float, ReleaseEpisodeMetadata> = emptyMap()
    private var lastReleases: List<Release> = emptyList()
    private var lastAccesses: Map<EpisodeId, EpisodeAccess> = emptyMap()

    init {
        loadEpisodes()
        // Only enrich the title browser. Switching in the player must stay instant.
        if (!argExtra.fromPlayer) viewModelScope.launch {
            try {
                metadata = episodeMetadataApi.get(argExtra.releaseId.id).associateBy { it.ordinal.toFloat() }
                if (lastReleases.isNotEmpty()) episodesData.value = lastReleases.toGroups(lastAccesses)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The numbered cards remain usable when optional v1 metadata is unavailable.
            }
        }
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
        playerController.selectEpisodeRelay.emit(action.episodeId)
    }

    private suspend fun updateEpisodes(releases: List<Release>) {
        val accesses = releases
            .flatMap { releaseInteractor.getAccesses(it.id) }
            .associateBy { it.id }
        lastReleases = releases
        lastAccesses = accesses
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
        return map { release ->
            val groupId = -release.id.id.toLong()
            fun meta(number: String) = if (release.id == argExtra.releaseId) number.toFloatOrNull()?.let(metadata::get) else null
            val actions = release.episodes.sortedWith(compareBy<ru.radiationx.data.entity.domain.release.Episode> {
                meta(it.id.id)?.sortOrder?.takeIf(Double::isFinite) ?: Double.MAX_VALUE
            }.thenBy { it.id.id.toBigDecimalOrNull() ?: BigDecimal.ZERO }).map { episode ->
                val access = accesses[episode.id]
                val info = meta(episode.id.id)
                val duration = info?.durationSeconds
                val description = listOfNotNull(
                    "Текущая серия".takeIf { argExtra.fromPlayer && episode.id == argExtra.episodeId },
                    "Просмотрено".takeIf { access?.isViewed == true },
                    access?.seek?.takeIf { it > 0 }?.let { "Продолжить с ${Date(it).asTimeSecString()}" },
                ).joinToString(" · ").ifEmpty { "Не просмотрено" }
                Action(
                    id = (release.id.id.toLong() shl 32) or
                        (episode.id.id.toFloat().toRawBits().toLong() and 0xffffffffL),
                    episodeId = episode.id,
                    title = episodeDisplayTitle(info?.name ?: episode.title, episode.id.id),
                    description = description,
                    number = episode.id.id,
                    watched = access?.isViewed == true,
                    current = argExtra.fromPlayer && episode.id == argExtra.episodeId,
                    preview = info?.previewUrl,
                    durationSeconds = duration,
                    progress = if (duration != null && duration > 0) {
                        ((access?.seek ?: 0).toDouble() / (duration * 1000.0)).coerceIn(0.0, 1.0).toFloat()
                    } else 0f,
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
        val current: Boolean = false,
        val preview: String? = null,
        val durationSeconds: Long? = null,
        val progress: Float = 0f,
    )
}
