package com.miruronative.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miruronative.data.AppGraph
import com.miruronative.data.remote.AniLibertyClient
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * AniLiberty's TV OTP login displayed inside the original fullscreen login surface.
 * The code is confirmed in an already authenticated AniLiberty browser session;
 * the TV exchanges it for a token once confirmation succeeds.
 */
@Composable
fun LoginWebView(onToken: (String) -> Unit, onCancel: () -> Unit) {
    val device = LocalAppDeviceProfile.current
    val repo = AppGraph.repository
    var requestVersion by remember { mutableIntStateOf(0) }
    var challenge by remember { mutableStateOf<AniLibertyClient.OtpChallenge?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestVersion) {
        loading = true
        error = null
        challenge = null
        try {
            challenge = repo.requestLoginCode()
        } catch (e: Exception) {
            error = e.message ?: "Не удалось получить код"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(challenge) {
        val pending = challenge ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + pending.remainingSeconds.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(4_000)
            try {
                onToken(repo.completeLogin(pending.code))
                return@LaunchedEffect
            } catch (e: IOException) {
                // 401: code is not yet confirmed by a logged-in user; keep waiting.
                if (e.message?.contains("HTTP 401") != true) {
                    error = e.message ?: "Ошибка авторизации"
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка авторизации"
                return@LaunchedEffect
            }
        }
        error = "Срок действия кода истёк"
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("AniLiberty", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Откройте на телефоне или компьютере страницу привязки ТВ, войдите в аккаунт AniLiberty и введите код:")
                Text(
                    "anilibria.top/app/auth/otp/linkDevice",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (loading) CircularProgressIndicator()
                challenge?.let { pending ->
                    Text(pending.code, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Ожидаем подтверждения · ${pending.remainingSeconds} с")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { requestVersion++ },
                    enabled = !loading,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp)),
                ) { Text(if (challenge == null) "Получить код" else "Новый код") }
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.TopEnd).padding(device.pagePadding)
                    .focusHighlight(RoundedCornerShape(24.dp)),
            ) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
        }
    }
}
