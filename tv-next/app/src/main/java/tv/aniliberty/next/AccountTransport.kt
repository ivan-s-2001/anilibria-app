package tv.aniliberty.next

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccountValidationException(message: String) : Exception(message)

class AccountHttpException(val status: Int) :
    Exception(
        when (status) {
            401,
            403 -> "Нужно войти в аккаунт заново"
            404 -> "Данные не найдены"
            429 -> "Слишком много запросов. Повторите позже"
            else -> "Сервис ответил: $status"
        }
    )

fun interface AccountTransport {
    suspend fun request(path: String, method: String, body: String?, token: String?): String
}

class AccountHttpTransport : AccountTransport {
    override suspend fun request(
        path: String,
        method: String,
        body: String?,
        token: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val connection =
                URI("https://aniliberty.top/api/v1$path").toURL().openConnection()
                    as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 20000
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "AniLiberty-Next/Android-TV")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode !in 200..299) {
                    val status = connection.responseCode
                    if (status == 422) {
                        val message =
                            runCatching {
                                    val error =
                                        JSONObject(
                                            connection.errorStream.bufferedReader().use {
                                                it.readText()
                                            }
                                        )
                                    val errors = error.optJSONObject("errors")
                                    errors
                                        ?.keys()
                                        ?.asSequence()
                                        ?.mapNotNull { errors.optJSONArray(it)?.optString(0) }
                                        ?.firstOrNull()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: error.optString("message").takeIf { it.isNotBlank() }
                                }
                                .getOrNull()
                        throw AccountValidationException(
                            message?.take(300) ?: "Проверьте введённые данные"
                        )
                    }
                    throw AccountHttpException(status)
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
}
