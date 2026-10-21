package ru.radiationx.anilibria.common.fragment

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentFactory
import com.github.terrakok.cicerone.androidx.FragmentScreen

/** A modal screen with its own layout, independent of Leanback GuidedStep. */
abstract class DialogAppScreen : FragmentScreen {
    abstract override fun createFragment(factory: FragmentFactory): DialogFragment
}
