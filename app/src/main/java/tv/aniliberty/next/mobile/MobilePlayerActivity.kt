package tv.aniliberty.next.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.aniliberty.next.*

/** Touch player: Media3 transport controls, phone dialogs and shared episode UUID progress. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MobilePlayerActivity : ComponentActivity() {
    private lateinit var video: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var caption: TextView
    private lateinit var skip: TextView
    private lateinit var message: TextView
    private var player: ExoPlayer? = null
    private var release: Release? = null
    private var episode: Episode? = null
    private var quality = 0
    private var desiredPlay = true
    private var restoredPosition: Long? = null
    private var speed = 1f
    private val prefs by lazy { playbackPreferences(this) }
    private val settings by lazy { TvSettings(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var skipTo: Long? = null
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            val engine = player ?: return
            if (++ticks % 10 == 0) save()
            val current = episode
            val range = listOf("opening" to current?.opening, "ending" to current?.ending)
                .firstOrNull { (_, range) -> skipTarget(range, engine.currentPosition, engine.duration) != null }
            skipTo = skipTarget(range?.second, engine.currentPosition, engine.duration)
            skip.visibility = if (skipTo != null) View.VISIBLE else View.GONE
            skip.text = if (range?.first == "opening") "Пропустить опенинг" else "Пропустить эндинг"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        quality = savedInstanceState?.getInt("quality") ?: 0
        desiredPlay = savedInstanceState?.getBoolean("playing") ?: true
        restoredPosition = savedInstanceState?.takeIf { it.containsKey("position") }?.getLong("position")
        speed = savedInstanceState?.getFloat("speed") ?: 1f
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xB3101114.toInt())
        }
        toolbar.addView(button("Закрыть", R.drawable.ic_arrow_left) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        caption = TextView(this).apply {
            label("Загрузка…", 14f, WHITE, true)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("Серии", R.drawable.ic_episodes) { episodes() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(button("Настройки", R.drawable.ic_settings) { options() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        skip = button("Пропустить опенинг") { skipTo?.let { player?.seekTo(it) } }.apply { visibility = View.GONE }
        root.addView(skip, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(100))
        })
        message = TextView(this).apply {
            label("Загрузка…", 16f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isClickable = true
            setOnClickListener { recreate() }
        }
        root.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> toolbar.visibility = visibility })
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val id = intent.getIntExtra("release", 0)
        val requested = savedInstanceState?.getString("episode") ?: intent.getStringExtra("episode")
        lifecycleScope.launch {
            try {
                val item = Api().release(id)
                release = item
                episode = playbackEpisode(item, prefs.getString("last:$id", null), requested, settings.watchedPercent) { uuid ->
                    prefs.getLong("position:$uuid", 0) to prefs.getLong("duration:$uuid", 0)
                } ?: error("Просмотр недоступен")
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message.text = "Просмотр недоступен. Нажмите, чтобы повторить"
                message.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun button(text: String, icon: Int = 0, click: () -> Unit) =
        action(root, if (icon == 0) text else "", icon = icon, click = click).apply {
            contentDescription = text
            minimumWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            compoundDrawablePadding = 0
            gravity = Gravity.CENTER
        }

    private fun start(position: Long? = restoredPosition) {
        val item = episode ?: return
        quality = quality.takeIf { it in item.streams.indices } ?: 0
        val stream = item.streams.getOrNull(quality) ?: return
        player?.release()
        val engine = ExoPlayer.Builder(this).build()
        player = engine
        video.player = engine
        message.visibility = View.GONE
        caption.text = "${release?.title}\n${item.label} · ${stream.first}"
        engine.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                message.text = "Не удалось воспроизвести. Нажмите, чтобы повторить"
                message.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                save()
                if (!settings.enabled("auto-next", true)) return
                val episodes = release?.episodes.orEmpty()
                val index = episodes.indexOfFirst { it.id == item.id }
                episodes.drop(index + 1).firstOrNull { it.streams.isNotEmpty() }?.let {
                    episode = it
                    desiredPlay = true
                    start()
                }
            }
        })
        engine.setMediaItem(MediaItem.fromUri(stream.second))
        val saved = position ?: prefs.getLong("position:${item.id}", 0)
        val duration = prefs.getLong("duration:${item.id}", item.duration * 1000)
        engine.seekTo(if (position == null && isEpisodeWatched(saved, duration, settings.watchedPercent)) 0 else saved)
        restoredPosition = null
        engine.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        engine.setHandleAudioBecomingNoisy(true)
        engine.setPlaybackSpeed(speed)
        engine.playWhenReady = desiredPlay
        engine.prepare()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun save() {
        val engine = player ?: return
        val release = release ?: return
        val episode = episode ?: return
        val duration = engine.duration.takeIf { it > 0 } ?: prefs.getLong("duration:${episode.id}", episode.duration * 1000)
        val position = engine.currentPosition.coerceAtLeast(0)
        if (position == 0L && engine.playbackState == Player.STATE_IDLE) return
        prefs.edit().putString("last:${release.id}", episode.id)
            .putLong("position:${episode.id}", position).putLong("duration:${episode.id}", duration).apply()
        ViewingHistory(prefs).save(release, episode, position, duration)
        queueAccountProgress(this, episode.id, position, duration)
    }

    private fun episodes() {
        val episodes = release?.episodes.orEmpty().filter { it.streams.isNotEmpty() }
        if (episodes.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Серии")
            .setSingleChoiceItems(episodes.map { it.label }.toTypedArray(), episodes.indexOfFirst { it.id == episode?.id }) { dialog, index ->
                save()
                episode = episodes[index]
                start()
                dialog.dismiss()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun options() {
        val current = episode ?: return
        val labels = arrayOf("Качество", "Скорость · ${speed}×", "Считать просмотренным · ${settings.watchedPercent}%", "Автопереход · ${if (settings.enabled("auto-next", true)) "включён" else "выключен"}")
        AlertDialog.Builder(this).setTitle("Воспроизведение").setItems(labels) { _, option ->
            when (option) {
                0 -> AlertDialog.Builder(this).setTitle("Качество")
                    .setSingleChoiceItems(current.streams.map { it.first }.toTypedArray(), quality) { dialog, index ->
                        val position = player?.currentPosition
                        desiredPlay = player?.playWhenReady ?: desiredPlay
                        save()
                        quality = index
                        start(position)
                        dialog.dismiss()
                    }.setNegativeButton("Закрыть", null).show()
                1 -> {
                    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    AlertDialog.Builder(this).setTitle("Скорость")
                        .setSingleChoiceItems(speeds.map { "${it}×" }.toTypedArray(), speeds.indexOf(speed)) { dialog, index ->
                            speed = speeds[index]
                            player?.setPlaybackSpeed(speed)
                            dialog.dismiss()
                        }.setNegativeButton("Закрыть", null).show()
                }
                2 -> {
                    val values = (50..100 step 5).toList()
                    AlertDialog.Builder(this).setTitle("Считать серию просмотренной после")
                        .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(settings.watchedPercent)) { dialog, index ->
                            settings.watchedPercent = values[index]
                            save()
                            dialog.dismiss()
                        }.setNegativeButton("Закрыть", null).show()
                }
                3 -> settings.toggle("auto-next", true)
            }
        }.show()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && episode != null) start()
    }

    override fun onStop() {
        desiredPlay = player?.playWhenReady ?: desiredPlay
        restoredPosition = player?.currentPosition ?: restoredPosition
        save()
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        video.player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("episode", episode?.id)
        (player?.currentPosition ?: restoredPosition)?.let { outState.putLong("position", it) }
        outState.putInt("quality", quality)
        outState.putFloat("speed", speed)
        outState.putBoolean("playing", player?.playWhenReady ?: desiredPlay)
        super.onSaveInstanceState(outState)
    }
}
