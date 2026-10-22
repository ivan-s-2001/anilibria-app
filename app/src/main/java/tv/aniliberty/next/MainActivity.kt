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
    private val prefs get()=playbackPreferences(this)
    private val account by lazy{AccountRepository(this)}
    private var accountJob:kotlinx.coroutines.Job?=null
    private var accountLoadedAt=0L
    private var accountHistoryJob:kotlinx.coroutines.Job?=null
    private val history get()=ViewingHistory(prefs)
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
    private val detailBookmarks=linkedMapOf<Pair<Int,Int>,DetailBookmark>()
    private val detailContentBookmarks=linkedMapOf<Pair<Int,Int>,DetailBookmark>()
    private val detailHeroButtons=linkedMapOf<Int,String>()
    private var detailFocusToken=0
    private var detailFocusPending=false
    private fun detailAddress():DetailBookmark? {
        val focus=list.findFocus()?:return null
        val row=list.findContainingItemView(focus)?:return null
        return DetailBookmark(row.tag as?String?:return null,(focus.tag as?String)?.takeIf{it!=row.tag})
    }
    private fun rememberDetailFocus(){
        val screen=current as?Screen.Detail?:return
        val address=detailAddress()?:return
        val key=screen.id to screen.tab
        if(detailBookmarks.size>48){detailBookmarks.remove(detailBookmarks.keys.first());detailContentBookmarks.keys.firstOrNull()?.let{detailContentBookmarks.remove(it)}}
        detailBookmarks[key]=address
        if(address.row=="hero")address.child?.let{detailHeroButtons[screen.id]=it}
        if(address.row!="hero" && address.row!="tabs")detailContentBookmarks[key]=address
        (list.findViewWithTag<View>(address.row)as?RecyclerView)?.let{adapter.rememberShelf(it)}
    }
    private fun focusDetail(index:Int,child:String?=null,top:Boolean=false){
        val screen=current as?Screen.Detail?:return
        if(index !in adapter.rows.indices)return
        val key=adapter.rows[index].key
        val token=++detailFocusToken
        fun apply(){
            if(token!=detailFocusToken || current!=screen || menuOpen || !list.hasWindowFocus())return
            val item=list.findViewHolderForAdapterPosition(index)?.itemView?:return
            if(item.tag!=key)return
            val exact=child?.let{item.findViewWithTag<View>(it)}
            if(exact?.isFocusable==true)exact.requestFocus()
            else if(key=="tabs"){
                item.findViewWithTag<View>("detail-tab:${screen.tab}")?.requestFocus()
            }else focusItem(item)
            detailFocusPending=false
        }
        val item=list.findViewHolderForAdapterPosition(index)?.itemView
        if(!top && item!=null && item.top>=list.paddingTop && item.bottom<=list.height-list.paddingBottom){apply();return}
        detailFocusPending=true;list.stopScroll()
        list.doOnNextLayout{if(token==detailFocusToken){detailFocusPending=false;apply()}}
        grid.scrollToPositionWithOffset(index,dp(if(key=="hero")0 else if(key=="tabs")12 else 64))
        list.requestLayout()
    }
    private fun focusDetailTab(index:Int=(current as?Screen.Detail)?.tab?:0){
        if(adapter.tabStart>=0)focusDetail(adapter.tabStart,"detail-tab:$index")
    }
    private fun enterDetailContent(){
        val screen=current as?Screen.Detail?:return
        val bookmark=detailContentBookmarks[screen.id to screen.tab]
        val position=adapter.rows.indexOfFirst{it.key==bookmark?.row}.takeIf{it>=adapter.contentStart && it>=0}
            ?:(adapter.contentStart.coerceAtLeast(0) until adapter.rows.size).firstOrNull{adapter.rows[it].kind!=Kind.HEADING}
        if(position!=null)focusDetail(position,bookmark?.child)
    }
    private fun detailKey(event:KeyEvent):Boolean {
        val screen=current as?Screen.Detail?:return false
        if(menuOpen || event.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN))return false
        if(event.action!=KeyEvent.ACTION_DOWN)return true
        if(detailFocusPending)return true
        val focus=list.findFocus()?:return false
        val outer=list.findContainingItemView(focus)?:return false
        val position=list.getChildAdapterPosition(outer)
        val row=adapter.rows.getOrNull(position)?:return false
        rememberDetailFocus()
        val horizontal=event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT
        val forward=event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN
        val direction=when(event.keyCode){KeyEvent.KEYCODE_DPAD_LEFT->View.FOCUS_LEFT;KeyEvent.KEYCODE_DPAD_RIGHT->View.FOCUS_RIGHT;KeyEvent.KEYCODE_DPAD_UP->View.FOCUS_UP;else->View.FOCUS_DOWN}
        if(row.kind==Kind.HERO || row.kind==Kind.TABS){
            if(horizontal){
                val choices=(outer as ViewGroup).getFocusables(View.FOCUS_FORWARD).filter{it is android.widget.TextView && it.isClickable}
                val index=choices.indexOf(focus)
                val target=choices.getOrNull(index+if(forward)1 else -1)
                if(index>=0 && target!=null)target.requestFocus()
                else if(!forward && index==0 && event.repeatCount==0)openMenu()
            }else if(row.kind==Kind.HERO){if(forward)focusDetailTab()}
            else if(forward){
                val tab=(focus.tag as?String)?.removePrefix("detail-tab:")?.toIntOrNull()?:screen.tab
                enterSelectedTab(tab)
            }else{
                val hero=adapter.rows.indexOfFirst{it.kind==Kind.HERO}
                if(hero>=0)focusDetail(hero,detailHeroButtons[screen.id],top=true)
            }
            return true
        }
        if(horizontal && outer is RecyclerView){
            adapter.rememberShelf(outer)
            val nested=focus.let{outer.findContainingItemView(it)}
            val index=nested?.let{outer.getChildAdapterPosition(it)}?:-1
            val size=outer.adapter?.itemCount?:0
            if(!forward && index==0){if(event.repeatCount==0)openMenu();return true}
            if(forward && index>=size-1)return true
            super.dispatchKeyEvent(event);return true // Keep horizontal navigation inside the nested shelf.
        }
        val target=detailGridTarget(adapter.rows,position,direction)
        if(target!=null){focusDetail(target);return true}
        if(horizontal){if(!forward && event.repeatCount==0)openMenu();return true}
        val candidates=if(forward)(position+1 until adapter.rows.size)else(position-1 downTo adapter.contentStart.coerceAtLeast(0))
        val next=candidates.firstOrNull{adapter.rows[it].kind !in listOf(Kind.HEADING,Kind.TABS,Kind.HERO)}
        // Grid top/bottom edges do not wrap to the previous/next row through FocusFinder.
        if(row.kind in listOf(Kind.EPISODE,Kind.POSTER)){
            if(!forward)focusDetailTab()
        }else if(next!=null)focusDetail(next)else if(!forward)focusDetailTab()
        return true
    }
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
        if(detailKey(event))return true
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
                if(current==Screen.Home || current is Screen.Detail)false else super.requestChildRectangleOnScreen(child,rect,immediate)
        }.apply {
            setBackgroundColor(0xFF121212.toInt()); setPadding(dp(32),dp(8),dp(32),dp(24));clipToPadding=false;itemAnimator=null
        }
        grid=object:GridLayoutManager(this,15){
            override fun onRequestChildFocus(parent:RecyclerView,state:RecyclerView.State,child:View,focused:View?):Boolean=
                current==Screen.Home || current is Screen.Detail || super.onRequestChildFocus(parent,state,child,focused)
        };list.layoutManager=grid
        adapter=PageAdapter(::navigate,::selectTab,::play,::favorite,{rememberFocus();model.open(Screen.Detail(it.id))},
            { id -> prefs.getLong("position:$id",0) to prefs.getLong("duration:$id",0) },
            { id -> prefs.getString("last:$id",null) },::returnToTabs, { index -> enterDay=true;val previous=model.state.value;model.homeDay(index);if(previous==model.state.value)render(model.state.value) },::quickActions,enterTab=::enterSelectedTab)
        grid.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=when(adapter.rows.getOrNull(position)?.kind){Kind.POSTER->if(current is Screen.Catalog || current is Screen.Section || current is Screen.Library || current is Screen.Franchise)5 else 3;Kind.GENRE->if(current is Screen.Section)5 else 3;Kind.FRANCHISE->if((current as?Screen.Section)?.kind==HomeSection.FRANCHISES)10 else 5;Kind.EPISODE,Kind.FEATURE->5;else->grid.spanCount}}
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
                val state=model.state.value
                if(current==Screen.AccountHistory && dy>0 && grid.findLastVisibleItemPosition()>=adapter.itemCount-6 && accountHistoryError==null)loadAccountHistory()
                if(state.moreError==null && dy>0 && grid.findLastVisibleItemPosition()>=adapter.itemCount-9){
                    if(state.screen is Screen.Catalog)model.loadMoreCatalog()
                    else if(state.screen is Screen.Section)model.loadMoreSection()
                }
            }
        })
        list.viewTreeObserver.addOnGlobalFocusChangeListener{_,focus->
            if(current is Screen.Detail && focus!=null && list.findContainingItemView(focus)!=null)rememberDetailFocus()
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
        if(savedInstanceState==null && getSharedPreferences("viewing",MODE_PRIVATE).getBoolean("welcomed",false))model.open(Screen.Home,false)
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::adapter.isInitialized && current is Screen.Detail && !menuOpen && detailAddress()==null){
            val screen=current as Screen.Detail
            val bookmark=detailBookmarks[screen.id to screen.tab]
            val index=adapter.rows.indexOfFirst{it.key==bookmark?.row}.takeIf{it>=0}?:adapter.rows.indexOfFirst{it.kind==Kind.HERO}
            if(index>=0)focusDetail(index,bookmark?.child)
        }
    }
    override fun onResume(){
        super.onResume()
        if(resumedOnce && ::adapter.isInitialized){rememberFocus();render(model.state.value)}
        resumedOnce=true
        hydrateHistory()
        if(account.signedIn)refreshAccount(force=true)
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
        rememberDetailFocus()
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
    private fun login(){
        val previousUser=account.session.userId
        AccountPanel(this,lifecycleScope,account){
            accountJob?.cancel();accountJob=null;profileSyncing=false
            accountHistoryJob?.cancel();accountHistoryLoadJob?.cancel();accountHistoryItems=emptyList();accountSessions=emptyList();accountHistoryPage=0;profileError=null;lastHistoryHydration=0L;accountLoadedAt=0L
            Toast.makeText(this,"Вход выполнен · ${account.session.name}",Toast.LENGTH_SHORT).show()
            if(previousUser!=null && previousUser!=account.session.userId)model.resetNavigation(Screen.Profile)
            rememberFocus();render(model.state.value);refreshAccount(force=true,after={offerLocalHistory(automatic=true)})
        }.start()
    }
    private fun refreshAccount(force:Boolean=false,after:(()->Unit)?=null){
        if(!account.signedIn || accountJob?.isActive==true)return
        if(!force && System.currentTimeMillis()-accountLoadedAt<60000)return
        profileSyncing=true;profileError=null
        if(current==Screen.Profile)render(model.state.value)
        accountJob=lifecycleScope.launch{
            try{
                account.refresh();accountLoadedAt=System.currentTimeMillis()
                account.loadProgress()
                rememberFocus();render(model.state.value);after?.invoke();startAccountHistorySync()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){profileError=e.message?:"Проверьте соединение"}
            finally{profileSyncing=false;if(current==Screen.Profile)render(model.state.value)}
        }
    }
    private fun startAccountHistorySync(){
        if(accountHistoryJob?.isActive==true)return
        accountHistoryJob=lifecycleScope.launch{hydrateAccountHistory();if(account.signedIn){rememberFocus();render(model.state.value)}}
    }
    private suspend fun hydrateAccountHistory(){
        val user=account.session.userId?:return
        try{
            val recent=account.recentHistory().distinctBy{it.releaseId}.take(12)
            for(record in recent){
                if(account.session.userId!=user)return
                val local=history.latestWatched().firstOrNull{it.releaseId==record.releaseId}
                if(local!=null && local.updatedAt>record.updatedAt)continue
                try{
                    val release=model.api.release(record.releaseId)
                    if(account.session.userId!=user)return
                    account.applyProgress(release)
                    val episode=release.episodes.firstOrNull{it.id==record.episodeId}?:continue
                    history.save(release,episode,prefs.getLong("position:${episode.id}",0),prefs.getLong("duration:${episode.id}",episode.duration*1000),record.updatedAt)
                    prefs.edit().putString("last:${release.id}",episode.id).apply()
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){ /* Keep already available local cards. */ }
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){ /* Progress sync itself can succeed while history metadata is unavailable. */ }
    }
    private fun accountAction(done:(()->Unit)?=null,block:suspend()->Unit){
        if(accountJob?.isActive==true){Toast.makeText(this,"Подождите, идёт синхронизация",Toast.LENGTH_SHORT).show();return}
        accountJob=lifecycleScope.launch{
            try{block();rememberFocus();render(model.state.value);done?.invoke()}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){
                val panel=TvSidePanel(this@MainActivity,"Не удалось сохранить")
                panel.message(e.message?:"Проверьте подключение")
                panel.addAction("Повторить",true){panel.dismiss();accountAction(done,block)}
                if(e is AccountHttpException && e.status in listOf(401,403))panel.addAction("Войти заново"){panel.dismiss();login()}
                panel.addAction("Закрыть"){panel.dismiss()};panel.show()
                if(!account.signedIn)render(model.state.value)
            }
        }
    }
    private var profileError:String?=null
    private var profileSyncing=false
    private var accountHistoryItems:List<AccountViewedEpisode> = emptyList()
    private var accountHistoryPage=0
    private var accountHistoryMore=false
    private var accountHistoryLoading=false
    private var accountHistoryError:String?=null
    private var accountHistoryLoadJob:kotlinx.coroutines.Job?=null
    private fun library(){
        rememberFocus();model.open(Screen.Profile)
        if(account.signedIn)refreshAccount(force=true)
    }
    private fun accountList(type:CollectionType?){
        if(account.lists==null){accountAction(done={accountList(type)}){account.refresh()};return}
        val lists=account.lists!!
        val ids=if(type==null)lists.favorites.toList()else lists.collections.filterValues{it==type}.keys.toList()
        rememberFocus();model.open(Screen.Library(ids,type?.title?:"Избранное"))
    }
    private var accountSessions:List<AccountDeviceSession> = emptyList()
    private var accountSessionsLoading=false
    private var accountSessionsError:String?=null
    private fun loadAccountSessions(){
        if(accountSessionsLoading || !account.signedIn)return
        accountSessionsLoading=true;accountSessionsError=null
        if(current==Screen.AccountSessions)render(model.state.value)
        lifecycleScope.launch{
            val user=account.session.userId
            try{val sessions=account.sessions();if(account.session.userId==user)accountSessions=sessions}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){accountSessionsError=e.message?:"Не удалось загрузить сеансы"}
            finally{accountSessionsLoading=false;if(current==Screen.AccountSessions)render(model.state.value)}
        }
    }
    private fun editAccountIdentity(nickname:Boolean){
        val panel=TvSidePanel(this,if(nickname)"Имя в профиле"else"Логин")
        val input=EditText(this).apply{
            setSingleLine();setText(if(nickname)account.session.profile()?.name else account.session.profile()?.login)
            textSize=18f;setTextColor(WHITE);setPadding(dp(12),dp(12),dp(12),dp(12));webControlBackground()
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        panel.body.addView(input,LinearLayout.LayoutParams(-1,-2))
        val save=panel.addAction("Сохранить",true){
            val value=input.text.toString().trim()
            if(value.isBlank()){input.error="Заполните поле"}else accountAction(done={panel.dismiss()}){account.updateIdentity(nickname,value)}
        }
        input.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_DONE){save.requestFocus();true}else false}
        panel.addAction("Отмена"){panel.dismiss()};panel.show();input.requestFocus();input.setSelection(input.text.length)
    }
    private fun accountSettingsOnPhone(){
        val url="https://www.aniliberty.top/app/settings/account"
        val panel=TvSidePanel(this,"Настройки аккаунта")
        panel.message("Изменение аватара, почты и пароля доступно на официальном сайте. Откройте QR-код на телефоне и войдите в свой аккаунт.")
        val matrix=com.google.zxing.qrcode.QRCodeWriter().encode(url,com.google.zxing.BarcodeFormat.QR_CODE,256,256)
        val image=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
        image.setPixels(IntArray(256*256){i->if(matrix[i%256,i/256])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,256,0,0,256,256)
        panel.body.addView(android.widget.ImageView(this).apply{setImageBitmap(image);contentDescription="QR-код настроек аккаунта"},LinearLayout.LayoutParams(dp(180),dp(180)).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL})
        panel.addAction("Открыть на ТВ"){openLink(url)}
        panel.addAction("Закрыть"){panel.dismiss()};panel.show()
    }
    private fun confirmLogout(){
        TvSidePanel(this,"Выйти из аккаунта?").apply{
            message("Данные аккаунта останутся на сайте. Приложение переключится на гостевую историю этого ТВ.")
            addAction("Выйти"){
                dismiss();accountHistoryLoadJob?.cancel();accountHistoryJob?.cancel()
                model.resetNavigation(Screen.Profile)
                accountAction(done={lastHistoryHydration=0;accountHistoryItems=emptyList();accountSessions=emptyList();accountHistoryPage=0;model.open(Screen.Profile,false)}){
                    if(!account.logout())Toast.makeText(this@MainActivity,"Вышли на этом ТВ. Сервис не подтвердил завершение сеанса.",Toast.LENGTH_LONG).show()
                }
            }
            addAction("Отмена"){dismiss()};show()
        }
    }
    private fun openAccountHistory(){
        rememberFocus();model.open(Screen.AccountHistory);loadAccountHistory(reset=true)
    }
    private fun loadAccountHistory(reset:Boolean=false){
        if(!account.signedIn || accountHistoryLoading || (!reset && !accountHistoryMore))return
        val user=account.session.userId
        if(reset){accountHistoryItems=emptyList();accountHistoryPage=0}
        accountHistoryLoading=true;accountHistoryError=null
        if(current==Screen.AccountHistory)render(model.state.value)
        accountHistoryLoadJob=lifecycleScope.launch{
            try{
                val next=account.history(accountHistoryPage+1)
                if(account.session.userId!=user)return@launch
                accountHistoryItems=(accountHistoryItems+next.items).distinctBy{it.episode.id}
                accountHistoryPage++;accountHistoryMore=next.more
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){accountHistoryError=e.message?:"Не удалось загрузить историю"}
            finally{
                accountHistoryLoading=false
                if(current==Screen.AccountHistory)render(model.state.value)
            }
        }
    }
    private fun offerLocalHistory(automatic:Boolean=false){
        val local=localTimecodes(getSharedPreferences("viewing",MODE_PRIVATE))
        if(local.isEmpty()){if(!automatic)Toast.makeText(this,"На этом ТВ нет локальной истории для переноса",Toast.LENGTH_SHORT).show();return}
        var plan:List<AccountTimecode> = emptyList()
        // Code-login's callback can arrive while the account refresh job is completing.
        list.post{
            accountAction(done={
                val panel=TvSidePanel(this,"Занести локальную историю просмотра в ваш аккаунт?")
                panel.message(if(plan.isEmpty())"Все серии из локальной истории уже есть в аккаунте. Прогресс аккаунта сохранён."else"Можно перенести прогресс ${plan.size} серий с этого ТВ. Уже сохранённый в аккаунте прогресс не изменится. Локальная история останется на ТВ.")
                if(plan.isNotEmpty())panel.addAction("Перенести",true){panel.dismiss();accountAction{val count=account.importLocalHistory();startAccountHistorySync();Toast.makeText(this,"Перенесено серий: $count",Toast.LENGTH_LONG).show()}}
                panel.addAction(if(plan.isEmpty())"Закрыть"else"Пропустить"){panel.dismiss()};panel.show()
            }){plan=account.localImportPlan()}
        }
    }
    private fun collections(release:Release){
        if(!account.signedIn){login();return}
        if(account.lists==null){accountAction(done={collections(release)}){account.refresh()};return}
        val current=account.lists!!.collections[release.id]
        val panel=TvSidePanel(this,"Коллекция")
        panel.message(release.title)
        CollectionType.entries.forEach{type->panel.addChoice(type.title,current==type,R.drawable.ic_library){panel.dismiss();accountAction{account.collection(release.id,type)}}}
        if(current!=null)panel.addChoice("Убрать из коллекции",icon=R.drawable.ic_close){panel.dismiss();accountAction{account.collection(release.id,null)}}
        panel.addAction("Отмена"){panel.dismiss()};panel.show()
    }
    private fun setEpisodeWatched(release:Release,episodes:List<Episode>,watched:Boolean){
        val edit=prefs.edit()
        episodes.forEach{e->
            val duration=(e.duration*1000).coerceAtLeast(1)
            edit.putLong("position:${e.id}",if(watched)duration else 0).putLong("duration:${e.id}",duration)
        };edit.apply()
        queueAccountTimecodes(this,episodes.map{e->AccountTimecode(e.id,prefs.getLong("position:${e.id}",0)/1000.0,watched)})
        episodes.lastOrNull()?.let{e->history.save(release,e,prefs.getLong("position:${e.id}",0),prefs.getLong("duration:${e.id}",0))}
        if(episodes.isNotEmpty()){if(watched)prefs.edit().putString("last:${release.id}",episodes.last().id).apply()else prefs.edit().remove("last:${release.id}").apply()}
        rememberFocus();render(model.state.value)
        if(account.signedIn)accountAction{account.flush();account.loadProgress()}
    }
    private fun episodeActions(release:Release,episode:Episode){
        val completed=prefs.getLong("position:${episode.id}",0)>0 && prefs.getLong("position:${episode.id}",0)>=prefs.getLong("duration:${episode.id}",episode.duration*1000)-1000
        val panel=TvSidePanel(this,episode.label)
        if(episode.streams.isNotEmpty())panel.addChoice("Смотреть с начала",icon=R.drawable.ic_play){
            panel.dismiss();prefs.edit().putLong("position:${episode.id}",0).apply();play(episode)
        }
        panel.addChoice(if(completed)"Отметить непросмотренной"else"Отметить просмотренной",icon=R.drawable.ic_episodes){panel.dismiss();setEpisodeWatched(release,listOf(episode),!completed)}
        panel.addAction("Закрыть"){panel.dismiss()};panel.show()
    }
    private fun releaseWatchActions(release:Release){
        val panel=TvSidePanel(this,"Отметки просмотра")
        panel.message("Для всех ${release.episodes.size} серий «${release.title}». Коллекция тайтла меняется отдельно.")
        panel.addChoice("Все серии просмотрены",icon=R.drawable.ic_episodes){panel.dismiss();setEpisodeWatched(release,release.episodes,true)}
        panel.addChoice("Все серии не просмотрены",icon=R.drawable.ic_refresh){panel.dismiss();setEpisodeWatched(release,release.episodes,false)}
        panel.addAction("Отмена"){panel.dismiss()};panel.show()
    }
    private fun localLibrary(){
        val items=JSONArray(prefs.getString("library","[]"))
        val ids=(0 until items.length()).mapNotNull{items.optJSONObject(it)?.optInt("id")?.takeIf{id->id>0}}
        rememberFocus();model.open(Screen.Library(ids))
    }
    private fun isFavorite(id:Int):Boolean {
        if(account.signedIn)return account.lists?.favorites?.contains(id)==true
        val items=JSONArray(prefs.getString("library","[]"))
        return (0 until items.length()).any{items.optJSONObject(it)?.optInt("id")==id}
    }
    private fun favorite(release:Release){
        if(account.signedIn){
            accountAction{
                if(account.lists==null)account.refresh()
                val added=account.lists?.favorites?.contains(release.id)!=true
                account.favorite(release.id,added)
                Toast.makeText(this,if(added)"Добавлено в избранное"else"Удалено из избранного",Toast.LENGTH_SHORT).show()
            }
            return
        }
        val panel=TvSidePanel(this,"Избранное AniLiberty")
        panel.message("Войдите, чтобы сохранять избранное и коллекции в аккаунте. Можно также сохранить тайтл только на этом ТВ.")
        panel.addChoice("Войти в аккаунт",icon=R.drawable.ic_library){panel.dismiss();login()}
        panel.addChoice(if(isFavorite(release.id))"Убрать из библиотеки на ТВ"else"Сохранить на этом ТВ",icon=R.drawable.ic_favorite){panel.dismiss();localFavorite(release)}
        panel.addAction("Отмена"){panel.dismiss()};panel.show()
    }
    private fun localFavorite(release:Release){
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
        val added=isFavorite(item.id)
        val labels=arrayOf(if(resume)"Продолжить просмотр"else"Смотреть",if(account.signedIn){if(added)"Убрать из избранного"else"В избранное"}else if(added)"Убрать с этого ТВ"else"В избранное","Подробнее")
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
    private fun returnToTabs(){focusDetailTab()}
    private fun openLink(url:String?){
        if(url==null)return
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:ActivityNotFoundException){Toast.makeText(this,"Нет приложения для открытия ссылки",Toast.LENGTH_LONG).show()}
    }
    private fun showDetailMore(release:Release){
        val panel=TvSidePanel(this,"Дополнительно")
        panel.addChoice("Открыть страницу на сайте",icon=R.drawable.ic_arrow_right){panel.dismiss();openLink("https://www.aniliberty.top/anime/releases/release/${release.alias}/episodes")}
        panel.addChoice("Отметки просмотра серий",icon=R.drawable.ic_episodes){panel.dismiss();releaseWatchActions(release)}
        panel.addChoice(if(account.signedIn)"Аккаунт · ${account.session.name}"else"Войти в аккаунт",icon=R.drawable.ic_library){panel.dismiss();library()}
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
        val sameDetail=(current as?Screen.Detail)?.id==(state.screen as?Screen.Detail)?.id && current is Screen.Detail && adapter.rows.any{it.kind==Kind.HERO}
        val sameCatalog=current is Screen.Catalog && (current==state.screen || catalogSearchEditing) && adapter.rows.any{it.kind==Kind.CATALOG_BAR}
        val sameSection=current is Screen.Section && current==state.screen && adapter.rows.any{it.key=="section-heading"}
        val sameProfile=(current==Screen.Profile || current==Screen.AccountSessions) && current==state.screen && adapter.rows.any{it.kind==Kind.ACTION}
        val sameAccountHistory=current==Screen.AccountHistory && state.screen==Screen.AccountHistory && adapter.rows.any{it.key=="account-history-refresh"}
        val sameHome=current==Screen.Home && state.screen==Screen.Home && adapter.rows.any{it.kind==Kind.SHELF}
        val retainedFocus = if(current==state.screen) list.findFocus()?.let { list.findContainingItemView(it)?.tag as? String } else null
        current=state.screen
        val section=when(current){Screen.Profile,Screen.AccountHistory,Screen.AccountSessions->3;Screen.Home->0;is Screen.Catalog,is Screen.Section,is Screen.Franchise->1;is Screen.Library->3;else->selectedSection}
        if(section!=selectedSection){selectedSection=section;buildMenu()}
        homeMenuLayout()
        val spans=if(current is Screen.Catalog || current is Screen.Section || current is Screen.Library || current is Screen.Franchise)20 else 15
        if(grid.spanCount!=spans)grid.spanCount=spans
        menu.visibility=if(current==Screen.Welcome)View.GONE else View.VISIBLE
        val rows=mutableListOf<Row>()
        if(state.screen==Screen.Welcome){
            rows+=Row("welcome",Kind.HEADING,"AniLiberty","Так звучит аниме")
            rows+=Row("intro",Kind.TEXT,"Аниме на большом экране","Управление пультом. Просмотр без обязательного входа.")
            rows+=Row("start",Kind.ACTION,"Начать просмотр",click={getSharedPreferences("viewing",MODE_PRIVATE).edit().putBoolean("welcomed",true).apply();model.open(Screen.Home,false)})
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

                Screen.Profile->{
                    rows+=Row("profile-heading",Kind.HEADING,"Профиль",selected=1)
                    rows+=Row("profile-identity",Kind.PROFILE,profile=account.session.profile())
                    if(account.signedIn){
                        if(profileSyncing)rows+=Row("profile-sync",Kind.TEXT,"Синхронизация…")
                        profileError?.let{rows+=Row("profile-error",Kind.TEXT,"Не удалось обновить аккаунт",it)}
                        if(account.session.profile()?.banned==true)rows+=Row("profile-ban",Kind.TEXT,"Аккаунт ограничен","Подробности доступны в профиле на сайте.")
                        val lists=account.lists
                        rows+=Row("profile-favorites",Kind.ACTION,"Избранное${lists?.let{" · ${it.favorites.size}"}.orEmpty()}",click={accountList(null)})
                        CollectionType.entries.forEach{type->rows+=Row("profile-collection:${type.name}",Kind.ACTION,type.title+(lists?.let{" · ${it.collections.values.count{v->v==type}}"}.orEmpty()),click={accountList(type)})}
                        rows+=Row("profile-history",Kind.ACTION,"История просмотра",click={openAccountHistory()})
                        rows+=Row("profile-import",Kind.ACTION,"Занести локальную историю в аккаунт",click={offerLocalHistory()})
                        val queued=pending(prefs).size
                        if(queued>0)rows+=Row("profile-pending",Kind.TEXT,"Ожидают синхронизации: $queued серий","Прогресс сохранён на этом ТВ.")
                        rows+=Row("profile-refresh",Kind.ACTION,"Синхронизировать",click={refreshAccount(force=true)})
                        rows+=Row("profile-nickname",Kind.ACTION,"Изменить имя",click={editAccountIdentity(true)})
                        rows+=Row("profile-login-name",Kind.ACTION,"Изменить логин",click={editAccountIdentity(false)})
                        rows+=Row("profile-sessions",Kind.ACTION,"Устройства и сеансы",click={rememberFocus();model.open(Screen.AccountSessions);loadAccountSessions()})
                        rows+=Row("profile-site",Kind.ACTION,"Аватар, почта и пароль",click={accountSettingsOnPhone()})
                        rows+=Row("profile-relogin",Kind.ACTION,"Войти в другой аккаунт",click={login()})
                        rows+=Row("profile-logout",Kind.ACTION,"Выйти из аккаунта",click={confirmLogout()})
                    }else{
                        rows+=Row("profile-guest",Kind.TEXT,"История сохраняется на этом ТВ","Войдите для доступа к избранному, коллекциям и прогрессу аккаунта.")
                        rows+=Row("profile-login",Kind.ACTION,"Войти по QR-коду",click={login()})
                        rows+=Row("profile-local",Kind.ACTION,"Библиотека на этом ТВ",click={localLibrary()})
                    }
                }
                Screen.AccountSessions->{
                    rows+=Row("sessions-heading",Kind.HEADING,"Устройства и сеансы",selected=1)
                    if(accountSessionsLoading)rows+=Row("sessions-loading",Kind.TEXT,"Загрузка устройств…")
                    accountSessions.forEach{session->
                        if(session.current)rows+=Row("session:${session.id}",Kind.TEXT,"${session.title} · Этот ТВ",session.description)
                        else rows+=Row("session:${session.id}",Kind.ACTION,session.title,session.description,click={
                            TvSidePanel(this,"Завершить сеанс?").apply{
                                message(session.title+"\n"+session.description)
                                addAction("Завершить"){dismiss();accountAction(done={loadAccountSessions()}){account.deleteSession(session.id)}}
                                addAction("Отмена"){dismiss()};show()
                            }
                        })
                    }
                    if(accountSessionsError!=null)rows+=Row("sessions-error",Kind.TEXT,"Не удалось обновить",accountSessionsError.orEmpty())
                    rows+=Row("sessions-refresh",Kind.ACTION,"Обновить устройства",click={loadAccountSessions()})
                    if(accountSessions.any{!it.current})rows+=Row("sessions-others",Kind.ACTION,"Завершить все другие сеансы",click={
                        TvSidePanel(this,"Завершить другие сеансы?").apply{
                            message("Потребуется заново войти на остальных устройствах. Этот ТВ останется в аккаунте.")
                            addAction("Завершить"){dismiss();accountAction(done={loadAccountSessions()}){account.deleteOtherSessions()}}
                            addAction("Отмена"){dismiss()};show()
                        }
                    })
                }
                Screen.AccountHistory->{
                    rows+=Row("account-history-heading",Kind.HEADING,"История просмотра",selected=1)
                    rows+=Row("account-history-refresh",Kind.ACTION,"Обновить историю",click={loadAccountHistory(reset=true)})
                    rows+=accountHistoryItems.map{item->
                        val duration=(item.episode.duration*1000).coerceAtLeast(1)
                        val position=if(item.time.watched)duration else(item.time.seconds*1000).toLong()
                        Row("account-history:${item.episode.id}",Kind.EPISODE,title=item.release.title,release=item.release,episode=item.episode,episodeProgress=position to duration,click={
                            rememberFocus();startActivity(Intent(this,PlayerActivity::class.java).putExtra("release",item.release.id).putExtra("episode",item.episode.id))
                        })
                    }
                    if(accountHistoryLoading)rows+=Row("account-history-loading",Kind.TEXT,"Загрузка истории…")
                    else if(accountHistoryError!=null)rows+=Row("account-history-retry",Kind.ACTION,"Повторить загрузку",accountHistoryError.orEmpty(),click={loadAccountHistory(reset=accountHistoryPage==0)})
                    else if(accountHistoryItems.isEmpty())rows+=Row("account-history-empty",Kind.TEXT,"Вы ещё ничего не смотрели")
                    else if(accountHistoryMore)rows+=Row("account-history-more",Kind.ACTION,"Загрузить ещё",click={loadAccountHistory()})
                }
                is Screen.Library->{
                    rows+=Row("library-heading",Kind.HEADING,screen.title,selected=1)
                    rows+=state.releases.map{Row("library:${it.id}",Kind.POSTER,release=it,selected=2)}
                    if(state.releases.isEmpty()){
                        rows+=Row("library-empty",Kind.TEXT,"Здесь будут ваши тайтлы",if(account.signedIn)"Выбирайте избранное и коллекцию на странице тайтла."else"Сохраняйте тайтлы на этом ТВ со страницы тайтла.")
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
                    account.applyProgress(r)
                    val description=android.text.Html.fromHtml(r.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    rows+=Row("hero",Kind.HERO,release=r,selected=if(isFavorite(r.id))1 else 0,favoriteTitle=if(account.signedIn){if(isFavorite(r.id))"В избранном"else"В избранное"}else if(isFavorite(r.id))"На этом ТВ"else"В избранное",controls=buildList{
                        add((account.lists?.collections?.get(r.id)?.title?:"Коллекция") to {collections(r)})
                        if(description.isNotBlank())add("Описание" to {TvSidePanel(this@MainActivity,"Описание").apply{message(description);addAction("Закрыть"){dismiss()};show()};Unit})
                        add("Ещё" to {showDetailMore(r)})
                    })
                    if(r.blocked)rows+=Row("release-restriction",Kind.TEXT,"Просмотр ограничен правообладателем или регионом")
                    rows+=Row("tabs",Kind.TABS,title=r.episodes.size.toString(),selected=screen.tab)
                    when(screen.tab){
                        0->{rows+=r.episodes.map{Row("episode:${it.id}",Kind.EPISODE,episode=it,episodeProgress=prefs.getLong("position:${it.id}",0) to prefs.getLong("duration:${it.id}",0),longClick={episodeActions(r,it)})};if(r.episodes.isEmpty())rows+=Row("empty",Kind.TEXT,"Эпизоды пока недоступны")}
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
        if(state.screen is Screen.Detail && !state.loading && rows.any{it.kind==Kind.HERO}){
            if(menuOpen || !list.hasWindowFocus())return
            if(enterContent){enterContent=false;enterDetailContent();return}
            if(keepDetailTabFocus)return
            if(sameDetail && detailAddress()?.let{address->rows.any{it.key==address.row}}==true)return
            val screen=state.screen
            val bookmark=detailBookmarks[screen.id to screen.tab]
            val index=rows.indexOfFirst{it.key==bookmark?.row}.takeIf{it>=0}?:rows.indexOfFirst{it.kind==Kind.HERO}
            if(index>=0)focusDetail(index,bookmark?.child)
            return
        }
        if(keepDetailTabFocus)return
        if(menuOpen)return
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
                if((!sameHome && !sameCatalog && !sameSection && !sameProfile && !sameAccountHistory) || list.findFocus()==null)focusItem(item)
            };val scroll=scrollMemory[screenKey(state.screen)]
                val anchor=scroll?.first?.let{saved->rows.indexOfFirst{it.key==saved}.takeIf{it>=0}}
                if(!sameHome && !sameCatalog && !sameSection && !sameProfile && !sameAccountHistory)grid.scrollToPositionWithOffset(anchor?:if(key==null)0 else index,scroll?.second?:if(key==null)0 else list.dp(12))}
        }
    }
}
