package tv.aniliberty.next

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The phone authorizes on the official website. TV only receives its own OTP session. */
class AccountPanel(context:Context,private val scope:CoroutineScope,private val account:AccountRepository,private val connected:()->Unit):TvSidePanel(context,"Вход в AniLiberty"){
    private var task:Job?=null
    private val link="https://www.aniliberty.top/app/auth/otp/linkDevice"
    init{setOnDismissListener{task?.cancel()}}
    fun start(){show();requestCode()}
    private fun requestCode(){
        task?.cancel();message("Получаем код устройства…");footer.removeAllViews();addAction("Отмена"){dismiss()}
        task=scope.launch{
            try{
                val device=account.session.deviceId
                val code=account.api.deviceCode(device)
                message("На телефоне войдите в свой аккаунт на сайте AniLiberty и откройте страницу привязки устройства.")
                body.addView(TextView(context).apply{label(code.code,36f,WHITE,true);gravity=Gravity.CENTER;letterSpacing=.12f;setPadding(0,dp(12),0,dp(12));contentDescription="Код устройства: ${code.code.toCharArray().joinToString(" ")}"})
                val matrix=QRCodeWriter().encode(link,BarcodeFormat.QR_CODE,256,256)
                val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                val pixels=IntArray(256*256){i->if(matrix[i%256,i/256])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
                bitmap.setPixels(pixels,0,256,0,0,256,256)
                body.addView(ImageView(context).apply{setImageBitmap(bitmap);contentDescription="QR-код страницы привязки"},LinearLayout.LayoutParams(dp(156),dp(156)).apply{gravity=Gravity.CENTER_HORIZONTAL})
                body.addView(TextView(context).apply{label("aniliberty.top/app/auth/otp/linkDevice",12f,MUTED);gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(8))})
                val status=TextView(context).apply{label("Ожидаем подтверждение на телефоне…",14f,MUTED);gravity=Gravity.CENTER}
                body.addView(status)
                val deadline=android.os.SystemClock.elapsedRealtime()+code.remainingSeconds*1000L
                val token=coroutineScope{
                    var progress="Ожидаем подтверждение на телефоне…"
                    val ticker=launch{
                        while(true){
                            val seconds=((deadline-android.os.SystemClock.elapsedRealtime())/1000).coerceAtLeast(0)
                            status.text="$progress\nКод действует ещё $seconds с"
                            delay(1000)
                        }
                    }
                    try{awaitDeviceLogin(deadline,status={progress=it}){account.api.deviceLogin(device,code.code)}}finally{ticker.cancel()}
                }
                if(token!=null){
                    status.text="Подтверждено. Загружаем профиль…"
                    suspend fun finishLogin(){account.connect(token);dismiss();connected()}
                    try{finishLogin();return@launch}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){
                        status.text="Устройство подтверждено. Не удалось загрузить профиль. Можно повторить без нового кода."
                        footer.removeAllViews()
                        addAction("Завершить вход",true){
                            task=scope.launch{try{finishLogin()}catch(e:CancellationException){throw e}catch(_:Exception){status.text="Профиль пока недоступен. Проверьте соединение и повторите."}}
                        }
                        addAction("Закрыть"){dismiss()};footer.getChildAt(0)?.requestFocus();return@launch
                    }
                }
                message("Срок действия кода истёк. Получите новый код.")
            }catch(e:CancellationException){throw e}
            catch(_:Exception){message("Не удалось войти. Проверьте подключение и повторите.")}
            footer.removeAllViews();addAction("Получить новый код",true){requestCode()};addAction("Закрыть"){dismiss()};footer.getChildAt(0)?.requestFocus()
        }
    }
}
