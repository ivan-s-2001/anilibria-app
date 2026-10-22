package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RelativeUpdateTest {
    private val now=Instant.parse("2026-10-22T12:00:00Z")
    @Test fun displaysElapsedDaysAndHours(){
        assertEquals("3 д. 4 ч. назад",relativeUpdate("2026-10-19T08:00:00Z",now))
        assertEquals("11 д. назад",relativeUpdate("2026-10-11T12:00:00Z",now))
        assertEquals("2 ч. назад",relativeUpdate("2026-10-22T10:00:00Z",now))
    }
    @Test fun hidesMissingInvalidAndFutureTimestamps(){
        assertEquals("",relativeUpdate("",now))
        assertEquals("",relativeUpdate("invalid",now))
        assertEquals("",relativeUpdate("2026-10-23T12:00:00Z",now))
    }
    @Test fun supportsOffsetsAndRecentUpdates(){
        assertEquals("Только что",relativeUpdate("2026-10-22T15:00:00+03:00",now))
        assertEquals("21 мин. назад",relativeUpdate("2026-10-22T11:39:00Z",now))
    }
}
