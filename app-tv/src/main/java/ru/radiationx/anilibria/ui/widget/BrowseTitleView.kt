/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatTextView
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.leanback.widget.SearchOrbView
import androidx.leanback.widget.TitleViewAdapter
import dev.androidbroadcast.vbpd.viewBinding
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.databinding.ViewTitleviewBinding

open class BrowseTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.leanback.R.attr.browseTitleViewStyle,
) : ConstraintLayout(context, attrs, defStyleAttr), TitleViewAdapter.Provider {

    private val binding by viewBinding<ViewTitleviewBinding>(attachToRoot = true)
    private val homeButtons = mutableListOf<TextView>()
    private var homePageIndex = 0

    private var flags = TitleViewAdapter.FULL_VIEW_VISIBLE
    private var mHasSearchListener = false
    private val mTitleViewAdapter by lazy { Adapter(this) }

    init {
        clipToPadding = false
        clipChildren = false
    }

    var title: CharSequence?
        get() = binding.titleText.text
        set(titleText) {
            binding.titleText.text = titleText
            updateBadgeVisibility()
        }

    var badgeDrawable: Drawable?
        get() = binding.titleBadge.drawable
        set(drawable) {
            binding.titleBadge.setImageDrawable(drawable)
            updateBadgeVisibility()
        }

    val searchAffordanceView: View
        get() = homeButtons.getOrNull(homePageIndex) ?: binding.titleOrb

    var searchAffordanceColors: SearchOrbView.Colors?
        get() = binding.titleOrb.orbColors
        set(colors) {
            colors ?: return
            binding.titleOrb.setOrbColors(colors)
        }

    var alert: CharSequence?
        get() = binding.titleAlert.text
        set(value) {
            binding.titleAlert.text = value
            updateAlertVisibility()
        }

    var other: CharSequence?
        get() = binding.titleOther.text
        set(value) {
            binding.titleOther.text = value
            updateOtherVisibility()
        }

    fun setHomeNavigation(items: List<Pair<String, () -> Unit>>) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        row.addView(AppCompatTextView(context).apply {
            text = "AniLiberty"
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.white))
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            resources.getDimensionPixelSize(R.dimen.tv_navigation_height)).apply {
            marginEnd = (24 * density).toInt()
        })
        homeButtons.clear()
        items.forEach { (label, action) ->
            val button = AppCompatTextView(context).apply {
                id = View.generateViewId()
                text = label
                isSingleLine = true
                minWidth = (72 * density).toInt()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_navigation_text))
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setTextColor(ContextCompat.getColorStateList(context, R.color.launcher_pill_text))
                setBackgroundResource(R.drawable.home_tab_bg)
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, resources.getDimensionPixelSize(R.dimen.tv_navigation_height)
            ).apply { marginEnd = (8 * density).toInt() })
            homeButtons.add(button)
        }
        homeButtons.forEachIndexed { index, button ->
            button.nextFocusLeftId = homeButtons.getOrNull(index - 1)?.id ?: button.id
            button.nextFocusRightId = homeButtons.getOrNull(index + 1)?.id ?: button.id
            button.nextFocusUpId = button.id
            button.nextFocusDownId = androidx.leanback.R.id.browse_container_dock
        }
        binding.titleControls.removeAllViews()
        // A wide label or increased system font must not clip the final tab.
        // HorizontalScrollView scrolls the focused descendant into view for D-pad.
        val navigation = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        binding.titleControls.addView(navigation, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        binding.titleControls.isVisible = true
        binding.titleControls.setPadding(0, 0, 0, 0)
        updateComponentsVisibility(flags)
    }

    fun selectHomeNavigation(index: Int) {
        homePageIndex = index.coerceIn(0, (homeButtons.size - 1).coerceAtLeast(0))
        homeButtons.forEachIndexed { index, button ->
            button.isSelected = index == homePageIndex
        }
    }

    fun getControls() = binding.titleControls

    fun setOnSearchClickedListener(listener: OnClickListener?) {
        mHasSearchListener = listener != null
        binding.titleOrb.setOnOrbClickedListener(listener)
        updateSearchOrbViewVisibility()
    }

    fun setOnAlertClickedListener(listener: OnClickListener?) {
        binding.titleAlert.setOnClickListener(listener)
    }

    fun setOnOtherClickedListener(listener: OnClickListener?) {
        binding.titleOther.setOnClickListener(listener)
    }

    fun enableAnimation(enable: Boolean) {
        binding.titleOrb.enableOrbColorAnimation(enable && binding.titleOrb.hasFocus())
    }

    fun updateComponentsVisibility(flags: Int) {
        this.flags = flags
        if (homeButtons.isNotEmpty()) {
            binding.titleBadge.isVisible = false
            binding.titleText.isVisible = false
            binding.titleOrb.isVisible = false
            binding.titleOther.isVisible = false
            binding.titleControls.isVisible = true
            updateAlertVisibility()
            return
        }
        if (flags and TitleViewAdapter.BRANDING_VIEW_VISIBLE == TitleViewAdapter.BRANDING_VIEW_VISIBLE) {
            updateBadgeVisibility()
        } else {
            binding.titleBadge.visibility = View.GONE
            binding.titleText.visibility = View.GONE
        }
        updateSearchOrbViewVisibility()
        updateAlertVisibility()
        updateOtherVisibility()
    }

    private fun updateSearchOrbViewVisibility() {
        val visibility =
            if (mHasSearchListener && flags and TitleViewAdapter.SEARCH_VIEW_VISIBLE == TitleViewAdapter.SEARCH_VIEW_VISIBLE) View.VISIBLE else View.INVISIBLE
        binding.titleOrb.visibility = visibility
    }

    private fun updateBadgeVisibility() {
        val drawable = binding.titleBadge.drawable
        if (drawable != null) {
            binding.titleBadge.visibility = View.VISIBLE
            binding.titleText.visibility = View.GONE
        } else {
            binding.titleBadge.visibility = View.GONE
            binding.titleText.visibility = View.VISIBLE
        }
    }

    private fun updateAlertVisibility() {
        binding.titleAlert.isVisible = !alert.isNullOrEmpty()
    }

    private fun updateOtherVisibility() {
        binding.titleOther.isVisible = !other.isNullOrEmpty()
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = mTitleViewAdapter

    override fun focusSearch(focused: View, direction: Int): View? {
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT || direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
            var nextView: View? = when (direction) {
                View.FOCUS_LEFT -> findViewById(focused.nextFocusLeftId)
                View.FOCUS_RIGHT -> findViewById(focused.nextFocusRightId)
                View.FOCUS_DOWN -> findViewById(focused.nextFocusDownId)
                View.FOCUS_UP -> findViewById(focused.nextFocusUpId)
                else -> null
            }
            if (focused.parent == this && (nextView == null || !nextView.isVisible || !(nextView.isFocusable || nextView is ViewGroup))) {
                nextView = when (direction) {
                    View.FOCUS_LEFT -> findViewById(focused.nextFocusUpId)
                    View.FOCUS_RIGHT -> findViewById(focused.nextFocusDownId)
                    // Имхо так лучше
                    //View.FOCUS_DOWN -> findViewById(focused.nextFocusRightId)
                    //View.FOCUS_UP -> findViewById(focused.nextFocusLeftId)
                    else -> null
                }
            }

            if (nextView != null && (nextView.isFocusable || nextView is ViewGroup)) {
                return nextView
            }
        }
        return super.focusSearch(focused, direction)
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?,
    ): Boolean {
        return searchAffordanceView.requestFocus() || super.onRequestFocusInDescendants(
            direction,
            previouslyFocusedRect
        )
    }

    class Adapter(private val titleView: BrowseTitleView) : TitleViewAdapter() {

        fun setAlert(alertText: CharSequence?) {
            titleView.alert = alertText
        }

        fun getAlert(): CharSequence? = titleView.alert

        fun setOnAlertClickedListener(listener: OnClickListener?) =
            titleView.setOnAlertClickedListener(listener)

        fun setOther(otherText: CharSequence?) {
            titleView.other = otherText
        }

        fun getOther(): CharSequence? = titleView.other

        fun setOnOtherClickedListener(listener: OnClickListener?) =
            titleView.setOnOtherClickedListener(listener)

        override fun getSearchAffordanceView(): View = titleView.searchAffordanceView
        override fun setOnSearchClickedListener(listener: OnClickListener?) =
            titleView.setOnSearchClickedListener(listener)

        override fun setAnimationEnabled(enable: Boolean) = titleView.enableAnimation(enable)
        override fun getBadgeDrawable(): Drawable? = titleView.badgeDrawable
        override fun getSearchAffordanceColors(): SearchOrbView.Colors? =
            titleView.searchAffordanceColors

        override fun getTitle(): CharSequence? = titleView.title
        override fun setBadgeDrawable(drawable: Drawable?) {
            titleView.badgeDrawable = drawable
        }

        override fun setSearchAffordanceColors(colors: SearchOrbView.Colors) {
            titleView.searchAffordanceColors = colors
        }

        override fun setTitle(titleText: CharSequence?) {
            titleView.title = titleText
        }

        override fun updateComponentsVisibility(flags: Int) =
            titleView.updateComponentsVisibility(flags)
    }
}