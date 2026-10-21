package tv.aniliberty.next

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load

const val RED = 0xFFFE3635.toInt()
const val WHITE = 0xFFF2F2F2.toInt()
const val MUTED = 0xFF999999.toInt()
fun View.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
fun shape(color: Int, radius: Float = 6f, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke != 0) setStroke(2, stroke)
}
fun View.focusBackground(primary: Boolean = false) {
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) WHITE else 0xFF292929.toInt(), dp(6).toFloat(), if (primary) 0 else WHITE))
        addState(intArrayOf(), shape(if (primary) RED else Color.TRANSPARENT, dp(6).toFloat()))
    }
}
fun TextView.label(value: String, size: Float, color: Int = WHITE, bold: Boolean = false) {
    text = value; textSize = size; setTextColor(color)
    typeface = resources.getFont(if (bold) R.font.montserrat else R.font.inter)
}
fun action(parent: ViewGroup, title: String, primary: Boolean = false, icon: Int = 0, click: () -> Unit): TextView = TextView(parent.context).apply {
    label(title, 16f, WHITE, true); gravity = Gravity.CENTER
    isFocusable = true; isClickable = true; minimumHeight = dp(46)
    setPadding(dp(16), 0, dp(16), 0); focusBackground(primary)
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(if (primary) Color.BLACK else WHITE, WHITE)))
    if (icon != 0) {
        val drawable = context.getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(20), dp(20)); setTintList(textColors) }
        setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = dp(8)
    }
    setOnClickListener { click() }
}

enum class Kind { NAV, HERO, TABS, POSTER, EPISODE, HEADING, TEXT, ACTION, SPOTLIGHT, FEATURE, DAYS, SHELF, MORE }
data class Row(val key: String, val kind: Kind, val title: String = "", val subtitle: String = "", val release: Release? = null, val episode: Episode? = null, val selected: Int = 0, val click: (() -> Unit)? = null, val image: String? = null, val items: List<Release> = emptyList(), val cards:List<Row> = emptyList())

class PageAdapter(
    private val navigate: (Int) -> Unit,
    private val tab: (Int) -> Unit,
    private val play: (Episode) -> Unit,
    private val favorite: (Release) -> Unit,
    private val details: (Release) -> Unit,
    private val progress: (String) -> Pair<Long, Long>,
    private val lastEpisode: (Int) -> String?,
    private val returnToTabs: () -> Unit,
    private val day: (Int) -> Unit = {},
    private val quickActions: (Release) -> Unit = {},
    private val shelfMode: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class ShelfPosition(val focusedKey:String?,val anchorKey:String?,val offset:Int)
    private val shelfPositions=mutableMapOf<String,ShelfPosition>()
    private fun saveShelf(shelf:RecyclerView) {
        val key=shelf.getTag(R.id.shelf_key) as?String?:return
        val child=shelf.adapter as?PageAdapter?:return
        val manager=shelf.layoutManager as?androidx.recyclerview.widget.LinearLayoutManager?:return
        val first=manager.findFirstVisibleItemPosition()
        val focus=shelf.findFocus()?.let{shelf.findContainingItemView(it)?.tag as?String}
        shelfPositions[key]=ShelfPosition(focus?:shelfPositions[key]?.focusedKey,child.rows.getOrNull(first)?.key,manager.findViewByPosition(first)?.left?:0)
    }
    fun restoreShelf(shelf:RecyclerView, requestFocus:Boolean=true) {
        val key=shelf.getTag(R.id.shelf_key) as?String?:return
        val child=shelf.adapter as?PageAdapter?:return
        val memory=shelfPositions[key]
        val index=child.rows.indexOfFirst{it.key==memory?.focusedKey}.takeIf{it>=0}?:0
        val manager=shelf.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val anchor=child.rows.indexOfFirst{it.key==memory?.anchorKey}.takeIf{it>=0}?:index
        manager.scrollToPositionWithOffset(anchor,memory?.offset?:0)
        if(requestFocus)shelf.post {
            val target=shelf.findViewHolderForAdapterPosition(index)?.itemView
            if(target!=null)target.requestFocus()else {manager.scrollToPosition(index);shelf.post{shelf.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()}}
        }
    }
    var rows: List<Row> = emptyList(); private set
    private val attachedShelves=mutableSetOf<RecyclerView>()
    var tabStart: Int = -1; private set
    var contentStart: Int = -1; private set
    fun submit(value: List<Row>) {
        // Snapshot nested lists before the outer adapter is rebound.
        attachedShelves.forEach(::saveShelf)
        rows = value; tabStart = rows.indexOfFirst { it.kind == Kind.TABS }; contentStart = if (tabStart >= 0) tabStart + 1 else -1
        notifyDataSetChanged()
    }
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = rows[position].kind.ordinal
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val view: View = when (Kind.entries[type]) {
            Kind.NAV, Kind.TABS, Kind.DAYS -> HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            Kind.SHELF -> RecyclerView(context).apply {
                layoutManager=androidx.recyclerview.widget.LinearLayoutManager(context,RecyclerView.HORIZONTAL,false)
                clipToPadding=false;itemAnimator=null;setPadding(dp(4),dp(4),dp(4),dp(4))
            }
            Kind.HERO -> LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Kind.HEADING -> LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(4))
                addView(TextView(context));addView(TextView(context),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
            }
            Kind.POSTER, Kind.SPOTLIGHT, Kind.FEATURE -> FrameLayout(context).apply {
                isFocusable = true; isClickable = true; focusBackground(); clipToOutline=true; setPadding(dp(3),dp(3),dp(3),dp(3))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP },FrameLayout.LayoutParams(-1,-1))
                addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xF5121212.toInt(),0x10121212)) },FrameLayout.LayoutParams(-1,-1))
                addView(LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12))
                    addView(TextView(context).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
                    addView(TextView(context).apply{maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
                },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            }
            Kind.EPISODE -> FrameLayout(context).apply {
                isFocusable = true; isClickable = true; focusBackground(); setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF0101010.toInt(),0x18101010)) }, FrameLayout.LayoutParams(-1,-1))
                addView(TextView(context).apply { setPadding(dp(12),dp(8),dp(12),dp(12)); maxLines = 3 }, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
                addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(RED) }, FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
            }
            else -> TextView(context)
        }
        view.layoutParams = RecyclerView.LayoutParams(if(shelfMode)view.dp(if(Kind.entries[type]==Kind.FEATURE)232 else 144)else -1, when(Kind.entries[type]){Kind.EPISODE->view.dp(160);Kind.POSTER->view.dp(270);Kind.MORE->view.dp(270);Kind.SHELF->view.dp(286);Kind.SPOTLIGHT->view.dp(160);Kind.FEATURE->view.dp(204);else->-2}).apply {
            val card = Kind.entries[type] in listOf(Kind.POSTER,Kind.EPISODE,Kind.FEATURE)
            setMargins(view.dp(4), view.dp(4),view.dp(4),view.dp(4))
        }
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]; val view = holder.itemView
        view.tag = row.key
        val firstContent = if(contentStart>=0) (contentStart until rows.size).firstOrNull{rows[it].kind!=Kind.HEADING} else null
        val firstRowCount = when(firstContent?.let{rows[it].kind}){Kind.POSTER->5;Kind.EPISODE->3;else->1}
        view.setOnKeyListener { _, key, event ->
            if (firstContent != null && position in firstContent until (firstContent+firstRowCount) && key == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_UP) returnToTabs(); true
            } else false
        }
        when (row.kind) {
            Kind.SHELF -> {
                val shelf=view as RecyclerView
                val oldKey=shelf.getTag(R.id.shelf_key) as? String
                if(oldKey!=row.key){shelf.adapter=PageAdapter(navigate,tab,play,favorite,details,progress,lastEpisode,returnToTabs,day,quickActions,true);shelf.scrollToPosition(0)}
                shelf.setTag(R.id.shelf_key,row.key)
                shelf.layoutParams=shelf.layoutParams.apply{height=view.dp(if(row.selected==1)220 else 286)}
                val cards=if(row.cards.isNotEmpty())row.cards else row.items.map{Row("${row.key}:release:${it.id}",Kind.POSTER,release=it)}
                (shelf.adapter as PageAdapter).submit(cards)
                restoreShelf(shelf,false)
            }
            Kind.MORE -> {
                val text=view as TextView
                text.layoutParams=text.layoutParams.apply{if(shelfMode){width=view.dp(if(row.selected==1)232 else 144);height=view.dp(if(row.selected==1)204 else 270)}}
                text.label("Все\n${row.title}",16f,WHITE,true)
                val arrow=view.context.getDrawable(R.drawable.ic_arrow_right)!!.mutate().apply{setBounds(0,0,view.dp(32),view.dp(32));setTint(WHITE)}
                text.setCompoundDrawables(null,arrow,null,null);text.compoundDrawablePadding=view.dp(12)
                text.gravity=Gravity.CENTER;text.isFocusable=true;text.isClickable=true
                text.focusBackground();text.contentDescription="Открыть раздел ${row.title} полностью"
                text.setOnClickListener{row.click?.invoke()}
            }
            Kind.DAYS -> {
                val line=(view as HorizontalScrollView).getChildAt(0) as LinearLayout;line.removeAllViews()
                listOf("Вчера","Сегодня","Завтра").forEachIndexed { index, title ->
                    line.addView(action(line,title,click={day(index)}).apply { setTextColor(if(index==row.selected)RED else MUTED) },LinearLayout.LayoutParams(-2,view.dp(42)).apply{marginEnd=view.dp(8)})
                }
            }
            Kind.NAV -> {
                val line = (view as HorizontalScrollView).getChildAt(0) as LinearLayout; line.removeAllViews()
                line.addView(TextView(view.context).apply { label("AniLiberty",20f,WHITE,true); setPadding(0,0,dp(28),0) })
                listOf("Главная", "Каталог", "Поиск", "Моя библиотека").forEachIndexed { i, text ->
                    line.addView(action(line,text,click={navigate(i)}).apply {
                        background=StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused),shape(0xFF292929.toInt(),dp(4).toFloat()))
                            addState(intArrayOf(),shape(Color.TRANSPARENT))
                        }
                    }, LinearLayout.LayoutParams(-2,view.dp(40)).apply { marginEnd=view.dp(8) })
                }
            }
            Kind.TABS -> {
                val line = (view as HorizontalScrollView).getChildAt(0) as LinearLayout; line.removeAllViews()
                val labels = listOf("Эпизоды", "Связанное", "Рекомендуем", "Работа над релизом")
                val icons = listOf(R.drawable.ic_episodes, R.drawable.ic_related, R.drawable.ic_favorite, R.drawable.ic_team)
                labels.forEachIndexed { i, text ->
                    val item = action(line,text,icon=icons[i],click={tab(i)})
                    item.textSize=15f; item.setTextColor(if(i==row.selected)RED else MUTED)
                    item.setOnFocusChangeListener { _, focused -> item.setTextColor(if(focused)WHITE else if(i==row.selected)RED else MUTED) }
                    item.background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused),shape(0xFF242424.toInt(),view.dp(4).toFloat()))
                        addState(intArrayOf(),shape(Color.TRANSPARENT))
                    }
                    item.setOnKeyListener { _, key, event ->
                        if(key==KeyEvent.KEYCODE_DPAD_DOWN) { if(event.action==KeyEvent.ACTION_UP)tab(i);true } else false
                    }
                    line.addView(LinearLayout(view.context).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(item,LinearLayout.LayoutParams(-2,view.dp(42)))
                        addView(View(view.context).apply { setBackgroundColor(if(i==row.selected)RED else Color.TRANSPARENT) },LinearLayout.LayoutParams(-1,view.dp(2)))
                    },LinearLayout.LayoutParams(-2,-2).apply{marginEnd=view.dp(8)})
                }
            }
            Kind.HERO -> {
                val r=row.release!!; val box=view as LinearLayout;box.removeAllViews();box.setPadding(0,view.dp(12),0,view.dp(16))
                box.addView(ImageView(view.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;load(r.poster) },LinearLayout.LayoutParams(view.dp(128),view.dp(184)))
                val info=LinearLayout(view.context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(28),0,0,0)}
                box.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                fun text(value:String,size:Float,color:Int=WHITE,bold:Boolean=false,lines:Int=3) {
                    if(value.isBlank())return
                    info.addView(TextView(view.context).apply { label(value,size,color,bold); maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=view.dp(8)})
                }
                text(r.title,24f,WHITE,true,2);text(r.english,15f,MUTED,false,1);text(r.metadata,16f);text(r.genres.joinToString(" · "),15f,MUTED,false,2);text(r.ratings.joinToString(" · "){"${it.source} ${"%.1f".format(it.value)}"},14f,MUTED,false,1)
                val controls=LinearLayout(view.context)
                info.addView(controls,LinearLayout.LayoutParams(-1,view.dp(48)).apply{topMargin=view.dp(10)})
                val episode = r.episodes.firstOrNull { it.id == lastEpisode(r.id) } ?: r.episodes.firstOrNull()
                if(episode!=null && !r.blocked) controls.addView(action(controls,if(progress(episode.id).first>0) "Продолжить" else "Смотреть",true,R.drawable.ic_play){play(episode)},LinearLayout.LayoutParams(-2,-1).apply{marginEnd=view.dp(12)})
                controls.addView(action(controls,"В библиотеку",icon=R.drawable.ic_favorite){favorite(r)},LinearLayout.LayoutParams(-2,-1))
                if(r.blocked)text("Просмотр ограничен правообладателем или регионом",15f,MUTED)
            }
            Kind.SPOTLIGHT,Kind.FEATURE,Kind.POSTER -> {
                val box=view as FrameLayout
                val content=box.getChildAt(2)as LinearLayout
                val title=content.getChildAt(0)as TextView
                val subtitle=content.getChildAt(1)as TextView
                val release=row.release
                val poster=row.kind==Kind.POSTER
                val spotlight=row.kind==Kind.SPOTLIGHT
                val image=box.getChildAt(0)as ImageView
                image.layoutParams=FrameLayout.LayoutParams(-1,if(poster)view.dp(198)else if(spotlight)-1 else view.dp(130),Gravity.TOP)
                image.scaleType=if(poster)ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                image.background=shape(0xFF212121.toInt(),view.dp(6).toFloat())
                image.clipToOutline=true
                image.load(if(poster)release?.poster else row.image)
                box.getChildAt(1).visibility=if(spotlight)View.VISIBLE else View.GONE
                title.label(release?.title?:row.title,if(spotlight)24f else 14f,WHITE,true)
                title.maxLines=2
                val short=if(release!=null)listOf(release.year,release.type,release.latestEpisode?.let{"Эп. ${number(it.ordinal)}"}.orEmpty()).filter(String::isNotBlank).joinToString(" · ")else row.subtitle
                val full=if(release!=null)listOf(release.metadata,release.genres.take(2).joinToString(" · ")).filter(String::isNotBlank).joinToString(" · ")else row.subtitle
                subtitle.label(if(box.hasFocus())full else short,12f,MUTED)
                subtitle.maxLines=1;subtitle.visibility=if(short.isBlank()&&full.isBlank())View.GONE else View.VISIBLE
                if(spotlight){
                    content.setPadding(view.dp(24),view.dp(12),view.dp(24),view.dp(12))
                    content.layoutParams=FrameLayout.LayoutParams((view.resources.displayMetrics.widthPixels*.6f).toInt(),-2,Gravity.BOTTOM or Gravity.START)
                    subtitle.text=listOf(release?.metadata.orEmpty(),"Открыть релиз").filter(String::isNotBlank).joinToString("\n")
                }else{
                    content.layoutParams=FrameLayout.LayoutParams(-1,view.dp(66),Gravity.BOTTOM)
                    content.setPadding(view.dp(4),view.dp(6),view.dp(4),0)
                }
                content.setBackgroundColor(Color.TRANSPARENT)
                box.setOnFocusChangeListener{_,focused->
                    if(!spotlight){subtitle.text=if(focused)full else short;title.setTextColor(if(focused)WHITE else 0xFFDDDDDD.toInt())}
                    box.animate().scaleX(if(focused)1.025f else 1f).scaleY(if(focused)1.025f else 1f).setDuration(140).start()
                }
                box.contentDescription=listOf(release?.title?:row.title,short).filter(String::isNotBlank).joinToString(". ")
                box.setOnClickListener{if(row.click!=null)row.click.invoke()else if(release!=null)details(release)}
                box.setOnLongClickListener(if(release!=null)View.OnLongClickListener{quickActions(release);true}else null)
                box.isLongClickable=release!=null
            }
            Kind.EPISODE -> {
                val box=view as FrameLayout;val e=row.episode!!
                (box.getChildAt(0)as ImageView).load(e.preview)
                val watched=progress(e.id)
                (box.getChildAt(2)as TextView).label("${e.label}\n${if(e.duration>0)"%d:%02d".format(e.duration/60,e.duration%60) else ""}${if(watched.first>0)" · Продолжить" else ""}",17f,WHITE,true)
                (box.getChildAt(3)as android.widget.ProgressBar).progress=if(watched.second>0)(watched.first*1000/watched.second).toInt().coerceIn(0,1000)else 0
                box.contentDescription=e.label;box.setOnClickListener{play(e)}
            }
            Kind.HEADING -> {
                val box=view as LinearLayout
                (box.getChildAt(0)as TextView).label(row.title,18f,WHITE,true)
                (box.getChildAt(1)as TextView).apply{label(row.subtitle,12f,MUTED);visibility=if(row.subtitle.isBlank())View.GONE else View.VISIBLE}
                box.isFocusable=false;box.isClickable=false
            }
            Kind.TEXT,Kind.ACTION -> {
                val t=view as TextView
                t.label(listOf(row.title,row.subtitle).filter(String::isNotBlank).joinToString("\n"),if(row.kind==Kind.HEADING)22f else 16f,if(row.kind==Kind.TEXT)MUTED else WHITE,row.kind==Kind.HEADING)
                t.setPadding(0,view.dp(8),0,view.dp(8));t.isFocusable=row.click!=null || (contentStart>=0 && position>=contentStart);t.isClickable=row.click!=null
                t.background=null
                if(row.click!=null){t.focusBackground();t.setPadding(view.dp(12),view.dp(10),view.dp(12),view.dp(10))}
                t.setOnClickListener(row.click?.let{callback->View.OnClickListener{callback()}})
                t.isClickable = row.click != null
                if(position==contentStart) t.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_DPAD_UP){if(event.action==KeyEvent.ACTION_UP)returnToTabs();true}else false}
            }
        }
    }
    override fun onViewAttachedToWindow(holder:RecyclerView.ViewHolder){
        if(holder.itemView is RecyclerView)attachedShelves.add(holder.itemView as RecyclerView)
    }
    override fun onViewDetachedFromWindow(holder:RecyclerView.ViewHolder){
        (holder.itemView as?RecyclerView)?.let{saveShelf(it);attachedShelves.remove(it)}
    }
    override fun onViewRecycled(holder:RecyclerView.ViewHolder){
        (holder.itemView as?RecyclerView)?.let{saveShelf(it);attachedShelves.remove(it)}
        val view=holder.itemView
        if(view is FrameLayout)(view.getChildAt(0)as?ImageView)?.load(null)
        if(view is LinearLayout && view.childCount>0)(view.getChildAt(0)as?ImageView)?.load(null)
        view.animate().cancel();view.scaleX=1f;view.scaleY=1f
        view.setOnClickListener(null);view.setOnKeyListener(null);view.setOnLongClickListener(null);view.onFocusChangeListener=null
    }
}


/** Home previews contain at most nine real cards and one explicit full-section destination. */
fun shelfCards(key:String,title:String,items:List<Row>,wide:Boolean=false,openAll:()->Unit):List<Row> =
    items.take(9)+Row("$key:all",Kind.MORE,title,selected=if(wide)1 else 0,click=openAll)
