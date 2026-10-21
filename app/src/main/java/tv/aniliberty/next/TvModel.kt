package tv.aniliberty.next

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Welcome : Screen
    data object Home : Screen
    data class Catalog(val query: String = "", val page: Int = 1, val genre: Int? = null, val title: String? = null) : Screen
    data class Franchise(val id: String, val title: String) : Screen
    data class Detail(val id: Int, val tab: Int = 0) : Screen
}
data class UiState(val screen: Screen = Screen.Welcome, val loading: Boolean = false, val error: String? = null, val releases: List<Release> = emptyList(), val detail: Release? = null, val more: Boolean = false, val home: HomeData? = null, val homeDay: Int = 1)
class TvModel : ViewModel() {
    val api = Api()
    val state = MutableStateFlow(UiState())
    private val stack = mutableListOf<Screen>()
    private var job: Job? = null
    fun open(screen: Screen, remember: Boolean = true) {
        if (remember && screen != state.value.screen) stack.add(state.value.screen)
        job?.cancel()
        val previous = state.value
        if (screen is Screen.Detail && previous.detail?.id == screen.id) {
            state.value = previous.copy(screen = screen); return
        }
        state.value = UiState(screen, loading = screen != Screen.Welcome)
        if (screen == Screen.Welcome) return
        job = viewModelScope.launch {
            try {
                state.value = when (screen) {
                    Screen.Home -> UiState(screen, home = api.home())
                    is Screen.Catalog -> api.catalog(screen.query, screen.page, screen.genre).let { (items, more) -> UiState(screen, releases = items, more = more) }
                    is Screen.Franchise -> UiState(screen, releases = api.franchise(screen.id))
                    is Screen.Detail -> UiState(screen, detail = api.release(screen.id))
                    else -> UiState(screen)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UiState(screen, error = e.message ?: "Не удалось загрузить данные") }
        }
    }
    fun homeDay(index: Int) { state.value = state.value.copy(homeDay = index) }
    fun tab(index: Int) { (state.value.screen as? Screen.Detail)?.let { state.value = state.value.copy(screen = it.copy(tab = index)) } }
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        open(stack.removeAt(stack.lastIndex), false); return true
    }
    fun retry() = open(state.value.screen, false)
}
