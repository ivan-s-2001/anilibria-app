package tv.aniliberty.next

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

enum class CollectionType(val title:String){
    PLANNED("В планах"),WATCHING("Смотрю"),WATCHED("Просмотрено"),POSTPONED("Отложено"),ABANDONED("Брошено")
}
data class AccountProfile(val id:Int,val name:String)
data class AccountLists(val favorites:Set<Int>,val collections:Map<Int,CollectionType>)
data class AccountTimecode(val episodeId:String,val seconds:Double,val watched:Boolean)
data class AccountHistoryItem(val releaseId:Int,val episodeId:String,val updatedAt:Long)
data class DeviceCode(val code:String,val remainingSeconds:Int)
class AccountHttpException(val status:Int):Exception(when(status){401,403->"Нужно войти в аккаунт заново";404->"Данные не найдены";429->"Слишком много запросов. Повторите позже";else->"Сервис ответил: $status"})
fun interface AccountTransport { suspend fun request(path:String,method:String,body:String?,token:String?):String }
class AccountHttpTransport:AccountTransport {
    override suspend fun request(path:String,method:String,body:String?,token:String?):String=withContext(Dispatchers.IO){
        val connection=URI("https://aniliberty.top/api/v1$path").toURL().openConnection()as HttpURLConnection
        try{
            connection.connectTimeout=12000;connection.readTimeout=20000;connection.requestMethod=method
            connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("User-Agent","AniLiberty-Next/Android-TV")
            if(token!=null)connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
            if(connection.responseCode !in 200..299)throw AccountHttpException(connection.responseCode)
            connection.inputStream.bufferedReader().use{it.readText()}
        }finally{connection.disconnect()}
    }
}
class AccountApi(private val transport:AccountTransport=AccountHttpTransport()){
    suspend fun deviceCode(deviceId:String):DeviceCode {
        val json=JSONObject(transport.request("/accounts/otp/get","POST",JSONObject().put("device_id",deviceId).toString(),null))
        val code=json.getJSONObject("otp").getString("code")
        require(Regex("[0-9]{6}").matches(code)){"Сервис вернул некорректный код"}
        return DeviceCode(code,json.getInt("remaining_time").coerceIn(1,600))
    }
    suspend fun deviceLogin(deviceId:String,code:String):String = JSONObject(transport.request("/accounts/otp/login","POST",JSONObject().put("device_id",deviceId).put("code",code.toInt()).toString(),null)).getString("token").also{require(it.isNotBlank())}
    suspend fun profile(token:String):AccountProfile {
        val json=JSONObject(transport.request("/accounts/users/me/profile?include=id,nickname,login","GET",null,token))
        return AccountProfile(json.getInt("id"),json.optString("nickname").ifBlank{json.optString("login").ifBlank{"Аккаунт AniLiberty"}})
    }
    suspend fun lists(token:String):AccountLists {
        val favorites=parseFavoriteIds(transport.request("/accounts/users/me/favorites/ids","GET",null,token))
        val collections=parseCollectionIds(transport.request("/accounts/users/me/collections/ids","GET",null,token))
        return AccountLists(favorites,collections)
    }
    suspend fun favorite(token:String,id:Int,added:Boolean){transport.request("/accounts/users/me/favorites",if(added)"POST"else"DELETE",releaseBody(id).toString(),token)}
    suspend fun collection(token:String,id:Int,type:CollectionType?){
        val body=releaseBody(id)
        if(type!=null)body.getJSONObject(0).put("type_of_collection",type.name)
        transport.request("/accounts/users/me/collections",if(type==null)"DELETE"else"POST",body.toString(),token)
    }
    suspend fun timecodes(token:String):List<AccountTimecode> = parseTimecodes(transport.request("/accounts/users/me/views/timecodes","GET",null,token))
    suspend fun updateTimecodes(token:String,values:List<AccountTimecode>){
        if(values.isEmpty())return
        val body=JSONArray();values.forEach{v->body.put(JSONObject().put("release_episode_id",v.episodeId).put("time",v.seconds.coerceAtLeast(0.0)).put("is_watched",v.watched))}
        transport.request("/accounts/users/me/views/timecodes","POST",body.toString(),token)
    }
    suspend fun recentHistory(token:String):List<AccountHistoryItem> = parseAccountHistory(transport.request("/accounts/users/me/views/history?limit=30","GET",null,token))
    suspend fun logout(token:String){transport.request("/accounts/users/auth/logout","POST",null,token)}
    private fun releaseBody(id:Int)=JSONArray().put(JSONObject().put("release_id",id))
}
internal fun parseFavoriteIds(body:String):Set<Int>{val json=JSONArray(body);return(0 until json.length()).mapNotNull{json.optInt(it,-1).takeIf{it>0}}.toSet()}
internal fun parseCollectionIds(body:String):Map<Int,CollectionType>{
    val json=JSONArray(body)
    return(0 until json.length()).mapNotNull{i->val pair=json.optJSONArray(i)?:return@mapNotNull null
        val id=pair.optInt(0,-1);val type=CollectionType.entries.firstOrNull{it.name==pair.optString(1)}
        if(id>0 && type!=null)id to type else null
    }.toMap()
}
internal fun parseTimecodes(body:String):List<AccountTimecode>{
    val json=JSONArray(body)
    return(0 until json.length()).mapNotNull{i->val tuple=json.optJSONArray(i)?:return@mapNotNull null
        val id=tuple.optString(0);val seconds=tuple.optDouble(1,Double.NaN)
        if(id.isBlank()||!seconds.isFinite()||seconds<0)null else AccountTimecode(id,seconds,tuple.optBoolean(2))
    }
}

internal fun parseAccountHistory(body:String):List<AccountHistoryItem>{
    val data=JSONObject(body).optJSONArray("data")?:return emptyList()
    return(0 until data.length()).mapNotNull{i->
        val item=data.optJSONObject(i)?:return@mapNotNull null
        val episode=item.optJSONObject("release_episode")?:return@mapNotNull null
        val release=episode.optJSONObject("release")?.optInt("id",-1)?:-1
        val id=item.optString("release_episode_id").ifBlank{episode.optString("id")}
        val updated=runCatching{java.time.OffsetDateTime.parse(item.optString("updated_at")).toInstant().toEpochMilli()}.getOrDefault(0)
        if(release>0 && id.isNotBlank())AccountHistoryItem(release,id,updated)else null
    }.sortedByDescending{it.updatedAt}
}
