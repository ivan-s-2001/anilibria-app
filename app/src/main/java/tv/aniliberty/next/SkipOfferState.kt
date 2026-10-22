package tv.aniliberty.next

/** The floating offer expires once; timeline controls remain available until dismissal or range end. */
internal class SkipOfferState {
    private var active:String?=null
    private var hiddenSince:Long?=null
    private val dismissed=mutableSetOf<String>()
    fun dismissed(key:String)=key in dismissed
    fun dismiss(key:String){dismissed+=key}
    fun hiddenRemaining(key:String?,now:Long,hidden:Boolean):Long {
        if(active!=key){active=key;hiddenSince=null}
        if(key==null || dismissed(key))return 0
        if(!hidden)return 0
        if(hiddenSince==null)hiddenSince=now
        return (10000-(now-(hiddenSince?:now)).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
internal data class SkipSegment(val key:String,val skipLabel:String,val watchLabel:String,val stop:Long)
