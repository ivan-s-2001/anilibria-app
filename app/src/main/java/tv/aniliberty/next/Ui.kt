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

const val RED = 0xFFFF3545.toInt()
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
    typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
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

enum class Kind { NAV, HERO, TABS, POSTER, EPISODE, HEADING, TEXT, ACTION }
data class Row(val key: String, val kind: Kind, val title: String = "", val subtitle: String = "", val release: Release? = null, val episode: Episode? = null, val selected: Int = 0, val click: (() -> Unit)? = null)

class PageAdapter(
    private val navigate: (Int) -> Unit,
    private val tab: (Int) -> Unit,
    private val play: (Episode) -> Unit,
    private val favorite: (Release) -> Unit,
    private val details: (Release) -> Unit,
    private val progress: (String) -> Pair<Long, Long>,
    private val lastEpisode: (Int) -> String?,
    private val returnToTabs: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<Row> = emptyList(); private set
    var tabStart: Int = -1; private set
    var contentStart: Int = -1; private set
    fun submit(value: List<Row>) {
        rows = value; tabStart = rows.indexOfFirst { it.kind == Kind.TABS }; contentStart = if (tabStart >= 0) tabStart + 1 else -1
        notifyDataSetChanged()
    }
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = rows[position].kind.ordinal
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val view: View = when (Kind.entries[type]) {
            Kind.NAV, Kind.TABS -> HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            Kind.HERO -> LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Kind.POSTER -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; focusBackground()
                setPadding(dp(4), dp(4), dp(4), dp(8))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, dp(208)))
                addView(TextView(context).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(44)))
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
        view.layoutParams = RecyclerView.LayoutParams(-1, if (Kind.entries[type] == Kind.EPISODE) view.dp(160) else -2).apply {
            val card = Kind.entries[type] in listOf(Kind.POSTER,Kind.EPISODE)
            setMargins(view.dp(if (card) 6 else 8), view.dp(if (card) 6 else 8),view.dp(if (card) 6 else 8),view.dp(if (card) 6 else 8))
        }
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]; val view = holder.itemView
        view.tag = row.key
        view.setOnKeyListener { _, key, event ->
            if (contentStart >= 0 && position in contentStart..(contentStart + 2) && row.kind == Kind.EPISODE && key == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_UP) returnToTabs(); true
            } else false
        }
        when (row.kind) {
            Kind.NAV -> {
                val line = (view as HorizontalScrollView).getChildAt(0) as LinearLayout; line.removeAllViews()
                line.addView(TextView(view.context).apply { label("AniLiberty",22f,WHITE,true); setPadding(0,0,dp(28),0) })
                listOf("Главная", "Каталог", "Поиск", "Моя библиотека").forEachIndexed { i, text ->
                    line.addView(action(line,text,click={navigate(i)}), LinearLayout.LayoutParams(-2,view.dp(46)).apply { marginEnd=view.dp(8) })
                }
            }
            Kind.TABS -> {
                val line = (view as HorizontalScrollView).getChildAt(0) as LinearLayout; line.removeAllViews()
                val labels = listOf("Эпизоды", "Связанное", "Комментарии", "Команда", "Торренты")
                val icons = listOf(R.drawable.ic_episodes, R.drawable.ic_related, R.drawable.ic_comments, R.drawable.ic_team, R.drawable.ic_download)
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
                box.addView(ImageView(view.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;load(r.poster) },LinearLayout.LayoutParams(view.dp(174),view.dp(250)))
                val info=LinearLayout(view.context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(28),0,0,0)}
                box.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                fun text(value:String,size:Float,color:Int=WHITE,bold:Boolean=false,lines:Int=3) {
                    if(value.isBlank())return
                    info.addView(TextView(view.context).apply { label(value,size,color,bold); maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=view.dp(8)})
                }
                text(r.title,29f,WHITE,true,2);text(r.english,15f,MUTED,false,1);text(r.metadata,16f);text(r.genres.joinToString(" · "),15f,MUTED,false,2)
                val controls=LinearLayout(view.context)
                info.addView(controls,LinearLayout.LayoutParams(-1,view.dp(48)).apply{topMargin=view.dp(10)})
                val episode = r.episodes.firstOrNull { it.id == lastEpisode(r.id) } ?: r.episodes.firstOrNull()
                if(episode!=null && !r.blocked) controls.addView(action(controls,if(progress(episode.id).first>0) "Продолжить" else "Смотреть",true,R.drawable.ic_play){play(episode)},LinearLayout.LayoutParams(-2,-1).apply{marginEnd=view.dp(12)})
                controls.addView(action(controls,"В библиотеку",icon=R.drawable.ic_favorite){favorite(r)},LinearLayout.LayoutParams(-2,-1))
                if(r.blocked)text("Просмотр ограничен правообладателем или регионом",15f,MUTED)
            }
            Kind.POSTER -> {
                val box=view as LinearLayout; val r=row.release!!
                (box.getChildAt(0)as ImageView).load(r.poster)
                (box.getChildAt(1)as TextView).label(r.title,15f)
                box.contentDescription=r.title;box.setOnClickListener{details(r)}
            }
            Kind.EPISODE -> {
                val box=view as FrameLayout;val e=row.episode!!
                (box.getChildAt(0)as ImageView).load(e.preview)
                val watched=progress(e.id)
                (box.getChildAt(2)as TextView).label("${e.label}\n${if(e.duration>0)"%d:%02d".format(e.duration/60,e.duration%60) else ""}${if(watched.first>0)" · Продолжить" else ""}",17f,WHITE,true)
                (box.getChildAt(3)as android.widget.ProgressBar).progress=if(watched.second>0)(watched.first*1000/watched.second).toInt().coerceIn(0,1000)else 0
                box.contentDescription=e.label;box.setOnClickListener{play(e)}
            }
            Kind.HEADING,Kind.TEXT,Kind.ACTION -> {
                val t=view as TextView
                t.label(listOf(row.title,row.subtitle).filter(String::isNotBlank).joinToString("\n"),if(row.kind==Kind.HEADING)22f else 16f,if(row.kind==Kind.TEXT)MUTED else WHITE,row.kind==Kind.HEADING)
                t.setPadding(0,view.dp(8),0,view.dp(8));t.isFocusable=row.click!=null || (contentStart>=0 && position>=contentStart);t.isClickable=row.click!=null
                t.background=null
                if(row.click!=null){t.focusBackground();t.setPadding(view.dp(12),view.dp(10),view.dp(12),view.dp(10))}
                t.setOnClickListener(row.click?.let{callback->View.OnClickListener{callback()}})
                if(position==contentStart) t.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_DPAD_UP){if(event.action==KeyEvent.ACTION_UP)returnToTabs();true}else false}
            }
        }
    }
    override fun onViewRecycled(holder:RecyclerView.ViewHolder){
        val view=holder.itemView
        if(view is FrameLayout)(view.getChildAt(0)as?ImageView)?.load(null)
        if(view is LinearLayout && view.childCount>0)(view.getChildAt(0)as?ImageView)?.load(null)
        view.setOnClickListener(null);view.setOnKeyListener(null)
    }
}
