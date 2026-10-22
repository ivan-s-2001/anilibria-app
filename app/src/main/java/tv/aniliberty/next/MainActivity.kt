package tv.aniliberty.next

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model by viewModels<TvModel>()
    private lateinit var list: RecyclerView
    private lateinit var adapter: PageAdapter
    private lateinit var grid: GridLayoutManager
    private val prefs by lazy { getSharedPreferences("viewing", MODE_PRIVATE) }
    private val history by lazy{ViewingHistory(prefs)}
    private var historyHydrating=false
    private var lastHistoryHydration=0L
    private var resumedOnce=false
    private val focusMemory = mutableMapOf<Screen, String>()
    private val scrollMemory=mutableMapOf<Screen,Pair<String,Int>>()
    private fun screenKey(screen:Screen)=if(screen is Screen.Detail)screen.copy(tab=0)else screen
    private fun focusItem(item:View?) {
        when(item){
            is RecyclerView->adapter.restoreShelf(item)
            is android.widget.HorizontalScrollView->{
                val line=item.getChildAt(0)as?LinearLayout
                if(current==Screen.Home && item.tag=="schedule-days")line?.getChildAt(model.state.value.homeDay+1)?.requestFocus()
                else line?.getFocusables(View.FOCUS_FORWARD)?.firstOrNull()?.requestFocus()
            }
            null->Unit
            else->if(item.isFocusable)item.requestFocus()else if(item is ViewGroup)item.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
        }
    }
    private var current: Screen = Screen.Welcome
    private var enterContent = false
    private var enterDay = false
    private lateinit var root:android.widget.FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var menu: LinearLayout
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private var menuOpen=false
    private var contentFocus: View?=null
    private var selectedSection=0

    private fun buildMenu() {
        menu.removeAllViews()
        menu.layoutParams=if(::root.isInitialized && menu.parent===root)android.widget.FrameLayout.LayoutParams(dp(if(menuOpen)196 else 60),-1)else LinearLayout.LayoutParams(dp(if(menuOpen)196 else 60),-1)
        menu.addView(LinearLayout(this).apply{
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(this@MainActivity).apply{setImageResource(R.drawable.ic_aniliberty);contentDescription="AniLiberty"},LinearLayout.LayoutParams(dp(36),dp(36)).apply{marginStart=dp(8);marginEnd=dp(10)})
            if(menuOpen)addView(android.widget.TextView(this@MainActivity).apply{label("AniLiberty",18f,WHITE,true)})
        },LinearLayout.LayoutParams(-1,dp(56)))
        val titles=listOf("Поиск","Главная","Релизы","Моё")
        val icons=listOf(R.drawable.ic_search,R.drawable.ic_home,R.drawable.ic_catalog,R.drawable.ic_library)
        titles.forEachIndexed { index,title ->
            val route=listOf(2,0,1,3)[index]
            menu.addView(action(menu,if(menuOpen)title else "",icon=icons[index]){
                selectedSection=route;closeMenu(false);navigate(route)
            }.apply {
                contentDescription=title
                if(route==selectedSection){
                    setTextColor(WHITE)
                    val marker=android.graphics.drawable.GradientDrawable().apply{setColor(RED)}
                    background=android.graphics.drawable.LayerDrawable(arrayOf(background!!,marker)).apply{setLayerWidth(1,dp(2));setLayerGravity(1,android.view.Gravity.START)}
                }
                setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.action==KeyEvent.ACTION_UP)closeMenu();true}else false}
            },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(6)})
        }
    }
    private fun homeMenuLayout(){
        if(current!=Screen.Welcome && menu.parent===shell){
            shell.removeView(menu);shell.setPadding(dp(60),0,0,0)
            root.addView(menu,android.widget.FrameLayout.LayoutParams(dp(if(menuOpen)196 else 60),-1))
        }else if(current==Screen.Welcome && menu.parent===root){
            root.removeView(menu);shell.setPadding(0,0,0,0)
            shell.addView(menu,0,LinearLayout.LayoutParams(dp(if(menuOpen)196 else 60),-1))
        }
    }
    private fun openMenu(){
        if(current==Screen.Welcome)return
        homeTransition++;homeFocusTarget=null;catalogToolsPending=false;list.stopScroll()
        contentFocus=list.findFocus();rememberFocus();menuOpen=true;buildMenu()
        menu.getChildAt(listOf(2,0,1,3).indexOf(selectedSection).coerceAtLeast(0)+1)?.requestFocus()
    }
    private fun closeMenu(restore:Boolean=true){menuOpen=false;buildMenu();if(restore){if(contentFocus?.isAttachedToWindow==true)contentFocus?.requestFocus()else list.requestFocus()}}
    private var homeFocusTarget:Int?=null
    private var lastHomeMove=0L
    private var homeTransition=0
    private fun moveHomeFocus(direction:Int):Boolean{
        if(homeFocusTarget!=null)return true
        val focus=list.findFocus()?:return false
        val outer=list.findContainingItemView(focus)?:return false
        val index=list.getChildAdapterPosition(outer)
        if(outer is RecyclerView)adapter.rememberShelf(outer)
        val target=homeFocusDestination(adapter.rows,index,direction)?:return true
        val key=adapter.rows[target].key
        val header=if(adapter.rows[target].kind==Kind.SHELF)homeSectionHeader(adapter.rows,target)?:target else target
        val token=++homeTransition
        homeFocusTarget=target
        fun finish(){
            if(token!=homeTransition)return
            homeFocusTarget=null
            if(current!=Screen.Home || menuOpen)return
            val resolved=adapter.rows.indexOfFirst{it.key==key}
            if(resolved>=0)focusItem(list.findViewHolderForAdapterPosition(resolved)?.itemView)
        }
        val heading=grid.findViewByPosition(header)
        val card=grid.findViewByPosition(target)
        val delta=if(heading!=null && card!=null)homeScrollDelta(heading.top,card.bottom,list.paddingTop+dp(6),list.height-list.paddingBottom)else null
        if(delta==0){finish();return true}
        // Only this transition owns the vertical scroll. Focus changes once it ends.
        list.stopScroll()
        val listener=object:RecyclerView.OnScrollListener(){
            override fun onScrollStateChanged(view:RecyclerView,state:Int){
                if(state!=RecyclerView.SCROLL_STATE_IDLE)return
                list.removeOnScrollListener(this)
                finish()
            }
        }
        list.addOnScrollListener(listener)
        if(delta!=null)list.smoothScrollBy(0,delta,android.view.animation.DecelerateInterpolator(),180)
        else grid.startSmoothScroll(object:androidx.recyclerview.widget.LinearSmoothScroller(this){
            override fun getVerticalSnapPreference()=SNAP_TO_START
            override fun calculateTimeForDeceleration(dx:Int)=180
        }.apply{targetPosition=header})
        return true
    }
    private var catalogToolsPending=false
    private fun focusCatalogTools(){
        if(catalogToolsPending)return
        val screen=current
        val target=adapter.rows.indexOfFirst{it.kind==Kind.CATALOG_BAR}
        if(target<0)return
        val tools=list.findViewHolderForAdapterPosition(target)?.itemView
        if(tools!=null && tools.top>=list.paddingTop){focusItem(tools);return}
        catalogToolsPending=true
        list.stopScroll()
        val listener=object:RecyclerView.OnScrollListener(){
            override fun onScrollStateChanged(view:RecyclerView,state:Int){
                if(state!=RecyclerView.SCROLL_STATE_IDLE)return
                list.removeOnScrollListener(this)
                val active=catalogToolsPending;catalogToolsPending=false
                if(active && current==screen && !menuOpen)focusItem(list.findViewHolderForAdapterPosition(target)?.itemView)
            }
        }
        list.addOnScrollListener(listener)
        grid.startSmoothScroll(object:androidx.recyclerview.widget.LinearSmoothScroller(this){
            override fun getVerticalSnapPreference()=SNAP_TO_START
            override fun calculateTimeForDeceleration(dx:Int)=160
        }.apply{targetPosition=target})
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if((current is Screen.Library || current is Screen.Franchise) && !menuOpen){
            val position=list.findFocus()?.let{list.findContainingItemView(it)}?.let{list.getChildAdapterPosition(it)}?:-1
            if(adapter.rows.getOrNull(position)?.kind==Kind.POSTER){
                val first=adapter.rows.indexOfFirst{it.kind==Kind.POSTER};val column=(position-first)%4
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && (column==3 || adapter.rows.getOrNull(position+1)?.kind!=Kind.POSTER))return true
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && column==0){if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)openMenu();return true}
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && position-first<4)return true
            }
        }
        if(current is Screen.Section && !menuOpen){
            val position=list.findFocus()?.let{list.findContainingItemView(it)}?.let{list.getChildAdapterPosition(it)}?:-1
            val kinds=listOf(Kind.POSTER,Kind.GENRE,Kind.FRANCHISE,Kind.FEATURE)
            if(adapter.rows.getOrNull(position)?.kind in kinds){
                val columns=if((current as?Screen.Section)?.kind==HomeSection.FRANCHISES)2 else 4
                val groupHeader=(0 until position).lastOrNull{adapter.rows[it].kind==Kind.HEADING}?:-1
                val first=(groupHeader+1..position).firstOrNull{adapter.rows[it].kind in kinds}?:position
                val column=(position-first)%columns
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && (column==columns-1 || adapter.rows.getOrNull(position+1)?.kind !in kinds))return true
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && column==0){if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)openMenu();return true}
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && position-first<columns && adapter.rows.take(groupHeader.coerceAtLeast(0)).none{it.kind in kinds}){
                    if(event.action==KeyEvent.ACTION_DOWN){val tools=adapter.rows.indexOfFirst{it.key=="section-tools"};grid.scrollToPositionWithOffset(tools,list.dp(12));list.post{focusItem(list.findViewHolderForAdapterPosition(tools)?.itemView)}}
                    return true
                }
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && position>=adapter.itemCount-9 && model.state.value.more && model.state.value.moreError==null){
                    if(event.action==KeyEvent.ACTION_DOWN)model.loadMoreSection()
                    if(adapter.rows.getOrNull(position+columns)?.kind !in kinds)return true
                }
            }
        }
        if(current is Screen.Catalog && !menuOpen){
            if(catalogToolsPending && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT))return true
            val focused=list.findFocus()?.let{list.findContainingItemView(it)}
            val position=focused?.let{list.getChildAdapterPosition(it)}?:-1
            if(adapter.rows.getOrNull(position)?.kind==Kind.POSTER){
                val first=adapter.rows.indexOfFirst{it.kind==Kind.POSTER}
                val column=(position-first)%4
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && (column==3 || adapter.rows.getOrNull(position+1)?.kind!=Kind.POSTER))return true
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && column==0){
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)openMenu()
                    return true
                }
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && model.state.value.more && position>=adapter.rows.size-10 && model.state.value.moreError==null){
                    if(event.action==KeyEvent.ACTION_DOWN)model.loadMoreCatalog()
                    if(adapter.rows.getOrNull(position+4)?.kind!=Kind.POSTER)return true
                }
                if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && position-first<4){
                    if(event.action==KeyEvent.ACTION_DOWN){
                        focusCatalogTools()
                    }
                    return true
                }
            }
        }
        if(current==Screen.Home && !menuOpen){
            val focus=list.findFocus()
            val outer=focus?.let{list.findContainingItemView(it)}
            if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP || event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN){
                if(focus!=null){
                    if(event.action==KeyEvent.ACTION_DOWN){
                        val now=android.os.SystemClock.uptimeMillis()
                        if(event.repeatCount==0 || now-lastHomeMove>=140){
                            lastHomeMove=now
                            moveHomeFocus(if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1)
                        }
                    }
                    return true
                }
            }
            if(outer is RecyclerView && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){
                val child=focus?.let{outer.findContainingItemView(it)}
                val index=child?.let{outer.getChildAdapterPosition(it)}?:-1
                if(index>=0 && event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && index==(outer.adapter?.itemCount?:0)-1)return true
                if(index==0 && event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)openMenu()
                    return true
                }
            }
        }
        if(!menuOpen && current!=Screen.Welcome && event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){
            val focus=currentFocus
            val next=focus?.focusSearch(View.FOCUS_LEFT)
            if(focus!=null && (next==null || isInMenu(next))){if(event.action==KeyEvent.ACTION_DOWN)openMenu();return true}
        }
        return super.dispatchKeyEvent(event)
    }
    private fun isInMenu(view:View):Boolean {var node:android.view.ViewParent?=view.parent;while(node!=null){if(node===menu)return true;node=node.parent};return false}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = object:RecyclerView(this){
            override fun requestChildRectangleOnScreen(child:View,rect:android.graphics.Rect,immediate:Boolean):Boolean=
                if(current==Screen.Home)false else super.requestChildRectangleOnScreen(child,rect,immediate)
        }.apply {
            setBackgroundColor(0xFF121212.toInt()); setPadding(dp(32),dp(8),dp(32),dp(24));clipToPadding=false;itemAnimator=null
        }
        grid=object:GridLayoutManager(this,15){
            override fun onRequestChildFocus(parent:RecyclerView,state:RecyclerView.State,child:View,focused:View?):Boolean=
                current==Screen.Home || super.onRequestChildFocus(parent,state,child,focused)
        };list.layoutManager=grid
        adapter=PageAdapter(::navigate,::selectTab,::play,::favorite,{rememberFocus();model.open(Screen.Detail(it.id))},
            { id -> prefs.getLong("position:$id",0) to prefs.getLong("duration:$id",0) },
            { id -> prefs.getString("last:$id",null) },::returnToTabs, { index -> enterDay=true;val previous=model.state.value;model.homeDay(index);if(previous==model.state.value)render(model.state.value) },::quickActions,enterTab=::enterSelectedTab)
        grid.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=when(adapter.rows.getOrNull(position)?.kind){Kind.POSTER->if(current is Screen.Catalog || current is Screen.Section || current is Screen.Library || current is Screen.Franchise)5 else 3;Kind.GENRE->if(current is Screen.Section)5 else 3;Kind.FRANCHISE->if((current as?Screen.Section)?.kind==HomeSection.FRANCHISES)10 else 5;Kind.EPISODE,Kind.FEATURE->5;else->grid.spanCount}}
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
                val state=model.state.value
                if(state.moreError==null && dy>0 && grid.findLastVisibleItemPosition()>=adapter.itemCount-9){
                    if(state.screen is Screen.Catalog)model.loadMoreCatalog()
                    else if(state.screen is Screen.Section)model.loadMoreSection()
                }
            }
        })
        list.viewTreeObserver.addOnGlobalFocusChangeListener{_,focus->
            if((current is Screen.Catalog || current is Screen.Section) && !menuOpen && focus!=null){
                val item=list.findContainingItemView(focus)
                val position=item?.let{list.getChildAdapterPosition(it)}?:-1
                val state=model.state.value
                if(item is RecyclerView && adapter.rows.getOrNull(position)?.key?.startsWith("period-shelf:")==true && state.more && state.moreError==null){
                    val nestedItem=item.findContainingItemView(focus)
                    val nestedPosition=nestedItem?.let{item.getChildAdapterPosition(it)}?:-1
                    if(nestedPosition>=((item.adapter?.itemCount?:0)-5).coerceAtLeast(0))list.post{if(model.state.value.screen==state.screen)model.loadMoreSection()}
                }
                if(position>=0 && adapter.rows.getOrNull(position)?.kind in listOf(Kind.POSTER,Kind.GENRE,Kind.FRANCHISE,Kind.FEATURE) && position>=adapter.itemCount-9 && state.more && state.moreError==null){
                    list.post{if(model.state.value.screen==state.screen && model.state.value.moreError==null){if(state.screen is Screen.Section)model.loadMoreSection()else model.loadMoreCatalog()}}
                }
            }
        }
        list.adapter=adapter
        shell=LinearLayout(this).apply{setBackgroundColor(0xFF121212.toInt())}
        menu=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(12),dp(4),0);setBackgroundColor(0xFF212121.toInt())}
        shell.addView(menu,LinearLayout.LayoutParams(dp(60),-1));shell.addView(list,LinearLayout.LayoutParams(0,-1,1f));buildMenu()
        root=android.widget.FrameLayout(this).apply{addView(shell,android.widget.FrameLayout.LayoutParams(-1,-1))}
        setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(catalogSearchEditing){finishCatalogSearch();return};if(menuOpen){if(current==Screen.Home){finish()}else closeMenu();return}
            rememberFocus()
            if(current==Screen.Home){if(list.computeVerticalScrollOffset()>0){list.smoothScrollToPosition(0)}else openMenu()}
            else if(!model.back())finish()}})
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{render(it)}} }
        if(savedInstanceState==null && prefs.getBoolean("welcomed",false))model.open(Screen.Home,false)
    }
    override fun onResume(){
        super.onResume()
        if(resumedOnce && ::adapter.isInitialized){rememberFocus();render(model.state.value)}
        resumedOnce=true
        hydrateHistory()
    }
    private fun hydrateHistory(){
        if(historyHydrating || System.currentTimeMillis()-lastHistoryHydration<60000)return
        historyHydrating=true;lastHistoryHydration=System.currentTimeMillis()
        lifecycleScope.launch {
            try{
                val known=history.latestWatched().associateBy{it.releaseId}
                val legacy=prefs.all.keys.filter{it.startsWith("last:")}.mapNotNull{it.substringAfter(":").toIntOrNull()}.filter{it !in known}
                val candidates=(history.hiddenReleaseIds()+known.values.filter{it.completed}.map{it.releaseId}+legacy).distinct().take(16)
                for(id in candidates){
                    try{
                        val item=model.api.release(id,refresh=true)
                        val saved=known[id]
                        val uuid=saved?.episodeId?:prefs.getString("last:$id",null)?:continue
                        val episode=item.episodes.firstOrNull{it.id==uuid}?:continue
                        if(history.latestWatched().firstOrNull{it.releaseId==id}!=saved)continue
                        history.save(item,episode,saved?.position?:prefs.getLong("position:$uuid",0),saved?.duration?:prefs.getLong("duration:$uuid",episode.duration*1000),saved?.updatedAt?:0)
                    }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){ /* Existing local cards remain usable. */ }
                }
                if(current==Screen.Home && candidates.isNotEmpty())render(model.state.value)
            }finally{historyHydrating=false}
        }
    }
    private fun playContinue(item:ContinueEpisode){
        rememberFocus()
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("release",item.releaseId).putExtra("episode",item.episodeId))
    }
    private fun rememberFocus(){
        list.findFocus()?.let{v->list.findContainingItemView(v)?.tag as?String}?.let{focusMemory[screenKey(current)]=it}
        val first=grid.findFirstVisibleItemPosition()
        adapter.rows.getOrNull(first)?.key?.let{scrollMemory[screenKey(current)]=it to (grid.findViewByPosition(first)?.top?:list.paddingTop)}
    }
    private fun navigate(index:Int){
        rememberFocus()
        when(index){0->model.open(Screen.Home);1->model.open(Screen.Catalog());2->search();3->library()}
    }
    private fun search(){
        val screen=(model.state.value.screen as?Screen.Catalog)?:Screen.Catalog()
        if(model.state.value.screen!=screen)model.open(screen)
        catalogSearch(screen)
    }
    private var enterCatalogResults=false
    private var catalogReferences:Map<String,List<CatalogChoice>> = emptyMap()
    private fun applyCatalog(screen:Screen.Catalog){
        rememberFocus()
        val next=screen.copy(page=1)
        if(next==model.state.value.screen && !model.state.value.loading){
            enterCatalogResults=false
            val index=adapter.rows.indexOfFirst{it.kind==Kind.POSTER}
            if(index>=0){
                list.doOnNextLayout{focusItem(list.findViewHolderForAdapterPosition(index)?.itemView)}
                grid.scrollToPositionWithOffset(index,list.dp(96))
            }
            return
        }
        enterCatalogResults=true;model.open(next,false)
    }
    private val sectionQueries=mutableMapOf<HomeSection,String>()
    private var catalogSearchEditing=false
    private var catalogSearchDraft=""
    private var catalogSearchJob:kotlinx.coroutines.Job?=null
    private fun catalogSearch(screen:Screen.Catalog){
        catalogSearchEditing=true;catalogSearchDraft=screen.query
        render(model.state.value)
    }
    private fun liveCatalogSearch(query:String){
        catalogSearchDraft=query
        catalogSearchJob?.cancel()
        catalogSearchJob=lifecycleScope.launch{
            kotlinx.coroutines.delay(350)
            val active=model.state.value.screen
            if(active is Screen.Section){sectionQueries[active.kind]=query;render(model.state.value);return@launch}
            val screen=active as?Screen.Catalog?:return@launch
            val next=screen.copy(query=query.trim(),page=1)
            if(next!=screen)model.open(next,false)
        }
    }
    private fun finishCatalogSearch(){
        catalogSearchJob?.cancel()
        catalogSearchEditing=false
        (getSystemService(INPUT_METHOD_SERVICE)as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(list.windowToken,0)
        val active=model.state.value.screen
        if(active is Screen.Section){sectionQueries[active.kind]=catalogSearchDraft;enterCatalogResults=true;render(model.state.value);return}
        val screen=active as?Screen.Catalog?:return
        enterCatalogResults=true
        val next=screen.copy(query=catalogSearchDraft.trim(),page=1)
        if(next==screen)render(model.state.value)else model.open(next,false)
    }
    private fun withCatalogReferences(screen:Screen.Catalog,ready:(Map<String,List<CatalogChoice>>)->Unit){
        if(catalogReferences.isNotEmpty()){ready(catalogReferences);return}
        val panel=TvSidePanel(this,"Каталог")
        panel.message("Загрузка параметров…")
        var task:kotlinx.coroutines.Job?=null
        panel.addAction("Отмена"){task?.cancel();panel.dismiss()}
        panel.setOnCancelListener{task?.cancel()}
        panel.show()
        task=lifecycleScope.launch{
            try{
                val data=model.api.catalogReferences();catalogReferences=data;panel.dismiss()
                if(model.state.value.screen==screen)ready(data)
            }catch(e:kotlinx.coroutines.CancellationException){panel.dismiss();throw e}
            catch(_:Exception){
                if(model.state.value.screen==screen){
                    panel.message("Не удалось загрузить параметры")
                    panel.footer.removeAllViews()
                    panel.addAction("Повторить",true){panel.dismiss();withCatalogReferences(screen,ready)}
                    panel.addAction("Закрыть"){panel.dismiss()}
                    panel.footer.getChildAt(0)?.requestFocus()
                }else panel.dismiss()
            }
        }
    }
    private fun catalogFilters(screen:Screen.Catalog)=withCatalogReferences(screen){refs->
        CatalogFilterPanel(this,screen,refs){filters->applyCatalog(screen.copy(filters=filters,genre=null,title=null))}.show()
    }
    private fun catalogSorting(screen:Screen.Catalog)=withCatalogReferences(screen){refs->
        val panel=TvSidePanel(this,"Сортировка")
        refs["sorting"].orEmpty().forEach{choice->
            panel.addChoice(choice.title,choice.value==screen.filters.sorting){
                panel.dismiss();applyCatalog(screen.copy(filters=screen.filters.copy(sorting=choice.value)))
            }
        }
        panel.addAction("Отмена"){panel.dismiss()}
        panel.show()
    }
    private fun catalogSummary(screen:Screen.Catalog):String{
        val f=screen.filters
        return listOf(
            screen.query.takeIf{it.isNotBlank()}?.let{"Поиск: $it"}.orEmpty(),
            if(screen.genre!=null)screen.title?:"Выбранный жанр"else if(f.genres.isNotEmpty())"Жанры: ${f.genres.size}"else "",
            if(f.types.isNotEmpty())"Формат: ${f.types.size}"else "",
            if(f.fromYear.isNotBlank()||f.toYear.isNotBlank())"${f.fromYear.ifBlank{"…"}}–${f.toYear.ifBlank{"…"}}"else "",
            if(f.seasons.isNotEmpty())"Сезоны: ${f.seasons.size}"else "",
            if(f.publish.isNotEmpty())"Статус выхода"else "",
            if(f.production.isNotEmpty())"Статус озвучки"else "",
            if(f.ages.isNotEmpty())"Возраст"else ""
        ).filter(String::isNotBlank).joinToString(" · ")
    }
    private fun library(){
        val items=JSONArray(prefs.getString("library","[]"))
        val ids=(0 until items.length()).mapNotNull{items.optJSONObject(it)?.optInt("id")?.takeIf{id->id>0}}
        rememberFocus();model.open(Screen.Library(ids))
    }
    private fun isFavorite(id:Int):Boolean {
        val items=JSONArray(prefs.getString("library","[]"))
        return (0 until items.length()).any{items.optJSONObject(it)?.optInt("id")==id}
    }
    private fun favorite(release:Release){
        val old=JSONArray(prefs.getString("library","[]"));val next=JSONArray();var found=false
        for(i in 0 until old.length()){val item=old.getJSONObject(i);if(item.getInt("id")==release.id)found=true else next.put(item)}
        if(!found)next.put(JSONObject().put("id",release.id).put("title",release.title))
        prefs.edit().putString("library",next.toString()).apply()
        Toast.makeText(this,if(found)"Удалено из библиотеки"else"Добавлено в библиотеку",Toast.LENGTH_SHORT).show()
        if(current is Screen.Library){
            val ids=(0 until next.length()).map{next.getJSONObject(it).getInt("id")}
            model.open(Screen.Library(ids),false)
        }else if(current is Screen.Detail)render(model.state.value)
    }
    private fun continueActions(item:ContinueEpisode){
        val panel=TvSidePanel(this,item.releaseTitle)
        panel.message(listOf(item.episodeTitle,if(item.nextUp)"Следующая серия"else "Прогресс просмотра сохранён").joinToString("\n"))
        panel.addChoice(if(item.nextUp)"Смотреть следующую серию"else "Продолжить",icon=R.drawable.ic_play){
            panel.dismiss();playContinue(item)
        }
        panel.addChoice("Открыть тайтл",icon=R.drawable.ic_catalog){
            panel.dismiss();rememberFocus();model.open(Screen.Detail(item.releaseId))
        }
        panel.addChoice("Убрать из продолжения",icon=R.drawable.ic_close){
            panel.dismiss();history.dismiss(item.releaseId)
            adapter.resetShelf("shelf:continue",true)
            render(model.state.value)
        }
        panel.body.addView(android.widget.TextView(this).apply{
            label("Появится снова при открытии тайтла или выходе новой серии.",13f,MUTED)
            setPadding(0,dp(12),0,0)
        })
        panel.addAction("Отмена"){panel.dismiss()}
        panel.show()
    }
    private fun quickActions(item:Release){
        val resume=prefs.getString("last:${item.id}",null)!=null
        val library=JSONArray(prefs.getString("library","[]"))
        val added=(0 until library.length()).any{library.getJSONObject(it).getInt("id")==item.id}
        val labels=arrayOf(if(resume)"Продолжить просмотр"else"Смотреть",if(added)"Убрать из библиотеки"else"В библиотеку","Подробнее")
        fun choose(choice:Int){
            when(choice){
                0->lifecycleScope.launch {
                    try {
                        val release=model.api.release(item.id)
                        val last=prefs.getString("last:${release.id}",null)
                        val episode=release.episodes.firstOrNull{it.id==last}?:release.episodes.firstOrNull()
                        if(release.blocked||episode==null||episode.streams.isEmpty())Toast.makeText(this@MainActivity,"Просмотр пока недоступен",Toast.LENGTH_LONG).show()
                        else {rememberFocus();startActivity(Intent(this@MainActivity,PlayerActivity::class.java).putExtra("release",release.id).putExtra("episode",episode.id))}
                    } catch(e:kotlinx.coroutines.CancellationException){throw e} catch(_:Exception){Toast.makeText(this@MainActivity,"Не удалось загрузить тайтл",Toast.LENGTH_LONG).show()}
                }
                1->favorite(item)
                2->{rememberFocus();model.open(Screen.Detail(item.id))}
            }
        }
        run {
            val panel=TvSidePanel(this,item.title)
            labels.forEachIndexed{index,label->panel.addChoice(label){panel.dismiss();choose(index)}}
            panel.addAction("Закрыть"){panel.dismiss()}
            panel.show()
        }
    }
    private fun play(episode:Episode){
        val release=model.state.value.detail?:return
        if(release.blocked){Toast.makeText(this,"Просмотр этого тайтла ограничен",Toast.LENGTH_LONG).show();return}
        if(episode.streams.isEmpty()){Toast.makeText(this,"Для этой серии нет доступного HLS-потока",Toast.LENGTH_LONG).show();return}
        rememberFocus()
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("release",release.id).putExtra("episode",episode.id))
    }
    private fun selectTab(index:Int){enterContent=false;val previous=model.state.value;model.tab(index);if(previous==model.state.value)render(model.state.value)}
    private fun enterSelectedTab(index:Int){enterContent=true;val previous=model.state.value;model.tab(index);if(previous==model.state.value)render(model.state.value)}
    private fun returnToTabs(){
        if(adapter.tabStart<0)return
        val position=adapter.tabStart
        list.doOnNextLayout{
            val scroll=list.findViewHolderForAdapterPosition(position)?.itemView as? android.widget.HorizontalScrollView
            val line=scroll?.getChildAt(0) as? LinearLayout
            val selected=(model.state.value.screen as?Screen.Detail)?.tab?:0
            (line?.getChildAt(selected)as?LinearLayout)?.getChildAt(0)?.requestFocus()
        }
        grid.scrollToPositionWithOffset(position,list.dp(12))
    }
    private fun openLink(url:String?){
        if(url==null)return
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:ActivityNotFoundException){Toast.makeText(this,"Нет приложения для открытия ссылки",Toast.LENGTH_LONG).show()}
    }
    private fun showDetailMore(release:Release){
        val panel=TvSidePanel(this,"Дополнительно")
        panel.addChoice("Открыть страницу на сайте",icon=R.drawable.ic_arrow_right){panel.dismiss();openLink("https://www.aniliberty.top/anime/releases/release/${release.alias}/episodes")}
        panel.addChoice("Торренты",icon=R.drawable.ic_download){panel.dismiss();showTorrents(release.id)}
        panel.addAction("Закрыть"){panel.dismiss()};panel.show()
    }
    private fun showTorrents(releaseId:Int){
        val panel=TvSidePanel(this,"Торренты")
        panel.message("Загрузка раздач…")
        var task:kotlinx.coroutines.Job?=null
        panel.addAction("Закрыть"){task?.cancel();panel.dismiss()}
        panel.setOnCancelListener{task?.cancel()};panel.show()
        task=lifecycleScope.launch{
            try{
                val items=model.api.releaseTorrents(releaseId)
                panel.body.removeAllViews()
                if(items.isEmpty())panel.message("Раздачи пока недоступны")
                else items.forEach{item->panel.addChoice(listOf(item.title,item.description).filter{it.isNotBlank()}.joinToString("\n"),icon=R.drawable.ic_download){openLink(item.magnet)}}
                panel.body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){panel.message("Не удалось загрузить раздачи. Проверьте подключение.")}
        }
    }
    private fun openSection(kind:HomeSection){rememberFocus();model.open(Screen.Section(kind))}
    private fun releaseCards(items:List<Release>,key:String)=items.distinctBy{it.id}.map{Row("$key:release:${it.id}",Kind.POSTER,release=it,selected=if(key.startsWith("latest",true)||key.startsWith("schedule",true))1 else 0)}
    private fun watchOrderCards(items:List<Release>,key:String)=items.distinctBy{it.id}.map{item->
        Row("$key:${item.id}",Kind.POSTER,release=item,selected=2)
    }
    private val franchisePreviewGate=kotlinx.coroutines.sync.Semaphore(2)
    private val franchisePreviews=FranchisePreviewCache()
    private val franchisePreviewErrors=mutableSetOf<String>()
    private val franchisePreviewJobs=mutableMapOf<String,kotlinx.coroutines.Job>()
    private fun loadFranchisePreview(id:String){
        if(franchisePreviews.containsKey(id) || id in franchisePreviewErrors || franchisePreviewJobs[id]?.isActive==true)return
        franchisePreviewJobs[id]=lifecycleScope.launch{
            try{val parts=franchisePreviewGate.withPermit{model.api.franchise(id)};franchisePreviews[id]=parts.map{it.title};franchisePreviewErrors.remove(id)}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){franchisePreviewErrors.add(id)}
            franchisePreviewJobs.remove(id)
            val screen=model.state.value.screen
            if(screen==Screen.Home || (screen is Screen.Section && screen.kind==HomeSection.FRANCHISES))render(model.state.value)
        }
    }
    private fun featureCards(items:List<Feature>,kind:HomeSection)=items.map{feature->
        Row("${kind.name}:${feature.id}",if(kind==HomeSection.GENRES)Kind.GENRE else Kind.FRANCHISE,feature.title,feature.subtitle,image=feature.image,
            composition=franchisePreviews[feature.id],selected=if(feature.id in franchisePreviewErrors)-1 else 0,
            focusLoad=if(kind==HomeSection.FRANCHISES)({loadFranchisePreview(feature.id)})else null,
            click={
            rememberFocus()
            if(kind==HomeSection.FRANCHISES)model.open(Screen.Franchise(feature.id,feature.title))
            else model.open(Screen.Catalog(genre=feature.id.toIntOrNull(),title=feature.title))
        })
    }
    private fun videoCards(items:List<HomeVideo>)=items.map{video->Row("video:${video.id}",Kind.FEATURE,video.title,video.subtitle,image=video.image,click={if(video.url.startsWith("https://"))openLink(video.url)else Toast.makeText(this,"У этого ролика нет доступной ссылки",Toast.LENGTH_SHORT).show()})}
    private val pendingShelfRefresh=mutableSetOf<HomeSection>()
    private fun homeShelf(kind:HomeSection,items:List<Row>,wide:Boolean=false,key:String=kind.name):Row {
        val style=when(kind){HomeSection.LATEST,HomeSection.SCHEDULE->4;HomeSection.GENRES->2;HomeSection.FRANCHISES->3;else->if(wide)1 else 0}
        val state=model.state.value
        val cards=if(kind==HomeSection.GENRES||kind==HomeSection.FRANCHISES)
            discoveryShelfCards(key,kind.title,items,style,kind in state.refreshing,kind in state.refreshErrors,{pendingShelfRefresh.add(kind);model.refreshFeatures(kind)},{openSection(kind)})
        else shelfCards(key,kind.title,items,wide,style){openSection(kind)}
        return Row("shelf:$key",Kind.SHELF,selected=style,cards=cards)
    }
    private var pendingRender:UiState?=null
    private var renderPosted=false
    private fun render(state:UiState){
        if(list.isComputingLayout){
            pendingRender=state
            if(!renderPosted){
                renderPosted=true
                list.post{
                    renderPosted=false
                    val latest=pendingRender;pendingRender=null
                    if(latest!=null && !isFinishing && !isDestroyed)render(latest)
                }
            }
            return
        }
        pendingRender=null
        if(state.screen !is Screen.Catalog && state.screen !is Screen.Section){catalogSearchEditing=false;catalogSearchJob?.cancel()}
        (state.screen as?Screen.Detail)?.let{history.restore(it.id)}
        if(current==state.screen && !state.loading && adapter.rows.none{it.key=="loading"})rememberFocus()
        val sameCatalog=current is Screen.Catalog && (current==state.screen || catalogSearchEditing) && adapter.rows.any{it.kind==Kind.CATALOG_BAR}
        val sameSection=current is Screen.Section && current==state.screen && adapter.rows.any{it.key=="section-heading"}
        val sameHome=current==Screen.Home && state.screen==Screen.Home && adapter.rows.any{it.kind==Kind.SHELF}
        val retainedFocus = if(current==state.screen) list.findFocus()?.let { list.findContainingItemView(it)?.tag as? String } else null
        current=state.screen
        val section=when(current){Screen.Home->0;is Screen.Catalog,is Screen.Section,is Screen.Franchise->1;is Screen.Library->3;else->selectedSection}
        if(section!=selectedSection){selectedSection=section;buildMenu()}
        homeMenuLayout()
        val spans=if(current is Screen.Catalog || current is Screen.Section || current is Screen.Library || current is Screen.Franchise)20 else 15
        if(grid.spanCount!=spans)grid.spanCount=spans
        menu.visibility=if(current==Screen.Welcome)View.GONE else View.VISIBLE
        val rows=mutableListOf<Row>()
        if(state.screen==Screen.Welcome){
            rows+=Row("welcome",Kind.HEADING,"AniLiberty","Так звучит аниме")
            rows+=Row("intro",Kind.TEXT,"Аниме на большом экране","Управление пультом. Просмотр без обязательного входа.")
            rows+=Row("start",Kind.ACTION,"Начать просмотр",click={prefs.edit().putBoolean("welcomed",true).apply();model.open(Screen.Home,false)})
        }else{
            
            if(state.loading && state.screen !is Screen.Catalog && !(state.screen==Screen.Home && state.home!=null))rows+=Row("loading",Kind.TEXT,"Загрузка…")
            else if(state.error!=null && state.screen !is Screen.Catalog)rows+=Row("retry",Kind.ACTION,"Не удалось загрузить",state.error,click=model::retry)
            else when(val screen=state.screen){
                Screen.Home->{
                    val home=state.home?:return
                    home.promotions?.firstOrNull()?.let { promotion ->
                        rows+=Row("promotion:${promotion.id}",Kind.SPOTLIGHT,release=promotion.release,image=promotion.image)
                    }
                    rows+=Row("latest-heading",Kind.HEADING,"Новые эпизоды",selected=1,click={
                        if(!model.state.value.loading){rememberFocus();lastHistoryHydration=0;hydrateHistory();model.retry()}
                    })
                    rows+=homeShelf(HomeSection.LATEST,releaseCards(home.latest,"latest"))
                    val unfinished=history.items()
                    if(unfinished.isNotEmpty()){
                        rows+=Row("continue-heading",Kind.HEADING,"Продолжить просмотр",selected=1)
                        val cards=unfinished.map{item->
                            val status=when{item.nextUp->"Следующая серия";item.duration>0->"Осталось ${(item.remaining+59999)/60000} мин";else->"Продолжить"}
                            Row("continue:${item.releaseId}:${item.episodeId}",Kind.CONTINUE,item.releaseTitle,"${item.episodeTitle}\n$status",image=item.preview,watching=item,click={playContinue(item)},longClick={
                                continueActions(item)
                            })
                        }
                        rows+=Row("shelf:continue",Kind.SHELF,selected=5,cards=cards)
                    }
                    rows+=Row("schedule-days",Kind.DAYS,"Расписание релизов",selected=state.homeDay)
                    val schedule=home.schedule?.getOrNull(state.homeDay)
                    if(schedule==null)rows+=Row("schedule-retry",Kind.ACTION,"Не удалось загрузить расписание · Повторить",click=model::retry)
                    else rows+=homeShelf(HomeSection.SCHEDULE,releaseCards(schedule,"schedule:${state.homeDay}"),key="schedule:${state.homeDay}")
                    rows+=Row("franchises-heading",Kind.HEADING,"Франшизы",selected=1)
                    rows+=homeShelf(HomeSection.FRANCHISES,featureCards(home.franchises.orEmpty(),HomeSection.FRANCHISES),true)
                    rows+=Row("genres-heading",Kind.HEADING,"Жанры",selected=1)
                    rows+=homeShelf(HomeSection.GENRES,featureCards(home.genres.orEmpty(),HomeSection.GENRES),true)
                    rows+=Row("videos-heading",Kind.HEADING,"Видео от AniLiberty",selected=1)
                    if(home.videos==null)rows+=Row("videos-retry",Kind.ACTION,"Не удалось загрузить видео · Повторить",click=model::retry)
                    else rows+=homeShelf(HomeSection.VIDEOS,videoCards(home.videos),true)
                }
                is Screen.Section->{
                    val data=state.section?:return
                    val count=data.releases.size+data.features.size+data.videos.size
                    rows+=Row("section-heading",Kind.HEADING,screen.kind.title,selected=1,click={rememberFocus();model.retry()})
                    val query=sectionQueries[screen.kind].orEmpty()
                    rows+=Row("section-tools",Kind.CATALOG_BAR,title=if(catalogSearchEditing)catalogSearchDraft else query,selected=if(catalogSearchEditing)1 else 0,
                        queryChanged=::liveCatalogSearch,searchDone=::finishCatalogSearch,controls=listOf(
                            (query.ifBlank{"Поиск в разделе…"}) to {catalogSearchDraft=query;catalogSearchEditing=true;render(model.state.value)},
                            "Сбросить" to {sectionQueries.remove(screen.kind);catalogSearchDraft="";catalogSearchEditing=false;render(model.state.value)}
                        ))
                    if(query.isNotBlank() && data.more)rows+=Row("section-search-scope",Kind.TEXT,"Поиск по загруженным · остальные появляются при прокрутке")
                    val filtered=filterSectionData(data,query)
                    val cards=when(screen.kind){
                        HomeSection.LATEST,HomeSection.SCHEDULE->releaseCards(filtered.releases,screen.kind.name)
                        HomeSection.FRANCHISES,HomeSection.GENRES->featureCards(filtered.features,screen.kind)
                        HomeSection.VIDEOS->videoCards(filtered.videos)
                    }
                    if(screen.kind==HomeSection.LATEST){
                        episodePeriods(filtered.releases).forEach{period->
                            rows+=Row("period:${period.key}",Kind.HEADING,period.title,selected=1)
                            rows+=Row("period-shelf:${period.key}",Kind.SHELF,selected=6,cards=releaseCards(period.releases,screen.kind.name))
                        }
                    }else rows+=cards.map{it.copy(homeStyle=true)}
                    if((query.isNotBlank() || screen.kind==HomeSection.LATEST) && cards.isEmpty() && state.more && !state.loadingMore && state.moreError==null){
                        list.post{if(model.state.value.screen==screen && sectionQueries[screen.kind]==query)model.loadMoreSection()}
                    }
                    if(state.loadingMore)rows+=Row("section-loading-more",Kind.TEXT,"Загрузка…")
                    else if(state.moreError!=null)rows+=Row("section-more-retry",Kind.ACTION,"Повторить загрузку",state.moreError,click=model::loadMoreSection)
                    if(cards.isEmpty())rows+=Row("section-empty",Kind.TEXT,if(query.isNotBlank())"Ничего не найдено"else "Пока ничего нет")
                }

                is Screen.Library->{
                    rows+=Row("library-heading",Kind.HEADING,"Моя библиотека",selected=1)
                    rows+=state.releases.map{Row("library:${it.id}",Kind.POSTER,release=it,selected=2)}
                    if(state.releases.isEmpty()){
                        rows+=Row("library-empty",Kind.TEXT,"Здесь будут ваши тайтлы","Добавляйте их на странице тайтла или удержанием OK на карточке.")
                        rows+=Row("library-catalogue",Kind.ACTION,"Открыть каталог",click={model.open(Screen.Catalog())})
                    }
                }
                is Screen.Franchise->{
                    rows+=Row("franchise-heading",Kind.HEADING,screen.title,"Порядок просмотра · слева направо")
                    if(state.releases.isNotEmpty())rows+=Row("franchise-order:${screen.id}",Kind.SHELF,selected=7,cards=watchOrderCards(state.releases,"franchise:${screen.id}"))
                    if(state.releases.isEmpty())rows+=Row("empty",Kind.TEXT,"Релизы пока не указаны")
                }
                is Screen.Catalog->{
                    val total=state.catalogTotal?.let{"Найдено: $it"}?:"Загружено: ${state.releases.size}"
                    rows+=Row("catalogue-heading",Kind.HEADING,screen.title?:"Каталог",total,selected=1)
                    val sort=catalogReferences["sorting"]?.firstOrNull{it.value==screen.filters.sorting}?.title?:when(screen.filters.sorting){"RATING_DESC"->"По рейтингу";"YEAR_DESC"->"Сначала новые";"YEAR_ASC"->"Сначала старые";else->"Сортировка"}
                    rows+=Row("catalogue-tools",Kind.CATALOG_BAR,title=catalogSearchDraft,selected=if(catalogSearchEditing)1 else 0,queryChanged=::liveCatalogSearch,searchDone=::finishCatalogSearch,controls=listOf(
                        (if(screen.query.isBlank())"Поиск по каталогу…"else screen.query.take(40)) to {catalogSearch(screen)},
                        "Фильтры${if(screen.filters.count+(if(screen.genre!=null)1 else 0)>0)" · ${screen.filters.count+(if(screen.genre!=null)1 else 0)}"else ""}" to {catalogFilters(screen)},
                        sort to {catalogSorting(screen)},
                        "Сбросить" to {rememberFocus();model.open(Screen.Catalog(),false)}
                    ))
                    if(screen.filters.count>0 || screen.genre!=null || screen.query.isNotBlank())rows+=Row("catalogue-active",Kind.TEXT,catalogSummary(screen))
                    rows+=state.releases.map{Row("release:${it.id}",Kind.POSTER,release=it,selected=2)}
                    if(state.loading)rows+=Row("catalogue-search-loading",Kind.TEXT,"Поиск…")
                    else if(state.error!=null)rows+=Row("catalogue-search-retry",Kind.ACTION,"Повторить поиск",state.error,click=model::retry)
                    else if(state.releases.isEmpty())rows+=Row("catalogue-empty",Kind.ACTION,"Ничего не найдено","Измените запрос или фильтры",click={catalogFilters(screen)})
                    if(state.loadingMore)rows+=Row("catalogue-loading-more",Kind.TEXT,"Загрузка…")
                    else if(state.moreError!=null)rows+=Row("catalogue-more-retry",Kind.ACTION,"Повторить загрузку",state.moreError,click=model::loadMoreCatalog)
                }
                is Screen.Detail->{
                    val r=state.detail?:return
                    val description=android.text.Html.fromHtml(r.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    rows+=Row("hero",Kind.HERO,release=r,selected=if(isFavorite(r.id))1 else 0,controls=buildList{
                        if(description.isNotBlank())add("Описание" to {TvSidePanel(this@MainActivity,"Описание").apply{message(description);addAction("Закрыть"){dismiss()};show()};Unit})
                        add("Ещё" to {showDetailMore(r)})
                    })
                    rows+=Row("tabs",Kind.TABS,title=r.episodes.size.toString(),selected=screen.tab)
                    when(screen.tab){
                        0->{rows+=r.episodes.map{Row("episode:${it.id}",Kind.EPISODE,episode=it)};if(r.episodes.isEmpty())rows+=Row("empty",Kind.TEXT,"Эпизоды пока недоступны")}
                        1->{
                            val franchises=state.discovery?.franchises
                            when {
                                state.discoveryLoading -> rows+=Row("related-loading",Kind.TEXT,"Загрузка франшизы…")
                                franchises==null -> rows+=Row("related-retry",Kind.ACTION,"Не удалось загрузить франшизу · Повторить",click=model::retryDiscovery)
                                franchises.isEmpty() -> rows+=Row("related-empty",Kind.TEXT,"Релиз не связан ни с одной франшизой")
                                else -> franchises.forEach { group ->
                                    rows+=Row("franchise-heading:${group.id}",Kind.HEADING,group.title,"Порядок просмотра · слева направо")
                                    rows+=Row("franchise-order:${group.id}",Kind.SHELF,selected=7,cards=watchOrderCards(group.releases,"related:${group.id}"))
                                }
                            }
                        }
                        2->{
                            val similar=state.discovery?.recommendations
                            when {
                                state.discoveryLoading->rows+=Row("similar-loading",Kind.TEXT,"Подбираем аниме…")
                                similar==null->rows+=Row("similar-retry",Kind.ACTION,"Повторить загрузку рекомендаций",click=model::retryDiscovery)
                                similar.isEmpty()->rows+=Row("similar-empty",Kind.TEXT,"Рекомендации пока отсутствуют")
                                else->rows+=similar.map{Row("similar:${it.id}",Kind.POSTER,release=it)}
                            }
                        }
                        3->{rows+=r.credits.groupBy{it.role}.map{(role,people)->Row("role:$role",Kind.TEXT,role,people.joinToString(" · "){it.name})};if(r.credits.isEmpty())rows+=Row("empty",Kind.TEXT,"Участники пока не указаны")}
                    }

                }
                else->Unit
            }
        }
        if(current==Screen.Home){
            val completed=pendingShelfRefresh.filter{it !in state.refreshing}
            val focusedShelf=list.findFocus()?.let{list.findContainingItemView(it)?.tag}
            completed.forEach{kind->
                if(kind !in state.refreshErrors)adapter.resetShelf("shelf:${kind.name}",!menuOpen && focusedShelf=="shelf:${kind.name}")
                pendingShelfRefresh.remove(kind)
            }
        }
        val preloadFranchises=when(val screen=state.screen){
            Screen.Home->state.home?.franchises.orEmpty()
            is Screen.Section->if(screen.kind==HomeSection.FRANCHISES)state.section?.features.orEmpty()else emptyList()
            else->emptyList()
        }
        preloadFranchises.take(12).forEach{loadFranchisePreview(it.id)}
        val keepDetailTabFocus=current is Screen.Detail && !enterContent && list.findFocus()?.let{list.findContainingItemView(it)?.tag}=="tabs"
        adapter.submit(rows)
        if(keepDetailTabFocus)return
        if(current==Screen.Home && menuOpen)return
        if(catalogSearchEditing && (current is Screen.Catalog || current is Screen.Section))return
        if(enterCatalogResults && (current is Screen.Catalog || current is Screen.Section) && !state.loading){
            enterCatalogResults=false
            val index=rows.indexOfFirst{it.kind in listOf(Kind.POSTER,Kind.GENRE,Kind.FRANCHISE,Kind.FEATURE,Kind.SHELF)}.takeIf{it>=0}?:rows.indexOfFirst{it.kind==Kind.CATALOG_BAR}
            if(index>=0){
                list.doOnNextLayout{focusItem(list.findViewHolderForAdapterPosition(index)?.itemView)}
                grid.scrollToPositionWithOffset(index,list.dp(96))
            }
        }else if(enterDay){
            enterDay=false
            val index=rows.indexOfFirst{it.key.startsWith("shelf:schedule:")}.takeIf{it>=0}?:rows.indexOfFirst{it.key=="schedule-days"}
            if(index>=0){
                val days=rows.indexOfFirst{it.key=="schedule-days"}
                list.doOnNextLayout{
                    val bar=list.findViewHolderForAdapterPosition(days)?.itemView as?android.widget.HorizontalScrollView
                    val line=bar?.getChildAt(0)as?LinearLayout
                    line?.getChildAt(state.homeDay+1)?.requestFocus()
                }
            }
        }else if(enterContent && adapter.contentStart in rows.indices){
            enterContent=false
            val index=(adapter.contentStart until rows.size).firstOrNull { rows[it].kind != Kind.HEADING } ?: adapter.contentStart
            list.doOnNextLayout{list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()}
            grid.scrollToPositionWithOffset(index,list.dp(64))
        }else{
            val key=retainedFocus?:focusMemory[screenKey(state.screen)]
            val index=rows.indexOfFirst{it.key==key}.takeIf{it>=0}
                ?: if(key!=null && adapter.contentStart>=0) (adapter.contentStart until rows.size).firstOrNull{rows[it].kind!=Kind.HEADING}?:adapter.contentStart
                else rows.indexOfFirst{it.kind in listOf(Kind.ACTION,Kind.POSTER,Kind.HERO,Kind.SPOTLIGHT,Kind.SHELF,Kind.FEATURE,Kind.GENRE,Kind.FRANCHISE,Kind.CONTINUE,Kind.MORE,Kind.CATALOG_BAR)}
            if(index>=0&&!state.loading){list.doOnNextLayout{
                if(current!=state.screen || (current==Screen.Home && menuOpen))return@doOnNextLayout
                val item=list.findViewHolderForAdapterPosition(index)?.itemView
                if((!sameHome && !sameCatalog && !sameSection) || list.findFocus()==null)focusItem(item)
            };val scroll=scrollMemory[screenKey(state.screen)]
                val anchor=scroll?.first?.let{saved->rows.indexOfFirst{it.key==saved}.takeIf{it>=0}}
                if(!sameHome && !sameCatalog && !sameSection)grid.scrollToPositionWithOffset(anchor?:if(key==null)0 else index,scroll?.second?:if(key==null)0 else list.dp(12))}
        }
    }
}
