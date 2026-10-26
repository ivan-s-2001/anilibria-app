package tv.aniliberty.next

internal fun releaseCards(items: List<Release>, key: String) =
        items
            .distinctBy { it.id }
            .map {
                Row(
                    "$key:release:${it.id}",
                    Kind.POSTER,
                    release = it,
                    selected =
                        if (key.startsWith("schedule", true)) 3
                        else if (key.startsWith("latest", true)) 1 else 0,
                )
            }
