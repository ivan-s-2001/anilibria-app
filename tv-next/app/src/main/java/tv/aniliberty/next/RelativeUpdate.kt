package tv.aniliberty.next

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun updateTimestamp(value: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant =
        runCatching { Instant.parse(value) }
            .getOrElse { runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() }
            ?: return ""
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone).format(instant)
}
