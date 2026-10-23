package com.miruronative.data

import com.miruronative.data.remote.AniLibertyClient

/** Labels and ordering for the existing provider picker. AniLiberty is the only backend. */
object ProviderCatalog {
    fun isEmbed(@Suppress("UNUSED_PARAMETER") provider: String): Boolean = false
    fun isNative(@Suppress("UNUSED_PARAMETER") provider: String): Boolean = true
    fun sortKey(provider: String): Int = if (provider == AniLibertyClient.PROVIDER) 0 else 1
    fun label(provider: String): String = provider
}
