package ru.radiationx.anilibria.ui.presenter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.shared_app.imageloader.showImageUrl

data class HomeHeroCard(val release: LibriaCard)

/** Uses an already loaded feed release; does not fetch web promotions. */
class HomeHeroPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.card_home_hero, parent, false)
    )

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = (item as HomeHeroCard).release
        viewHolder.view.findViewById<TextView>(R.id.hero_title).text = card.title
        viewHolder.view.findViewById<TextView>(R.id.hero_metadata).text = card.preview?.metadata ?: card.description
        viewHolder.view.findViewById<ImageView>(R.id.hero_image).showImageUrl(card.image)
        viewHolder.view.contentDescription = "${card.title}. Открыть релиз"
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.hero_image).setImageDrawable(null)
        viewHolder.view.contentDescription = null
    }
}
