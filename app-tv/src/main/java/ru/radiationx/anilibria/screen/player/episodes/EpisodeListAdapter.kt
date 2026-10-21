package ru.radiationx.anilibria.screen.player.episodes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.radiationx.anilibria.R
import ru.radiationx.data.entity.domain.types.EpisodeId

data class EpisodeListRow(val groupTitle: String? = null, val episode: PlayerEpisodesViewModel.Action? = null)

fun episodeDisplayTitle(title: String?, number: String): String =
    title?.trim()?.takeIf { it.isNotEmpty() } ?: "Серия $number"

/** Filtering never changes the ID passed to playback; numbering can be fractional. */
fun episodeRows(
    groups: List<PlayerEpisodesViewModel.Group>, descending: Boolean, hideWatched: Boolean,
    query: String = "",
): List<EpisodeListRow> = buildList {
    val search = query.trim()
    groups.forEach { group ->
        val episodes = group.actions.filter { !hideWatched || !it.watched }
            .filter { search.isEmpty() || it.number.contains(search, true) ||
                it.title.contains(search, true) || group.title.contains(search, true) }
            .let { if (descending) it.asReversed() else it }
        if (episodes.isNotEmpty()) {
            if (groups.size > 1) add(EpisodeListRow(groupTitle = group.title))
            addAll(episodes.map { EpisodeListRow(episode = it) })
        }
    }
}

class EpisodeListAdapter(
    private val play: (PlayerEpisodesViewModel.Action) -> Unit,
    private val focused: (EpisodeId) -> Unit,
    private val compact: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<EpisodeListRow> = emptyList()
        private set

    fun submit(items: List<EpisodeListRow>) {
        rows = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size
    override fun getItemViewType(position: Int): Int = if (rows[position].episode == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = if (viewType == 1) {
            LayoutInflater.from(parent.context).inflate(R.layout.item_tv_episode, parent, false).apply {
                if (compact) {
                    val density = resources.displayMetrics.density
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    minimumHeight = (76 * density).toInt()
                    findViewById<TextView>(R.id.episode_title).textSize = 17f
                    findViewById<TextView>(R.id.episode_description).textSize = 14f
                    findViewById<TextView>(R.id.episode_number).apply {
                        textSize = 18f
                        layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                            width = (44 * density).toInt()
                            marginEnd = (12 * density).toInt()
                        }
                    }
                }
            }
        } else TextView(parent.context).apply {
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isFocusable = false
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val episode = row.episode
        if (episode == null) {
            (holder.itemView as TextView).text = row.groupTitle
            return
        }
        holder.itemView.apply {
            isActivated = episode.current
            findViewById<TextView>(R.id.episode_number).text = episode.number
            findViewById<TextView>(R.id.episode_title).text = episode.title
            findViewById<TextView>(R.id.episode_description).text = episode.description
            contentDescription = "Серия ${episode.number}. ${episode.title}. ${episode.description.orEmpty()}"
            setOnClickListener { play(episode) }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) focused(episode.episodeId)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.setOnClickListener(null)
        holder.itemView.onFocusChangeListener = null
        super.onViewRecycled(holder)
    }
}
