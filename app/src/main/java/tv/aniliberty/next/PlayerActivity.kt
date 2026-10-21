package tv.aniliberty.next

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private lateinit var video: PlayerView
    private var player: ExoPlayer? = null
    private var release: Release? = null
    private var current: Episode? = null
    private val prefs by lazy { getSharedPreferences("viewing",MODE_PRIVATE) }
    private var stopped = false
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=FrameLayout(this)
        video=PlayerView(this).apply{controllerShowTimeoutMs=3000;setShowNextButton(false);setShowPreviousButton(false)}
        root.addView(video,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        val id=intent.getIntExtra("release",0)
        val episodeId=state?.getString("episode")?:intent.getStringExtra("episode")
        lifecycleScope.launch{
            try{
                val item=Api().release(id);release=item
                val episode=item.episodes.find{it.id==episodeId}?:error("Эпизод не найден")
                current=episode
                if(!stopped)startEpisode(episode)
            }catch(e:CancellationException){throw e}catch(_:Exception){
                AlertDialog.Builder(this@PlayerActivity).setTitle("Не удалось начать просмотр").setMessage("Проверьте подключение и повторите попытку.").setPositiveButton("Назад"){_,_->finish()}.setOnCancelListener{finish()}.show()
            }
        }
    }
    private fun startEpisode(episode:Episode, quality:Int=0){
        val stream=episode.streams.getOrNull(quality)?:run{Toast.makeText(this,"Нет доступного потока",Toast.LENGTH_LONG).show();return}
        save();player?.release();current=episode
        val engine=ExoPlayer.Builder(this).build();player=engine;video.player=engine
        engine.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                AlertDialog.Builder(this@PlayerActivity).setTitle("Ошибка воспроизведения").setMessage("Попробуйте другое качество или повторите загрузку.").setPositiveButton("Повторить"){_,_->startEpisode(episode,quality)}.setNeutralButton("Качество"){_,_->qualities()}.setNegativeButton("Назад"){_,_->finish()}.show()
            }
            override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED)save()}
        })
        engine.setMediaItem(MediaItem.fromUri(stream.second));engine.prepare()
        engine.seekTo(prefs.getLong("position:${episode.id}",0));engine.playWhenReady=true
        video.requestFocus();video.hideController()
    }
    private fun save(){
        val engine=player?:return;val episode=current?:return;val item=release?:return
        val duration=engine.duration.takeIf{it>0}?:episode.duration*1000
        prefs.edit().putLong("position:${episode.id}",engine.currentPosition.coerceAtLeast(0)).putLong("duration:${episode.id}",duration).putString("last:${item.id}",episode.id).apply()
    }
    private fun episodes(){
        val item=release?:return
        video.hideController()
        AlertDialog.Builder(this).setTitle("Эпизоды").setSingleChoiceItems(item.episodes.map{it.label}.toTypedArray(),item.episodes.indexOfFirst{it.id==current?.id}){dialog,index->
            dialog.dismiss();startEpisode(item.episodes[index])
        }.setNegativeButton("Закрыть",null).show()
    }
    private fun qualities(){
        val episode=current?:return
        AlertDialog.Builder(this).setTitle("Качество").setItems(episode.streams.map{it.first}.toTypedArray()){_,index->startEpisode(episode,index)}.show()
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean{
        if(keyCode==KeyEvent.KEYCODE_MENU){AlertDialog.Builder(this).setItems(arrayOf("Эпизоды","Качество")){_,index->if(index==0)episodes()else qualities()}.show();return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onStart(){super.onStart();stopped=false;if(player==null)current?.let{startEpisode(it)}}
    override fun onStop(){stopped=true;save();video.player=null;player?.release();player=null;super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("episode",current?.id);super.onSaveInstanceState(out)}
}
