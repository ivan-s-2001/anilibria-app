package ru.radiationx.anilibria.screen.search

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.anilibria.screen.SuggestionsScreen
import ru.radiationx.data.entity.domain.search.SearchForm
import ru.radiationx.data.repository.SearchRepository
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val converter: CardsDataConverter,
    private val router: Router,
    private val cardRouter: LibriaCardRouter,
    searchController: SearchController,
) : BaseCardsViewModel() {

    private var searchForm = SearchForm()
    private val loaderVersion = AtomicLong()

    val progressState = MutableStateFlow(false)

    override val loadOnCreate: Boolean = false

    override val progressOnRefresh: Boolean = false

    override val replaceRequestOnRefresh: Boolean = true

    init {
        searchController.applyFormEvent.onEach {
            searchForm = it
            onRefreshClick()
        }.launchIn(viewModelScope)
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> {
        val version = loaderVersion.incrementAndGet()
        progressState.value = requestPage == firstPage
        return try {
            searchRepository.searchReleases(searchForm, requestPage)
                .data.map { converter.toCard(it) }
        } finally {
            if (version == loaderVersion.get()) progressState.value = false
        }
    }

    fun onSearchClick() {
        router.navigateTo(SuggestionsScreen())
    }

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}