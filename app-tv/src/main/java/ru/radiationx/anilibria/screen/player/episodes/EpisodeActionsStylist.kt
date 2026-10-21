package ru.radiationx.anilibria.screen.player.episodes

import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import ru.radiationx.anilibria.R

/** Keeps Leanback's virtualized list and D-pad behavior, with TV-sized episode rows. */
class EpisodeActionsStylist(
    private val episodeForId: (Long) -> PlayerEpisodesViewModel.Action?,
) : GuidedActionsStylist() {
    override fun onProvideItemLayoutId(viewType: Int): Int = R.layout.item_tv_episode

    override fun onBindViewHolder(vh: ViewHolder, action: GuidedAction) {
        super.onBindViewHolder(vh, action)
        val episode = episodeForId(action.id)
        vh.itemView.findViewById<TextView>(R.id.episode_number).apply {
            isVisible = episode != null
            text = episode?.number.orEmpty()
        }
        vh.itemView.contentDescription = if (episode != null) {
            "Серия ${episode.number}. ${episode.title}. ${episode.description.orEmpty()}"
        } else action.title
    }
}
