package ru.radiationx.anilibria.screen.main

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseRowsViewModel
import ru.radiationx.data.datasource.holders.EpisodesCheckerHolder
import ru.radiationx.data.repository.HistoryRepository
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
import javax.inject.Inject

class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    historyRepository: HistoryRepository,
    episodesCheckerHolder: EpisodesCheckerHolder,
) : BaseRowsViewModel() {

    companion object {
        const val CONTINUE_ROW_ID = 5L
        const val FEED_ROW_ID = 1L
        const val SCHEDULE_ROW_ID = 2L
        const val FAVORITE_ROW_ID = 3L
        const val YOUTUBE_ROW_ID = 4L
    }

    override val rowIds: List<Long> =
        listOf(CONTINUE_ROW_ID, FAVORITE_ROW_ID, FEED_ROW_ID, SCHEDULE_ROW_ID, YOUTUBE_ROW_ID)

    override val availableRows: MutableSet<Long> =
        mutableSetOf(FEED_ROW_ID, SCHEDULE_ROW_ID, YOUTUBE_ROW_ID)

    init {
        combine(
            episodesCheckerHolder.observeEpisodes(),
            historyRepository.observeReleases()
        ) { episodes, history ->
            history.items.any { release -> episodes.any { it.id.releaseId == release.id } }
        }.distinctUntilChanged().onEach {
            updateAvailableRow(CONTINUE_ROW_ID, it)
        }.launchIn(viewModelScope)

        authRepository
            .observeAuthState()
            .distinctUntilChanged()
            .onEach {
                updateAvailableRow(FAVORITE_ROW_ID, it == AuthState.AUTH)
            }
            .launchIn(viewModelScope)
    }
}