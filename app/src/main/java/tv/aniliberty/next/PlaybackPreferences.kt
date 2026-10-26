package tv.aniliberty.next

internal fun watchedPercent(prefs: android.content.SharedPreferences): Int =
    prefs.getInt(WATCHED_PERCENT_KEY, DEFAULT_WATCHED_PERCENT).coerceIn(1, 100)
