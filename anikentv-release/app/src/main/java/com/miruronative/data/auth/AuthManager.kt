package com.miruronative.data.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.miruronative.data.reminder.ReleaseSyncScheduler
import java.util.UUID

/**
 * AniLiberty account session. Device ID is stable across app launches; a TV login uses
 * the OTP challenge confirmed by the user in their already-authenticated browser session.
 * Never interpret a previously stored AniList token as an AniLiberty session.
 */
object AuthManager {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _token = MutableStateFlow<String?>(null)
    val token = _token.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_auth", Context.MODE_PRIVATE)
        _token.value = prefs.getString("aniliberty_token", null)
    }

    fun current(): String? = _token.value
    val isLoggedIn: Boolean get() = _token.value != null

    fun deviceId(): String = synchronized(this) {
        prefs.getString("aniliberty_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("aniliberty_device_id", it).commit()
        }
    }

    fun setToken(token: String) {
        prefs.edit().putString("aniliberty_token", token).apply()
        _token.value = token
        ReleaseSyncScheduler.runNow(appContext)
    }

    fun logout() {
        prefs.edit().remove("aniliberty_token").apply()
        _token.value = null
        ReleaseSyncScheduler.runNow(appContext)
    }
}
