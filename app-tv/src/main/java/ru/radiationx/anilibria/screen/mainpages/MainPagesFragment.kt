package ru.radiationx.anilibria.screen.mainpages

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import ru.radiationx.anilibria.ui.widget.BrowseTitleView
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class MainPagesFragment : BrowseSupportFragment() {
    private val viewModel by viewModel<MainPagesViewModel>()
    private val fragmentFactory by lazy { MainPagesFragmentFactory() }
    private var lastSelectedPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastSelectedPosition = (savedInstanceState?.getInt(PAGE_KEY) ?: 0).coerceIn(0, 2)
        // PageRow dispatch requires headers support; only hide the legacy rail.
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = false
        mainFragmentRegistry.registerFragment(PageRow::class.java, fragmentFactory)
        adapter = ArrayObjectAdapter(ListRowPresenter()).apply {
            MainPagesFragmentFactory.ids.forEach { id ->
                add(PageRow(HeaderItem(id, MainPagesFragmentFactory.variant1[id])))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideLegacyNavigation(view)
        view.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.browse_frame)
            .setOnFocusSearchListener { focused, direction ->
                when {
                    focused == null -> null
                    titleView?.hasFocus() == true -> when (direction) {
                        View.FOCUS_DOWN -> mainFragment?.view
                        View.FOCUS_UP -> focused
                        else -> null
                    }
                    direction == View.FOCUS_UP &&
                        (mainFragment as? RowsSupportFragment)?.selectedPosition == 0 -> titleView
                    else -> null
                }
            }
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        (titleView as? BrowseTitleView)?.apply {
            setHomeNavigation(
                listOf(
                    "Главная" to { openPage(0) },
                    "Каталог" to { viewModel.onCatalogClick() },
                    "Расписание" to { viewModel.onScheduleClick() },
                    "Поиск" to { viewModel.onSearchClick() },
                    "Моё" to { openPage(1) },
                    "Профиль" to { openPage(2) }
                )
            )
            selectHomeNavigation(navigationIndex(lastSelectedPosition))
            setOnAlertClickedListener { viewModel.onAppUpdateClick() }
        }
        subscribeTo(viewModel.hasUpdatesData) { available ->
            (titleView as? BrowseTitleView)?.alert = if (available) "Обновление" else null
        }
    }

    private fun hideLegacyNavigation(root: View) {
        root.findViewById<ViewGroup>(androidx.leanback.R.id.browse_headers_dock)?.apply {
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
        }
    }

    // Catalog, schedule and search open their own screens; only these three
    // destinations are hosted PageRows. Keep their navigation mapping explicit.
    private fun navigationIndex(page: Int): Int = when (page) {
        1 -> 4
        2 -> 5
        else -> 0
    }

    private fun openPage(position: Int) {
        lastSelectedPosition = position
        setSelectedPosition(position, false)
        (titleView as? BrowseTitleView)?.selectHomeNavigation(navigationIndex(position))
    }

    override fun onStart() {
        super.onStart()
        view?.let(::hideLegacyNavigation)
        setSelectedPosition(lastSelectedPosition, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(PAGE_KEY, lastSelectedPosition)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val PAGE_KEY = "home_page"
    }
}
