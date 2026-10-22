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
    data class Favorites(val query:String="",val filter:AccountFilter=AccountFilter()):Screen
    data class Collections(val type:CollectionType=CollectionType.WATCHING,val query:String="",val filter:AccountFilter=AccountFilter()):Screen
    data object Settings:Screen
    data class AccountSettings(val section:String):Screen
    data object Profile : Screen
    data object AccountSessions : Screen
    data object AccountHistory : Screen
    data class Library(val ids:List<Int>,val title:String="Моя библиотека"):Screen
    data class Catalog(val query: String = "", val page: Int = 1, val genre: Int? = null, val title: String? = null,val filters:CatalogFilter=CatalogFilter()) : Screen
    data class Section(val kind:HomeSection,val page:Int=1):Screen
    data class Franchise(val id: String, val title: String) : Screen
    data class Detail(val id: Int, val tab: Int = 0) : Screen
}
data class UiState(val screen: Screen = Screen.Welcome, val loading: Boolean = false, val error: String? = null, val releases: List<Release> = emptyList(), val detail: Release? = null, val more: Boolean = false, val home: HomeData? = null, val homeDay: Int = 1, val discovery: Discovery? = null, val discoveryLoading: Boolean = false, val section:SectionData?=null,val refreshing:Set<HomeSection> = emptySet(),val refreshErrors:Set<HomeSection> = emptySet(),val catalogPage:Int=1,val catalogTotal:Int?=null,val loadingMore:Boolean=false,val moreError:String?=null)
class TvModel : ViewModel() {
    val api = Api()
    val state = MutableStateFlow(UiState())
    private val stack = mutableListOf<Screen>()
    private var job: Job? = null
    private var homeCache: HomeData? = null
    private val sectionCache=linkedMapOf<Screen.Section,UiState>()
    private val catalogCache=linkedMapOf<Screen.Catalog,UiState>()
    private var navigationGeneration=0
    private val featureJobs=mutableMapOf<HomeSection,Job>()
    private var homeLoadedAt = 0L
    private var selectedHomeDay = 1
    fun open(screen: Screen, remember: Boolean = true) {
        if (remember && state.value.screen != Screen.Welcome && screen != state.value.screen) stack.add(state.value.screen)
        navigationGeneration++;featureJobs.values.forEach{it.cancel()};featureJobs.clear()
        job?.cancel()
        if (screen == Screen.Home && homeCache != null) {
            state.value = UiState(screen, home = homeCache, homeDay = selectedHomeDay)
            return
        }
        if(screen is Screen.Section && sectionCache[screen]!=null){state.value=sectionCache.getValue(screen);return}
        if(screen is Screen.Catalog && catalogCache[screen]!=null){state.value=catalogCache.getValue(screen);return}
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
                    is Screen.Catalog -> api.catalog(screen).let { result ->
                        UiState(screen,releases=result.releases,more=result.more,catalogPage=screen.page,catalogTotal=result.total).also{
                            if(catalogCache.size>=8)catalogCache.remove(catalogCache.keys.first())
                            catalogCache[screen]=it
                        }
                    }
                    is Screen.Section -> api.section(screen.kind,screen.page).let{data->
                        if(sectionCache.size>=16)sectionCache.remove(sectionCache.keys.first())
                        UiState(screen,section=data,more=data.more,catalogPage=screen.page).also{sectionCache[screen]=it}
                    }
                    is Screen.Library -> UiState(screen,releases=api.libraryReleases(screen.ids))
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
    fun refreshFeatures(kind:HomeSection){
        if(kind !in setOf(HomeSection.GENRES,HomeSection.FRANCHISES))return
        if(state.value.screen!=Screen.Home || kind in state.value.refreshing)return
        val generation=navigationGeneration
        state.value=state.value.copy(refreshing=state.value.refreshing+kind,refreshErrors=state.value.refreshErrors-kind)
        featureJobs[kind]=viewModelScope.launch {
            try{
                val items=api.randomFeatures(kind)
                if(navigationGeneration==generation && state.value.screen==Screen.Home){
                    val home=state.value.home?:return@launch
                    val updated=replaceHomeFeatures(home,kind,items)
                    homeCache=updated
                    state.value=state.value.copy(home=updated,refreshing=state.value.refreshing-kind)
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(navigationGeneration==generation && state.value.screen==Screen.Home)state.value=state.value.copy(refreshing=state.value.refreshing-kind,refreshErrors=state.value.refreshErrors+kind)}
            finally{if(navigationGeneration==generation)featureJobs.remove(kind)}
        }
    }
    fun homeDay(index: Int) { selectedHomeDay = index; state.value = state.value.copy(homeDay = index) }
    fun loadMoreSection(){
        val before=state.value
        val screen=before.screen as?Screen.Section?:return
        val previous=before.section?:return
        if(before.loading || before.loadingMore || !before.more)return
        val generation=navigationGeneration
        state.value=before.copy(loadingMore=true,moreError=null)
        job=viewModelScope.launch{
            try{
                val next=api.section(screen.kind,before.catalogPage+1)
                if(generation!=navigationGeneration || state.value.screen!=screen)return@launch
                val combined=mergeSectionData(previous,next)
                val merged=if(screen.kind==HomeSection.LATEST)combined.copy(more=next.more)else combined
                val updated=state.value.copy(section=merged,more=merged.more,catalogPage=before.catalogPage+1,loadingMore=false)
                sectionCache[screen]=updated;state.value=updated
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(generation==navigationGeneration && state.value.screen==screen)state.value=state.value.copy(loadingMore=false,moreError="Не удалось загрузить ещё")}
        }
    }
    fun loadMoreCatalog(){
        val before=state.value
        val screen=before.screen as?Screen.Catalog?:return
        if(before.loading || before.loadingMore || !before.more)return
        val generation=navigationGeneration
        state.value=before.copy(loadingMore=true,moreError=null)
        job=viewModelScope.launch{
            try{
                val next=api.catalog(screen,before.catalogPage+1)
                if(generation!=navigationGeneration || state.value.screen!=screen)return@launch
                val updated=state.value.copy(releases=mergeCatalogPages(before.releases,next.releases),more=next.more,catalogPage=before.catalogPage+1,catalogTotal=next.total?:before.catalogTotal,loadingMore=false)
                catalogCache[screen]=updated
                state.value=updated
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(generation==navigationGeneration && state.value.screen==screen)state.value=state.value.copy(loadingMore=false,moreError="Не удалось загрузить ещё")}
        }
    }
    fun tab(index: Int) { (state.value.screen as? Screen.Detail)?.let { state.value = state.value.copy(screen = it.copy(tab = index)) } }
    fun resetNavigation(screen:Screen){stack.clear();open(screen,false)}
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
    fun retry() { (state.value.screen as?Screen.Catalog)?.let{catalogCache.remove(it)}; (state.value.screen as?Screen.Section)?.let{sectionCache.remove(it)}; if (state.value.screen == Screen.Home) homeCache = null; open(state.value.screen, false) }
}
