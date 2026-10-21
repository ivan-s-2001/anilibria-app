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
data class UiState(val screen: Screen = Screen.Welcome, val loading: Boolean = false, val error: String? = null, val releases: List<Release> = emptyList(), val detail: Release? = null, val more: Boolean = false, val home: HomeData? = null, val homeDay: Int = 1, val discovery: Discovery? = null, val discoveryLoading: Boolean = false)
class TvModel : ViewModel() {
    val api = Api()
    val state = MutableStateFlow(UiState())
    private val stack = mutableListOf<Screen>()
    private var job: Job? = null
    private var homeCache: HomeData? = null
    private var homeLoadedAt = 0L
    private var selectedHomeDay = 1
    fun open(screen: Screen, remember: Boolean = true) {
        if (remember && state.value.screen != Screen.Welcome && screen != state.value.screen) stack.add(state.value.screen)
        job?.cancel()
        if (screen == Screen.Home && homeCache != null && android.os.SystemClock.elapsedRealtime() - homeLoadedAt < 300000) {
            state.value = UiState(screen, home = homeCache, homeDay = selectedHomeDay)
            return
        }
        val previous = state.value
        if (screen is Screen.Detail && previous.detail?.id == screen.id) {
            state.value = previous.copy(screen = screen); return
        }
        state.value = UiState(screen, loading = screen != Screen.Welcome)
        if (screen == Screen.Welcome) return
        job = viewModelScope.launch {
            try {
                state.value = when (screen) {
                    Screen.Home -> api.home().let { home -> homeCache = home; homeLoadedAt = android.os.SystemClock.elapsedRealtime(); UiState(screen, home = home, homeDay = selectedHomeDay) }
                    is Screen.Catalog -> api.catalog(screen.query, screen.page, screen.genre).let { (items, more) -> UiState(screen, releases = items, more = more) }
                    is Screen.Franchise -> UiState(screen, releases = api.franchise(screen.id))
                    is Screen.Detail -> UiState(screen, detail = api.release(screen.id))
                    else -> UiState(screen)
                }
                if (screen is Screen.Detail) {
                    state.value = state.value.copy(discoveryLoading = true)
                    val discovery = api.discovery(screen.id)
                    val active = state.value.screen as? Screen.Detail
                    if (active?.id == screen.id) state.value = state.value.copy(discovery = discovery, discoveryLoading = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UiState(screen, error = e.message ?: "Не удалось загрузить данные") }
        }
    }
    fun homeDay(index: Int) { selectedHomeDay = index; state.value = state.value.copy(homeDay = index) }
    fun tab(index: Int) { (state.value.screen as? Screen.Detail)?.let { state.value = state.value.copy(screen = it.copy(tab = index)) } }
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        open(stack.removeAt(stack.lastIndex), false); return true
    }
    fun retryDiscovery() {
        val screen=state.value.screen as? Screen.Detail?:return
        job?.cancel()
        state.value=state.value.copy(discoveryLoading=true)
        job=viewModelScope.launch {
            val result=api.discovery(screen.id)
            if((state.value.screen as? Screen.Detail)?.id==screen.id)state.value=state.value.copy(discovery=result,discoveryLoading=false)
        }
    }
    fun retry() { if (state.value.screen == Screen.Home) homeCache = null; open(state.value.screen, false) }
}
