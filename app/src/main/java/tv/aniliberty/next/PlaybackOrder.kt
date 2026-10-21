package tv.aniliberty.next

/** Playback follows the server's ordered episode list, never ordinal + 1. */
fun nextEpisode(episodes:List<Episode>, currentId:String?):Episode? {
    val index=episodes.indexOfFirst{it.id==currentId}
    return if(index>=0)episodes.getOrNull(index+1)else null
}
