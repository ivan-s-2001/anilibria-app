package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class RelativeUpdateTest {
    @Test fun displaysDateAndTimeInTvTimeZone(){
        assertEquals("22.10.2026 15:40",updateTimestamp("2026-10-22T12:40:00Z",ZoneId.of("Europe/Moscow")))
        assertEquals("22.10.2026 15:40",updateTimestamp("2026-10-22T15:40:00+03:00",ZoneId.of("Europe/Moscow")))
        assertEquals("23.10.2026 01:30",updateTimestamp("2026-10-22T22:30:00Z",ZoneId.of("Europe/Moscow")))
    }
    @Test fun hidesMissingAndInvalidTimestamps(){
        assertEquals("",updateTimestamp(""))
        assertEquals("",updateTimestamp("invalid"))
    }
}
