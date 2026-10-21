package ru.radiationx.anilibria.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import ru.radiationx.anilibria.databinding.ViewSearchControlsBinding

class SearchTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.leanback.R.attr.browseTitleViewStyle,
) : BrowseTitleView(context, attrs, defStyleAttr) {
    private val binding = ViewSearchControlsBinding.inflate(LayoutInflater.from(context), getControls(), true)
    private var yearClick: OnClickListener? = null
    private var seasonClick: OnClickListener? = null
    private var genreClick: OnClickListener? = null
    private var completeClick: OnClickListener? = null
    private var resetClick: OnClickListener? = null
    private var filterDialog: AlertDialog? = null

    var year: String? = null
        set(value) { field = value; updateSummary() }
    var season: String? = null
        set(value) { field = value; updateSummary() }
    var genre: String? = null
        set(value) { field = value; updateSummary() }
    var onlyCompleted: String? = null
        set(value) { field = value; updateSummary() }
    var sort: String?
        get() = binding.searchTitleSort.text.toString()
        set(value) { binding.searchTitleSort.text = value }

    init {
        getControls().isVisible = true
        binding.searchTitleFilters.setOnClickListener {
            filterDialog?.dismiss()
            val labels = arrayOf(year ?: "Год", season ?: "Сезон", genre ?: "Жанр",
                onlyCompleted ?: "Статус", "Сбросить фильтры")
            filterDialog = AlertDialog.Builder(context)
                .setTitle("Фильтры каталога")
                .setItems(labels) { _, index ->
                    val listener = when (index) {
                        0 -> yearClick
                        1 -> seasonClick
                        2 -> genreClick
                        3 -> completeClick
                        else -> resetClick
                    }
                    listener?.onClick(binding.searchTitleFilters)
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun updateSummary() {
        val text = listOfNotNull(year, season, genre, onlyCompleted).joinToString(" · ")
        binding.searchTitleSummary.text = text
        binding.searchTitleFilters.contentDescription = "Фильтры. $text"
    }

    fun setYearClickListener(listener: OnClickListener?) { yearClick = listener }
    fun setSeasonClickListener(listener: OnClickListener?) { seasonClick = listener }
    fun setGenreClickListener(listener: OnClickListener?) { genreClick = listener }
    fun setSortClickListener(listener: OnClickListener?) { binding.searchTitleSort.setOnClickListener(listener) }
    fun setOnlyCompletedClickListener(listener: OnClickListener?) { completeClick = listener }
    fun setResetClickListener(listener: OnClickListener?) { resetClick = listener }

    override fun onDetachedFromWindow() {
        filterDialog?.dismiss()
        filterDialog = null
        super.onDetachedFromWindow()
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (direction == View.FOCUS_UP && binding.searchTitleFilters.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}
