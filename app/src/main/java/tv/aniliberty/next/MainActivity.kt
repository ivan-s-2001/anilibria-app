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
    private var current: Screen = Screen.Welcome
    private var enterContent = false
    private var enterDay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = RecyclerView(this).apply {
            setBackgroundColor(0xFF121212.toInt()); setPadding(dp(40),dp(20),dp(40),dp(32));clipToPadding=false;itemAnimator=null
        }
        grid=GridLayoutManager(this,6);list.layoutManager=grid
        adapter=PageAdapter(::navigate,::selectTab,::play,::favorite,{rememberFocus();model.open(Screen.Detail(it.id))},
            { id -> prefs.getLong("position:$id",0) to prefs.getLong("duration:$id",0) },
            { id -> prefs.getString("last:$id",null) },::returnToTabs, { index -> enterDay=true;val previous=model.state.value;model.homeDay(index);if(previous==model.state.value)render(model.state.value) })
        grid.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=when(adapter.rows[position].kind){Kind.POSTER->1;Kind.EPISODE,Kind.FEATURE->2;else->6}}
        list.adapter=adapter;setContentView(list)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){rememberFocus();if(!model.back())finish()}})
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{render(it)}} }
        if(savedInstanceState==null && prefs.getBoolean("welcomed",false))model.open(Screen.Home,false)
    }
    private fun rememberFocus(){list.findFocus()?.let{v->list.findContainingItemView(v)?.tag as?String}?.let{focusMemory[current]=it}}
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
    private fun render(state:UiState){
        val retainedFocus = if(current==state.screen) list.findFocus()?.let { list.findContainingItemView(it)?.tag as? String } else null
        current=state.screen
        val rows=mutableListOf<Row>()
        if(state.screen==Screen.Welcome){
            rows+=Row("welcome",Kind.HEADING,"AniLiberty","Так звучит аниме")
            rows+=Row("intro",Kind.TEXT,"Аниме на большом экране","Управление пультом. Просмотр без обязательного входа.")
            rows+=Row("start",Kind.ACTION,"Начать просмотр",click={prefs.edit().putBoolean("welcomed",true).apply();model.open(Screen.Home,false)})
        }else{
            rows+=Row("nav",Kind.NAV)
            if(state.loading)rows+=Row("loading",Kind.TEXT,"Загрузка…")
            else if(state.error!=null)rows+=Row("retry",Kind.ACTION,"Не удалось загрузить",state.error,click=model::retry)
            else when(val screen=state.screen){
                Screen.Home->{
                    val home=state.home?:return
                    home.promotions?.firstOrNull()?.let { promotion ->
                        rows+=Row("promotion:${promotion.id}",Kind.SPOTLIGHT,release=promotion.release,image=promotion.image)
                    }
                    rows+=Row("heading",Kind.HEADING,"Новые эпизоды","Самые новые и свежие эпизоды в любимой озвучке")
                    rows+=home.latest.take(12).map{Row("release:${it.id}",Kind.POSTER,release=it)}
                    rows+=Row("support",Kind.ACTION,"Поддержите AniLiberty!","Поддержите команду проекта",click={openLink("https://www.aniliberty.top/support")})
                    rows+=Row("schedule-heading",Kind.HEADING,"Расписание релизов","Список релизов, над которыми команда трудится прямо сейчас")
                    rows+=Row("schedule-days",Kind.DAYS,selected=state.homeDay)
                    val schedule=home.schedule?.getOrNull(state.homeDay)
                    if(schedule==null)rows+=Row("schedule-retry",Kind.ACTION,"Не удалось загрузить расписание · Повторить",click=model::retry)
                    else if(schedule.isEmpty())rows+=Row("schedule-empty",Kind.TEXT,"На этот день релизы не запланированы")
                    else rows+=schedule.take(12).map{Row("schedule:${it.id}",Kind.POSTER,release=it)}
                    if(!home.videos.isNullOrEmpty()){
                        rows+=Row("videos-heading",Kind.HEADING,"Видео","Ролики команды AniLiberty")
                        rows+=home.videos.map{video->Row("video:${video.id}",Kind.FEATURE,video.title,video.subtitle,click={openLink(video.url.takeIf{it.startsWith("https://")})},image=video.image)}
                    }
                    rows+=Row("franchises-heading",Kind.HEADING,"Франшизы","Самые интересные и захватывающие франшизы в любимой озвучке")
                    if(home.franchises==null)rows+=Row("franchises-retry",Kind.ACTION,"Не удалось загрузить франшизы · Повторить",click=model::retry)
                    else rows+=home.franchises.map{feature->Row("franchise:${feature.id}",Kind.FEATURE,feature.title,feature.subtitle,click={rememberFocus();model.open(Screen.Franchise(feature.id,feature.title))},image=feature.image)}
                    rows+=Row("genres-heading",Kind.HEADING,"Жанры","Список жанров на любой вкус и цвет")
                    if(home.genres==null)rows+=Row("genres-retry",Kind.ACTION,"Не удалось загрузить жанры · Повторить",click=model::retry)
                    else rows+=home.genres.map{feature->Row("genre:${feature.id}",Kind.FEATURE,feature.title,feature.subtitle,click={rememberFocus();model.open(Screen.Catalog(genre=feature.id.toIntOrNull(),title=feature.title))},image=feature.image)}
                    rows+=Row("torrents-heading",Kind.HEADING,"Торренты","Самые свежие торренты: любимая озвучка + оригинальное качество")
                    if(home.torrents==null)rows+=Row("torrents-retry",Kind.ACTION,"Не удалось загрузить торренты · Повторить",click=model::retry)
                    else rows+=home.torrents.mapIndexed{index,torrent->Row("home-torrent:$index",Kind.ACTION,torrent.release?.title?:torrent.title,torrent.subtitle,click={openLink(torrent.magnet.takeIf{it.startsWith("magnet:")})})}
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
                        2->rows+=Row("comments",Kind.ACTION,"Обсуждение на сайте",click={openLink("https://aniliberty.top/anime/releases/${r.alias}")})
                        3->{rows+=r.credits.groupBy{it.role}.map{(role,people)->Row("role:$role",Kind.TEXT,role,people.joinToString(" · "){it.name})};if(r.credits.isEmpty())rows+=Row("empty",Kind.TEXT,"Участники пока не указаны")}
                        4->{rows+=r.torrents.mapIndexed{i,t->Row("torrent:$i",Kind.ACTION,t.label,t.description,click={openLink(t.magnet)})};if(r.torrents.isEmpty())rows+=Row("empty",Kind.TEXT,"Раздачи пока недоступны")}
                    }
                    rows+=Row("similar-heading",Kind.HEADING,"Похожие аниме","Рекомендации AniLiberty для этого тайтла")
                    val similar=state.discovery?.recommendations
                    when {
                        state.discoveryLoading -> rows+=Row("similar-loading",Kind.TEXT,"Подбираем аниме…")
                        similar==null -> rows+=Row("similar-retry",Kind.ACTION,"Не удалось загрузить рекомендации · Повторить",click=model::retryDiscovery)
                        similar.isEmpty() -> rows+=Row("similar-empty",Kind.TEXT,"Рекомендации пока отсутствуют")
                        else -> rows+=similar.map { item -> Row("similar:${item.id}",Kind.POSTER,release=item) }
                    }
                }
                else->Unit
            }
        }
        adapter.submit(rows)
        if(enterDay){
            enterDay=false
            val index=rows.indexOfFirst{it.key.startsWith("schedule:")}.takeIf{it>=0}?:rows.indexOfFirst{it.key=="schedule-days"}
            if(index>=0){list.doOnNextLayout{list.findViewHolderForAdapterPosition(index)?.itemView?.let{item->if(item.isFocusable)item.requestFocus()else if(item is ViewGroup)item.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()}};grid.scrollToPositionWithOffset(index,list.dp(64))}
        }else if(enterContent && adapter.contentStart in rows.indices){
            enterContent=false
            val index=(adapter.contentStart until rows.size).firstOrNull { rows[it].kind != Kind.HEADING } ?: adapter.contentStart
            list.doOnNextLayout{list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()}
            grid.scrollToPositionWithOffset(index,list.dp(64))
        }else{
            val key=retainedFocus?:focusMemory[state.screen]
            val index=rows.indexOfFirst{it.key==key}.takeIf{it>=0}
                ?: if(key!=null && adapter.contentStart>=0) (adapter.contentStart until rows.size).firstOrNull{rows[it].kind!=Kind.HEADING}?:adapter.contentStart
                else rows.indexOfFirst{it.kind in listOf(Kind.ACTION,Kind.POSTER,Kind.HERO,Kind.SPOTLIGHT)}
            if(index>=0&&!state.loading){list.doOnNextLayout{
                val item=list.findViewHolderForAdapterPosition(index)?.itemView
                if(item?.isFocusable==true)item.requestFocus()else if(item is ViewGroup)item.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
            };grid.scrollToPositionWithOffset(if(key==null)0 else index,if(key==null)0 else list.dp(12))}
        }
    }
}
