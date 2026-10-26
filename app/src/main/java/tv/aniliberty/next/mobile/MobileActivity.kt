package tv.aniliberty.next.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.aniliberty.next.*

/** Touch presentation over the same API, navigation state and viewing storage as TV. */
class MobileActivity : ComponentActivity() {
    private val model by viewModels<AppModel>()
    private val prefs by lazy { playbackPreferences(this) }
    private val history by lazy { ViewingHistory(prefs) }
    private val library by lazy { LocalLibrary(this) }
    private lateinit var list: RecyclerView
    private lateinit var adapter: MobileAdapter
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var loading: android.widget.ProgressBar
    private lateinit var navigation: LinearLayout
    private var launchJob: Job? = null
    private var current: Screen? = null
    private val scrollMemory = mutableMapOf<Screen, android.os.Parcelable?>()
    private var restoreScreen: Screen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101114.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        back = mobileButton("Назад", R.drawable.ic_arrow_left) { goBack() }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(52)))
        title = TextView(this).apply { label("AniHall", 22f, WHITE, true) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(mobileButton("Поиск", R.drawable.ic_search) { search() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(60)))
        val content = FrameLayout(this)
        adapter = MobileAdapter()
        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MobileActivity, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (this@MobileActivity.adapter.rows.getOrNull(position)?.poster != null) 1 else 3
                }
            }
            adapter = this@MobileActivity.adapter
            setPadding(dp(12), 0, dp(12), dp(16))
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = layoutManager as GridLayoutManager
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= adapter!!.itemCount - 7)
                        model.loadMoreCatalog()
                }
            })
        }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        loading = android.widget.ProgressBar(this).apply { contentDescription = "Загрузка" }
        content.addView(loading, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF191B20.toInt())
        }
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(root)
        root.requestApplyInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
        if (model.state.value.screen == Screen.Welcome) model.open(Screen.Home, false)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) render(model.state.value)
    }

    override fun onStop() {
        launchJob?.cancel()
        super.onStop()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun mobileButton(text: String, icon: Int = 0, clicked: () -> Unit) =
        action(FrameLayout(this), if (icon == 0) text else "", icon = icon, click = clicked).apply {
            contentDescription = text
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            if (icon != 0) {
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER
                compoundDrawablePadding = 0
            }
        }

    private fun goBack() {
        if (!model.back()) {
            if (model.state.value.screen != Screen.Home) model.resetNavigation(Screen.Home)
            else finish()
        }
    }

    private fun open(release: Release) = model.open(Screen.Detail(release.id))

    private fun search() {
        val input = EditText(this).apply {
            hint = "Название аниме"
            setSingleLine()
            setText((model.state.value.screen as? Screen.Catalog)?.query.orEmpty())
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Поиск")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Найти") { _, _ -> model.open(Screen.Catalog(query = input.text.toString().trim())) }
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            model.open(Screen.Catalog(query = input.text.toString().trim()))
            dialog.dismiss()
            true
        }
        dialog.setOnShowListener { input.requestFocus(); dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) }
        dialog.show()
    }

    private fun showLibrary() {
        val ids = library.lists().favorites.toList()
        model.resetNavigation(Screen.Library(ids, "Библиотека"))
    }

    private fun startPlayback(id: Int, episodeId: String? = null) {
        if (launchJob?.isActive == true) return
        launchJob = lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            try {
                val release = model.api.release(id)
                val episode = playbackEpisode(release, prefs.getString("last:$id", null), episodeId, watchedPercent(prefs)) { uuid ->
                    prefs.getLong("position:$uuid", 0) to prefs.getLong("duration:$uuid", 0)
                }
                if (episode == null) Toast.makeText(this@MobileActivity, "Просмотр пока недоступен", Toast.LENGTH_LONG).show()
                else startActivity(Intent(this@MobileActivity, MobilePlayerActivity::class.java)
                    .putExtra("release", id).putExtra("episode", episode.id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(this@MobileActivity, "Не удалось загрузить тайтл", Toast.LENGTH_LONG).show()
            } finally {
                loading.visibility = if (model.state.value.loading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun render(state: UiState) {
        val changed = current != state.screen
        if (changed) {
            launchJob?.cancel()
            current?.let { scrollMemory[it] = list.layoutManager?.onSaveInstanceState() }
            restoreScreen = state.screen
        }
        current = state.screen
        title.text = when (val screen = state.screen) {
            is Screen.Catalog -> screen.title ?: if (screen.query.isBlank()) "Каталог" else screen.query
            is Screen.Library -> "Библиотека"
            is Screen.Detail -> "О тайтле"
            else -> "AniHall"
        }
        back.visibility = if (state.screen is Screen.Detail) View.VISIBLE else View.GONE
        loading.visibility = if (state.loading || launchJob?.isActive == true) View.VISIBLE else View.GONE
        val rows = buildList {
            if (state.error != null) {
                add(MobileRow(text = "Не удалось загрузить данные"))
                add(MobileRow(text = "Повторить", click = model::retry))
            } else when (val screen = state.screen) {
                Screen.Home -> {
                    val continuing = history.items()
                    if (continuing.isNotEmpty()) {
                        add(MobileRow(text = "Продолжить просмотр", heading = true))
                        add(MobileRow(shelf = continuing.map { item ->
                            MobileRow(text = item.releaseTitle, subtitle = item.episodeTitle,
                                image = item.preview, progress = item.position to item.duration,
                                click = { startPlayback(item.releaseId, item.episodeId) })
                        }))
                    }
                    state.home?.let { home ->
                        home.featuredRelease?.let { release ->
                            add(MobileRow(hero = release, click = { open(release) }))
                        }
                        add(MobileRow(text = "Новые эпизоды", heading = true))
                        add(MobileRow(shelf = home.latest.distinctBy { it.id }.map(::poster)))
                        home.schedule?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { today ->
                            add(MobileRow(text = "Сегодня в расписании", heading = true))
                            add(MobileRow(shelf = today.map { release ->
                                MobileRow(poster = release, click = { open(release) })
                            }))
                        }
                        if (home.genres.orEmpty().isNotEmpty()) {
                            add(MobileRow(text = "Жанры", heading = true))
                            home.genres.orEmpty().forEach { genre ->
                                add(MobileRow(text = genre.title, click = { model.open(Screen.Catalog(genre = genre.id.toIntOrNull(), title = genre.title)) }))
                            }
                        }
                    }
                }
                is Screen.Catalog, is Screen.Library -> {
                    if (state.releases.isEmpty() && !state.loading)
                        add(MobileRow(text = if (screen is Screen.Library) "Добавляйте тайтлы в избранное — они появятся здесь" else "Ничего не найдено"))
                    state.releases.forEach { add(poster(it)) }
                    if (state.more) add(MobileRow(text = if (state.loadingMore) "Загрузка…" else "Показать ещё", click = model::loadMoreCatalog))
                    if (state.moreError != null) add(MobileRow(text = "Не удалось загрузить ещё · Повторить", click = model::loadMoreCatalog))
                }
                is Screen.Detail -> state.detail?.let { release ->
                    library.remember(release)
                    add(MobileRow(text = release.title, subtitle = release.metadata, image = release.poster, heading = true))
                    val last = prefs.getString("last:${release.id}", null)
                    val selected = playbackEpisode(release, last, watchedPercent = watchedPercent(prefs)) { uuid ->
                        prefs.getLong("position:$uuid", 0) to prefs.getLong("duration:$uuid", 0)
                    }
                    if (selected != null) {
                        val playLabel = when {
                            last != null && selected.id != last -> "Смотреть следующую серию"
                            prefs.getLong("position:${selected.id}", 0) > 0 -> "Продолжить просмотр"
                            else -> "Смотреть"
                        }
                        add(MobileRow(text = playLabel, primary = true, click = { startPlayback(release.id) }))
                    }
                    val favorite = release.id in library.lists().favorites
                    add(MobileRow(text = if (favorite) "Убрать из избранного" else "В избранное", click = {
                        library.favorite(release.id, !favorite)
                        render(model.state.value)
                    }))
                    val description = android.text.Html.fromHtml(release.description, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    if (description.isNotBlank()) add(MobileRow(text = description))
                    add(MobileRow(text = "Серии", heading = true))
                    if (release.blocked) add(MobileRow(text = "Просмотр этого тайтла ограничен"))
                    release.episodes.forEach { episode ->
                        val position = prefs.getLong("position:${episode.id}", 0)
                        val duration = prefs.getLong("duration:${episode.id}", episode.duration * 1000)
                        val status = when {
                            release.blocked || episode.streams.isEmpty() -> " · Недоступно"
                            isEpisodeWatched(position, duration, watchedPercent(prefs)) -> " · Просмотрено"
                            else -> ""
                        }
                        add(MobileRow(text = episode.label, subtitle = "${episode.duration / 60} мин$status", image = episode.preview,
                            progress = position to duration,
                            click = if (release.blocked || episode.streams.isEmpty()) null else ({ startPlayback(release.id, episode.id) })))
                    }
                    state.discovery?.recommendations?.takeIf { it.isNotEmpty() }?.let { recommended ->
                        add(MobileRow(text = "Похожие тайтлы", heading = true))
                        recommended.forEach { add(poster(it)) }
                    }
                }
                else -> Unit
            }
        }
        adapter.submit(rows)
        if (restoreScreen == state.screen && !state.loading) {
            scrollMemory[state.screen]?.let { list.layoutManager?.onRestoreInstanceState(it) }
                ?: list.scrollToPosition(0)
            restoreScreen = null
        }
        navigation.removeAllViews()
        listOf(Triple("Главная", R.drawable.ic_home, state.screen == Screen.Home),
            Triple("Каталог", R.drawable.ic_catalog, state.screen is Screen.Catalog),
            Triple("Библиотека", R.drawable.ic_library, state.screen is Screen.Library)).forEachIndexed { index, (label, icon, selected) ->
            navigation.addView(TextView(this).apply {
                label(label, 11f, if (selected) WHITE else MUTED, selected)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                isSelected = selected
                contentDescription = label
                setCompoundDrawablesWithIntrinsicBounds(null, getDrawable(icon)?.mutate()?.apply { setTint(if (selected) WHITE else MUTED) }, null, null)
                compoundDrawablePadding = dp(4)
                setOnClickListener {
                    when (index) {
                        0 -> model.resetNavigation(Screen.Home)
                        1 -> model.resetNavigation(Screen.Catalog())
                        else -> showLibrary()
                    }
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun poster(release: Release) = MobileRow(poster = release, click = { open(release) })
}
