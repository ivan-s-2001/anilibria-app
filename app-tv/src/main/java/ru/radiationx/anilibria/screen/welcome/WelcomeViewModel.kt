package ru.radiationx.anilibria.screen.welcome

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.AuthCredentialsGuidedScreen
import ru.radiationx.anilibria.screen.AuthOtpGuidedScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.MainPagesScreen
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.shared.ktx.coRunCatching
import javax.inject.Inject

/** Entry to the existing authentication flows; no new protocol or credential storage. */
class WelcomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val guidedRouter: GuidedRouter,
    private val router: Router,
) : LifecycleViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    private var openedMain = false

    init {
        authRepository.observeAuthState().onEach {
            if (it == AuthState.AUTH) openMain()
        }.launchIn(viewModelScope)
    }

    fun onCodeClick() {
        if (!busy.value) guidedRouter.open(AuthOtpGuidedScreen())
    }

    fun onCredentialsClick() {
        if (!busy.value) guidedRouter.open(AuthCredentialsGuidedScreen())
    }

    fun onGuestClick() {
        if (busy.value) return
        busy.value = true
        error.value = ""
        viewModelScope.launch {
            coRunCatching { authRepository.setAuthSkipped(true) }
                .onSuccess { openMain() }
                .onFailure { error.value = "Не удалось продолжить. Попробуйте ещё раз." }
            busy.value = false
        }
    }

    private fun openMain() {
        if (openedMain) return
        openedMain = true
        router.newRootScreen(MainPagesScreen())
    }
}
