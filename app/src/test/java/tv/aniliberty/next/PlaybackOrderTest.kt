package tv.aniliberty.next

import org.junit.Assert.*
import org.junit.Test

class PlaybackOrderTest {
    private fun episode(id:String,ordinal:Double,order:Int)=Episode(id,ordinal,"",null,0,emptyList(),order.toDouble())
    @Test fun nextUsesServerOrderIncludingFractionalAndMissingOrdinals(){
        val episodes=listOf(episode("a",1.0,0),episode("special",12.5,1),episode("last",20.0,2))
        assertEquals("special",nextEpisode(episodes,"a")?.id)
        assertEquals("last",nextEpisode(episodes,"special")?.id)
    }
    @Test fun missingCurrentOrLastNeverWrapToFirst(){
        val episodes=listOf(episode("a",1.0,0))
        assertNull(nextEpisode(episodes,"a"))
        assertNull(nextEpisode(episodes,"removed"))
        assertNull(nextEpisode(emptyList(),null))
    }
}
