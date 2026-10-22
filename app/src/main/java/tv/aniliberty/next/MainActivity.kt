package tv.aniliberty.next

import android.app.AlertDialog
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
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model by viewModels<TvModel>()
    private lateinit var list: RecyclerView
    private lateinit var adapter: PageAdapter
    private lateinit var grid: GridLayoutManager
    private val prefs by lazy { getSharedPreferences("viewing", MODE_PRIVATE) }
    private val focusMemory = mutableMapOf<Screen, String>()
    private val scrollMemory=mutableMapOf<Screen,Pair<Int,Int>>()
    private fun screenKey(screen:Screen)=if(screen is Screen.Detail)screen.copy(tab=0)else screen
    private fun focusItem(item:View?) {
        when(item){
            is RecyclerView->adapter.restoreShelf(item)
            null->Unit
            else->if(item.isFocusable)item.requestFocus()else if(item is ViewGroup)item.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
        }
    }
    private var current: Screen = Screen.Welcome
    private var enterContent = false
    private var enterDay = false
    private lateinit var shell: LinearLayout
    private lateinit var menu: LinearLayout
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private var menuOpen=false
    private var contentFocus: View?=null
    private var selectedSection=0

    private fun buildMenu() {
        menu.removeAllViews()
        menu.layoutParams=LinearLayout.LayoutParams(dp(if(menuOpen)196 else 60),-1)
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
    private fun openMenu(){
        if(current==Screen.Welcome)return
        contentFocus=list.findFocus();rememberFocus();menuOpen=true;buildMenu()
        menu.getChildAt(listOf(2,0,1,3).indexOf(selectedSection).coerceAtLeast(0)+1)?.requestFocus()
    }
    private fun closeMenu(restore:Boolean=true){menuOpen=false;buildMenu();if(restore){if(contentFocus?.isAttachedToWindow==true)contentFocus?.requestFocus()else list.requestFocus()}}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
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
        list = RecyclerView(this).apply {
            setBackgroundColor(0xFF121212.toInt()); setPadding(dp(32),dp(8),dp(32),dp(24));clipToPadding=false;itemAnimator=null
        }
        grid=GridLayoutManager(this,15);list.layoutManager=grid
        adapter=PageAdapter(::navigate,::selectTab,::play,::favorite,{rememberFocus();model.open(Screen.Detail(it.id))},
            { id -> prefs.getLong("position:$id",0) to prefs.getLong("duration:$id",0) },
            { id -> prefs.getString("last:$id",null) },::returnToTabs, { index -> enterDay=true;val previous=model.state.value;model.homeDay(index);if(previous==model.state.value)render(model.state.value) },::quickActions)
        grid.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=when(adapter.rows[position].kind){Kind.POSTER,Kind.GENRE->3;Kind.EPISODE,Kind.FEATURE,Kind.FRANCHISE->5;else->15}}
        list.adapter=adapter
        shell=LinearLayout(this).apply{setBackgroundColor(0xFF121212.toInt())}
        menu=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(12),dp(4),0);setBackgroundColor(0xFF212121.toInt())}
        shell.addView(menu,LinearLayout.LayoutParams(dp(60),-1));shell.addView(list,LinearLayout.LayoutParams(0,-1,1f));buildMenu();setContentView(shell)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(menuOpen){if(current==Screen.Home){finish()}else closeMenu();return}
            rememberFocus()
            if(current==Screen.Home){if(list.computeVerticalScrollOffset()>0){list.smoothScrollToPosition(0)}else openMenu()}
            else if(!model.back())finish()}})
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{render(it)}} }
        if(savedInstanceState==null && prefs.getBoolean("welcomed",false))model.open(Screen.Home,false)
    }
    private fun rememberFocus(){
        list.findFocus()?.let{v->list.findContainingItemView(v)?.tag as?String}?.let{focusMemory[screenKey(current)]=it}
        val first=grid.findFirstVisibleItemPosition()
        scrollMemory[screenKey(current)]=first to (grid.findViewByPosition(first)?.top?:list.paddingTop)
    }
    private fun navigate(index:Int){
        rememberFocus()
        when(index){0->model.open(Screen.Home);1->model.open(Screen.Catalog());2->search();3->library()}
    }
    private fun search(){
        val input=EditText(this).apply{hint="Название аниме";isSingleLine=true;imeOptions=EditorInfo.IME_ACTION_SEARCH}
        val dialog=AlertDialog.Builder(this).setTitle("Поиск").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Найти"){_,_->model.open(Screen.Catalog(input.text.toString().trim()))}.create()
        input.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){model.open(Screen.Catalog(input.text.toString().trim()));dialog.dismiss();true}else false}
        dialog.show();input.requestFocus()
    }
    private fun library(){
        val items=JSONArray(prefs.getString("library","[]"))
        if(items.length()==0){Toast.makeText(this,"Библиотека пуста. Добавьте тайтл на его странице.",Toast.LENGTH_LONG).show();return}
        val values=(0 until items.length()).map{items.getJSONObject(it)}
        AlertDialog.Builder(this).setTitle("Моя библиотека · на этом телевизоре").setItems(values.map{it.getString("title")}.toTypedArray()){_,i->model.open(Screen.Detail(values[i].getInt("id")))}.setNegativeButton("Закрыть",null).show()
    }
    private fun favorite(release:Release){
        val old=JSONArray(prefs.getString("library","[]"));val next=JSONArray();var found=false
        for(i in 0 until old.length()){val item=old.getJSONObject(i);if(item.getInt("id")==release.id)found=true else next.put(item)}
        if(!found)next.put(JSONObject().put("id",release.id).put("title",release.title))
        prefs.edit().putString("library",next.toString()).apply()
        Toast.makeText(this,if(found)"Удалено из библиотеки"else"Добавлено в библиотеку",Toast.LENGTH_SHORT).show()
    }
    private fun quickActions(item:Release){
        val resume=prefs.getString("last:${item.id}",null)!=null
        val library=JSONArray(prefs.getString("library","[]"))
        val added=(0 until library.length()).any{library.getJSONObject(it).getInt("id")==item.id}
        AlertDialog.Builder(this).setTitle(item.title).setItems(arrayOf(if(resume)"Продолжить просмотр"else"Смотреть", if(added)"Убрать из библиотеки"else"В библиотеку", "Подробнее")){_,choice->
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
        }.show()
    }
    private fun play(episode:Episode){
        val release=model.state.value.detail?:return
        if(episode.streams.isEmpty()){Toast.makeText(this,"Для этой серии нет доступного HLS-потока",Toast.LENGTH_LONG).show();return}
        rememberFocus()
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("release",release.id).putExtra("episode",episode.id))
    }
    private fun selectTab(index:Int){enterContent=true;val previous=model.state.value;model.tab(index);if(previous==model.state.value)render(model.state.value)}
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
    private fun showTorrents(releaseId:Int){
        val loading=AlertDialog.Builder(this).setMessage("Загрузка раздач…").setNegativeButton("Отмена",null).create()
        val task=lifecycleScope.launch {
            try{
                val items=model.api.releaseTorrents(releaseId)
                loading.dismiss()
                if(items.isEmpty())AlertDialog.Builder(this@MainActivity).setMessage("Раздачи пока недоступны").setPositiveButton("Закрыть",null).show()
                else AlertDialog.Builder(this@MainActivity).setTitle("Торренты").setItems(items.map{listOf(it.title,it.description).filter(String::isNotBlank).joinToString("\n")}.toTypedArray()){_,index->openLink(items[index].magnet)}.setNegativeButton("Закрыть",null).show()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){loading.dismiss();Toast.makeText(this@MainActivity,"Не удалось загрузить раздачи",Toast.LENGTH_LONG).show()}
        }
        loading.setOnCancelListener{task.cancel()}
        loading.setOnShowListener{loading.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{task.cancel();loading.dismiss()}}
        loading.show()
    }
    private fun openSection(kind:HomeSection){rememberFocus();model.open(Screen.Section(kind))}
    private fun releaseCards(items:List<Release>,key:String)=items.distinctBy{it.id}.map{Row("$key:release:${it.id}",Kind.POSTER,release=it,selected=if(key.startsWith("latest",true)||key.startsWith("schedule",true))1 else 0)}
    private fun featureCards(items:List<Feature>,kind:HomeSection)=items.map{feature->
        Row("${kind.name}:${feature.id}",if(kind==HomeSection.GENRES)Kind.GENRE else Kind.FRANCHISE,feature.title,feature.subtitle,image=feature.image,click={
            rememberFocus()
            if(kind==HomeSection.FRANCHISES)model.open(Screen.Franchise(feature.id,feature.title))
            else model.open(Screen.Catalog(genre=feature.id.toIntOrNull(),title=feature.title))
        })
    }
    private fun videoCards(items:List<HomeVideo>)=items.map{video->Row("video:${video.id}",Kind.FEATURE,video.title,video.subtitle,image=video.image,click={openLink(video.url.takeIf{it.startsWith("https://")})})}
    private fun homeShelf(kind:HomeSection,items:List<Row>,wide:Boolean=false,key:String=kind.name):Row {
        val style=when(kind){HomeSection.LATEST,HomeSection.SCHEDULE->4;HomeSection.GENRES->2;HomeSection.FRANCHISES->3;else->if(wide)1 else 0}
        val state=model.state.value
        val cards=if(kind==HomeSection.GENRES||kind==HomeSection.FRANCHISES)
            discoveryShelfCards(key,kind.title,items,style,kind in state.refreshing,kind in state.refreshErrors,{model.refreshFeatures(kind)},{openSection(kind)})
        else shelfCards(key,kind.title,items,wide,style){openSection(kind)}
        return Row("shelf:$key",Kind.SHELF,selected=style,cards=cards)
    }
    private fun render(state:UiState){
        val retainedFocus = if(current==state.screen) list.findFocus()?.let { list.findContainingItemView(it)?.tag as? String } else null
        current=state.screen
        menu.visibility=if(current==Screen.Welcome)View.GONE else View.VISIBLE
        val rows=mutableListOf<Row>()
        if(state.screen==Screen.Welcome){
            rows+=Row("welcome",Kind.HEADING,"AniLiberty","Так звучит аниме")
            rows+=Row("intro",Kind.TEXT,"Аниме на большом экране","Управление пультом. Просмотр без обязательного входа.")
            rows+=Row("start",Kind.ACTION,"Начать просмотр",click={prefs.edit().putBoolean("welcomed",true).apply();model.open(Screen.Home,false)})
        }else{
            
            if(state.loading)rows+=Row("loading",Kind.TEXT,"Загрузка…")
            else if(state.error!=null)rows+=Row("retry",Kind.ACTION,"Не удалось загрузить",state.error,click=model::retry)
            else when(val screen=state.screen){
                Screen.Home->{
                    val home=state.home?:return
                    home.promotions?.firstOrNull()?.let { promotion ->
                        rows+=Row("promotion:${promotion.id}",Kind.SPOTLIGHT,release=promotion.release,image=promotion.image)
                    }
                    rows+=Row("latest-heading",Kind.HEADING,"Новые эпизоды",selected=1)
                    rows+=homeShelf(HomeSection.LATEST,releaseCards(home.latest,"latest"))
                    rows+=Row("schedule-days",Kind.DAYS,"Расписание релизов",selected=state.homeDay)
                    val schedule=home.schedule?.getOrNull(state.homeDay)
                    if(schedule==null)rows+=Row("schedule-retry",Kind.ACTION,"Не удалось загрузить расписание · Повторить",click=model::retry)
                    else rows+=homeShelf(HomeSection.SCHEDULE,releaseCards(schedule,"schedule:${state.homeDay}"),key="schedule:${state.homeDay}")
                    rows+=Row("franchises-heading",Kind.HEADING,"Франшизы",selected=1)
                    rows+=homeShelf(HomeSection.FRANCHISES,featureCards(home.franchises.orEmpty(),HomeSection.FRANCHISES),true)
                    rows+=Row("genres-heading",Kind.HEADING,"Жанры",selected=1)
                    rows+=homeShelf(HomeSection.GENRES,featureCards(home.genres.orEmpty(),HomeSection.GENRES),true)
                    rows+=Row("videos-heading",Kind.HEADING,"Видео команды",selected=1)
                    if(home.videos==null)rows+=Row("videos-retry",Kind.ACTION,"Не удалось загрузить видео · Повторить",click=model::retry)
                    else rows+=homeShelf(HomeSection.VIDEOS,videoCards(home.videos),true)
                    rows+=Row("refresh-home",Kind.ACTION,"Обновить главную",click={rememberFocus();model.retry()})
                    rows+=Row("support",Kind.ACTION,"Поддержать AniLiberty",click={openLink("https://www.aniliberty.top/support")})
                }
                is Screen.Section->{
                    val data=state.section?:return
                    rows+=Row("section-heading",Kind.HEADING,screen.kind.title)
                    val cards=when(screen.kind){
                        HomeSection.LATEST,HomeSection.SCHEDULE->releaseCards(data.releases,screen.kind.name)
                        HomeSection.FRANCHISES,HomeSection.GENRES->featureCards(data.features,screen.kind)
                        HomeSection.VIDEOS->videoCards(data.videos)
                    }
                    rows+=cards
                    if(data.more)rows+=Row("section-next",Kind.ACTION,"Показать больше",click={
                        rememberFocus()
                        val next=screen.copy(page=screen.page+1)
                        cards.lastOrNull()?.key?.let{focusMemory[next]=it}
                        scrollMemory[screen]?.let{scrollMemory[next]=it}
                        model.open(next,false)
                    })
                    if(cards.isEmpty())rows+=Row("section-empty",Kind.ACTION,"Пока ничего нет · Вернуться",click={model.back()})
                }

                is Screen.Franchise->{
                    rows+=Row("franchise-heading",Kind.HEADING,screen.title,"Порядок просмотра")
                    rows+=state.releases.map{Row("release:${it.id}",Kind.POSTER,release=it)}
                    if(state.releases.isEmpty())rows+=Row("empty",Kind.TEXT,"Релизы пока не указаны")
                }
                is Screen.Catalog->{
                    rows+=Row("heading",Kind.HEADING,screen.title?:if(screen.query.isBlank())"Каталог"else"Результаты: ${screen.query}","Страница ${screen.page}")
                    rows+=state.releases.map{Row("release:${it.id}",Kind.POSTER,release=it)}
                    if(state.releases.isEmpty())rows+=Row("empty",Kind.TEXT,"Ничего не найдено")
                    if(state.more)rows+=Row("next",Kind.ACTION,"Следующая страница",click={model.open(screen.copy(page=screen.page+1))})
                    if(screen.page>1)rows+=Row("previous",Kind.ACTION,"Предыдущая страница",click={model.open(screen.copy(page=screen.page-1))})
                }
                is Screen.Detail->{
                    val r=state.detail?:return
                    rows+=Row("hero",Kind.HERO,release=r)
                    val description=android.text.Html.fromHtml(r.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    if(description.isNotBlank())rows+=Row("synopsis",Kind.ACTION,description.take(220).let{if(it.length<description.length)"$it…"else it},"Описание",click={AlertDialog.Builder(this).setTitle(r.title).setMessage(description).setPositiveButton("Закрыть",null).show()})
                    rows+=Row("more",Kind.ACTION,"Ещё",click={
                        AlertDialog.Builder(this).setTitle(r.title).setItems(arrayOf("Открыть страницу на сайте","Торренты")){_,choice->
                            if(choice==0)openLink("https://www.aniliberty.top/anime/releases/release/${r.alias}/episodes")
                            else showTorrents(r.id)
                        }.show()
                    })
                    rows+=Row("tabs",Kind.TABS,selected=screen.tab)
                    when(screen.tab){
                        0->{rows+=r.episodes.map{Row("episode:${it.id}",Kind.EPISODE,episode=it)};if(r.episodes.isEmpty())rows+=Row("empty",Kind.TEXT,"Эпизоды пока недоступны")}
                        1->{
                            val franchises=state.discovery?.franchises
                            when {
                                state.discoveryLoading -> rows+=Row("related-loading",Kind.TEXT,"Загрузка франшизы…")
                                franchises==null -> rows+=Row("related-retry",Kind.ACTION,"Не удалось загрузить франшизу · Повторить",click=model::retryDiscovery)
                                franchises.isEmpty() -> rows+=Row("related-empty",Kind.TEXT,"Релиз не связан ни с одной франшизой")
                                else -> franchises.forEach { group ->
                                    rows+=Row("franchise-heading:${group.id}",Kind.HEADING,group.title,"Порядок просмотра")
                                    rows+=group.releases.map { part -> Row("franchise-part:${part.id}",Kind.POSTER,release=part) }
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
        adapter.submit(rows)
        if(enterDay){
            enterDay=false
            val index=rows.indexOfFirst{it.key.startsWith("shelf:schedule:")}.takeIf{it>=0}?:rows.indexOfFirst{it.key=="schedule-days"}
            if(index>=0){list.doOnNextLayout{focusItem(list.findViewHolderForAdapterPosition(index)?.itemView)};grid.scrollToPositionWithOffset(index,list.dp(64))}
        }else if(enterContent && adapter.contentStart in rows.indices){
            enterContent=false
            val index=(adapter.contentStart until rows.size).firstOrNull { rows[it].kind != Kind.HEADING } ?: adapter.contentStart
            list.doOnNextLayout{list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()}
            grid.scrollToPositionWithOffset(index,list.dp(64))
        }else{
            val key=retainedFocus?:focusMemory[screenKey(state.screen)]
            val index=rows.indexOfFirst{it.key==key}.takeIf{it>=0}
                ?: if(key!=null && adapter.contentStart>=0) (adapter.contentStart until rows.size).firstOrNull{rows[it].kind!=Kind.HEADING}?:adapter.contentStart
                else rows.indexOfFirst{it.kind in listOf(Kind.ACTION,Kind.POSTER,Kind.HERO,Kind.SPOTLIGHT,Kind.SHELF,Kind.FEATURE,Kind.GENRE,Kind.FRANCHISE,Kind.MORE)}
            if(index>=0&&!state.loading){list.doOnNextLayout{
                val item=list.findViewHolderForAdapterPosition(index)?.itemView
                focusItem(item)
            };val scroll=scrollMemory[screenKey(state.screen)]
                grid.scrollToPositionWithOffset(scroll?.first?:if(key==null)0 else index,scroll?.second?:if(key==null)0 else list.dp(12))}
        }
    }
}
