package ru.radiationx.anilibria.screen.watching

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.radiationx.anilibria.screen.PlayerScreen
import ru.radiationx.shared.ktx.coRunCatching
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.datasource.holders.EpisodesCheckerHolder
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.HistoryRepository
import javax.inject.Inject

class WatchingContinueViewModel @Inject constructor(
    private val releaseInteractor: ReleaseInteractor,
    private val historyRepository: HistoryRepository,
    private val episodesCheckerHolder: EpisodesCheckerHolder,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter,
    private val router: Router,
) : BaseCardsViewModel() {

    private var playJob: Job? = null

    override val defaultTitle: String = "Продолжить просмотр"

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = episodesCheckerHolder
        .getEpisodes()
        .let { episodeAccesses ->
            episodeAccesses.sortedByDescending { it.lastAccessRaw }.map { it.id.releaseId }
        }
        .let { ids ->
            if (ids.isEmpty()) {
                return@let emptyList()
            }
            historyRepository.getReleases().items.let { releases ->
                releases.filter { ids.contains(it.id) }
            }
        }
        .let { releases ->
            releases.map { release ->
                val lastEpisode =
                    releaseInteractor.getAccesses(release.id).maxByOrNull { it.lastAccessRaw }
                Pair(release, lastEpisode)
            }
        }
        .let { pairs ->
            pairs.sortedByDescending { it.second?.lastAccessRaw }.map {
                converter.toCard(it.first)
                    .copy(description = "Вы остановились на ${it.second?.id?.id} серии")
            }
        }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        false

    override fun onLibriaCardClick(card: LibriaCard) {
        val type = card.type as? LibriaCard.Type.Release ?: return
        if (playJob?.isActive == true) return
        playJob = viewModelScope.launch {
            coRunCatching {
                val release = withTimeoutOrNull(12_000L) { releaseInteractor.getFull(type.releaseId) }
                val access = releaseInteractor.getAccesses(type.releaseId)
                    .filter { saved -> release?.episodes?.any { it.id == saved.id } == true }
                    .maxByOrNull { it.lastAccessRaw }
                if (access != null) router.navigateTo(PlayerScreen(type.releaseId, access.id))
                else cardRouter.navigate(card)
            }.onFailure { cardRouter.navigate(card) }
        }
    }
}