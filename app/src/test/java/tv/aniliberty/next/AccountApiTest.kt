package tv.aniliberty.next

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class AccountApiTest {
    private data class Call(val path:String,val method:String,val body:String?,val token:String?)
    @Test fun favoritesAndCollectionsUseSeparateAuthenticatedEndpointsAndArrayBodies()=runBlocking{
        val calls=mutableListOf<Call>()
        val api=AccountApi(AccountTransport{p,m,b,t->calls+=Call(p,m,b,t);"[]"})
        api.favorite("session",42,true);api.favorite("session",42,false)
        api.collection("session",42,CollectionType.WATCHING);api.collection("session",42,null)
        assertEquals(listOf("POST","DELETE","POST","DELETE"),calls.map{it.method})
        assertTrue(calls.all{it.token=="session" && JSONArray(it.body).getJSONObject(0).getInt("release_id")==42})
        assertTrue(calls.take(2).all{it.path.endsWith("/favorites")})
        assertEquals("WATCHING",JSONArray(calls[2].body).getJSONObject(0).getString("type_of_collection"))
        assertFalse(JSONArray(calls[3].body).getJSONObject(0).has("type_of_collection"))
    }
    @Test fun tupleResponsesParseWithoutTreatingCollectionAsFavorite(){
        assertEquals(setOf(42,51),parseFavoriteIds("[42,51,42]"))
        assertEquals(mapOf(42 to CollectionType.WATCHING,51 to CollectionType.PLANNED),parseCollectionIds("[[42,\"WATCHING\"],[51,\"PLANNED\"],[99,\"FUTURE_UNKNOWN\"]]"))
        assertEquals(listOf(AccountTimecode("episode",743.5,true)),parseTimecodes("[[\"episode\",743.5,true],[\"bad\",-1,false]]"))
    }
    @Test fun otpPreservesLeadingZeroForDisplayAndUsesDocumentedNumericLoginCode()=runBlocking{
        val calls=mutableListOf<Call>()
        val api=AccountApi(AccountTransport{p,m,b,t->calls+=Call(p,m,b,t);if(p.endsWith("/get"))"{\"otp\":{\"code\":\"058701\"},\"remaining_time\":120}"else"{\"token\":\"session\"}"})
        assertEquals("058701",api.deviceCode("device").code)
        assertEquals("session",api.deviceLogin("device","058701"))
        assertTrue(calls.all{it.token==null && it.method=="POST"})
        assertEquals(58701,JSONObject(calls.last().body).getInt("code"))
        assertEquals("device",JSONObject(calls.last().body).getString("device_id"))
    }
    @Test fun timecodeWritesUseSecondsAndWatchedFlagWithoutReleasePositionIndex()=runBlocking{
        var sent=""
        val api=AccountApi(AccountTransport{p,m,b,t->assertEquals("/accounts/users/me/views/timecodes",p);assertEquals("POST",m);assertEquals("session",t);sent=b!!;""})
        api.updateTimecodes("session",listOf(AccountTimecode("episode-uuid",12.375,false)))
        val value=JSONArray(sent).getJSONObject(0)
        assertEquals("episode-uuid",value.getString("release_episode_id"))
        assertEquals(12.375,value.getDouble("time"),.00001)
        assertFalse(value.getBoolean("is_watched"))
    }
    @Test fun localHistoryImportExcludesUnstartedAndKeepsFractionalSeconds(){
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("import-test",Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("position:one",12375).putLong("duration:one",50000).putLong("position:two",0).putLong("position:done",60000).putLong("duration:done",60000).commit()
        val records=localTimecodes(prefs).associateBy{it.episodeId}
        assertEquals(setOf("one","done"),records.keys)
        assertEquals(12.375,records.getValue("one").seconds,.00001)
        assertTrue(records.getValue("done").watched)
        assertFalse(records.getValue("one").watched)
        assertEquals(listOf(records.getValue("done")),importCandidates(records.values.toList(),setOf("one")))
    }
    @Test fun guestAndAccountPlaybackAreIsolatedAndUnsentQueueIsDurable(){
        val context=RuntimeEnvironment.getApplication()
        val session=context.getSharedPreferences("account-session",Context.MODE_PRIVATE)
        session.edit().clear().commit()
        val guest=playbackPreferences(context);guest.edit().clear().putLong("position:ep",100).commit()
        session.edit().putInt("user",123).commit()
        val signedIn=playbackPreferences(context);signedIn.edit().clear().commit()
        assertEquals(0,signedIn.getLong("position:ep",0))
        queueAccountProgress(context,"ep",12345,20000)
        assertEquals(12.345,pending(signedIn).getValue("ep").seconds,.00001)
        session.edit().putInt("user",456).commit()
        assertTrue(pending(playbackPreferences(context)).isEmpty())
        session.edit().clear().commit()
        assertEquals(100,playbackPreferences(context).getLong("position:ep",0))
    }
}
