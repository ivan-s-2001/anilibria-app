package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test

class SkipOfferStateTest {
    @Test fun floatingOfferExpiresAfterTenSecondsAndDoesNotRestartEveryTick(){
        val state=SkipOfferState()
        assertEquals(10000L,state.hiddenRemaining("ep:opening",100,true))
        assertEquals(1L,state.hiddenRemaining("ep:opening",10099,true))
        assertEquals(0L,state.hiddenRemaining("ep:opening",10100,true))
        assertEquals(0L,state.hiddenRemaining("ep:opening",30000,true))
        assertFalse(state.dismissed("ep:opening")) // Timeline skip remains available.
    }
    @Test fun openingTimelineDoesNotResetFloatingTimeout(){
        val state=SkipOfferState()
        assertEquals(0L,state.hiddenRemaining("ep:opening",100,false))
        assertEquals(10000L,state.hiddenRemaining("ep:opening",1000,true))
        assertEquals(0L,state.hiddenRemaining("ep:opening",2000,false))
        assertEquals(0L,state.hiddenRemaining("ep:opening",12000,true))
    }
    @Test fun watchDismissesCurrentRangeButNotEndingOrNextEpisode(){
        val state=SkipOfferState()
        state.hiddenRemaining("ep:opening",0,true)
        state.dismiss("ep:opening")
        assertEquals(0L,state.hiddenRemaining("ep:opening",1,true))
        assertTrue(state.dismissed("ep:opening"))
        assertEquals(10000L,state.hiddenRemaining("ep:ending",2,true))
        assertEquals(10000L,state.hiddenRemaining("next:opening",3,true))
    }
}
