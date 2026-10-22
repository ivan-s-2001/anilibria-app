package tv.aniliberty.next

import java.time.Instant
import java.time.OffsetDateTime

fun relativeUpdate(value:String,now:Instant=Instant.now()):String {
    val instant=runCatching{Instant.parse(value)}.getOrElse{
        runCatching{OffsetDateTime.parse(value).toInstant()}.getOrNull()
    }?:return ""
    val minutes=java.time.Duration.between(instant,now).toMinutes()
    if(minutes<0)return ""
    if(minutes<1)return "Только что"
    val days=minutes/1440;val hours=minutes/60%24
    val text=when{
        days>0->listOf("$days д.",if(hours>0)"$hours ч."else "").filter(String::isNotBlank).joinToString(" ")
        minutes>=60->"${minutes/60} ч."
        else->"$minutes мин."
    }
    return "$text назад"
}
