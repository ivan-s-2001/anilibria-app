package ru.radiationx.anilibria.ui.presenter.cust

import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ListRowView

/** Keep row geometry stable: focused cards must not insert an extra description block. */
class CustomListRowViewHolder(
    rootView: ListRowView,
    gridView: HorizontalGridView,
    presenter: ListRowPresenter,
) : ListRowPresenter.ViewHolder(rootView, gridView, presenter) {
    fun setDescription(title: CharSequence, subtitle: CharSequence) = Unit
    fun setExpanded(expanded: Boolean) = Unit
    fun setSelected(selected: Boolean) = Unit
}
