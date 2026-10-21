package ru.radiationx.anilibria.screen.welcome

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.androidbroadcast.vbpd.viewBinding
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.databinding.FragmentWelcomeBinding
import ru.radiationx.quill.inject
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class WelcomeFragment : Fragment(R.layout.fragment_welcome) {
    private val binding by viewBinding<FragmentWelcomeBinding>()
    private val viewModel by viewModel<WelcomeViewModel>()
    private val backgroundManager by inject<GradientBackgroundManager>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        backgroundManager.clearGradient()
        binding.welcomeCode.setOnClickListener { viewModel.onCodeClick() }
        binding.welcomeCredentials.setOnClickListener { viewModel.onCredentialsClick() }
        binding.welcomeGuest.setOnClickListener { viewModel.onGuestClick() }
        subscribeTo(viewModel.busy) {
            binding.welcomeProgress.isVisible = it
            binding.welcomeCode.isEnabled = !it
            binding.welcomeCredentials.isEnabled = !it
            binding.welcomeGuest.isEnabled = !it
        }
        subscribeTo(viewModel.error) {
            binding.welcomeError.text = it
            binding.welcomeError.isVisible = it.isNotBlank()
        }
        if (savedInstanceState == null) binding.welcomeCode.requestFocus()
    }
}
