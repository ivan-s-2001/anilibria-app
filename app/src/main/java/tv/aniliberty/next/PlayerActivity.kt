package tv.aniliberty.next

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private lateinit var root:FrameLayout
    private lateinit var video:PlayerView
    private lateinit var buffering:android.widget.ProgressBar
    private var errorPanel:TvSidePanel?=null
    private lateinit var autoNextButton:TextView
    private lateinit var controls:LinearLayout
    private lateinit var title:TextView
    private lateinit var clock:TextView
    private lateinit var seek:SeekBar
    private lateinit var previousButton:TextView
    private lateinit var pause:TextView
    private lateinit var episodesButton:TextView
    private lateinit var qualityButton:TextView
    private lateinit var nextButton:TextView
    private var panel:ScrollView?=null
    private var panelCaller:View?=null
    private var player:ExoPlayer?=null
    private var release:Release?=null
    private var current:Episode?=null
    private var quality=0
    private var stopped=false
    private var desiredPlay=true
    private var pendingSeek:Long?=null
    private var nextOffer:LinearLayout?=null
    private var progressTicks=0
    private var nextRemaining=10
    private var countdownRunning=false
    private val handler=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences("viewing",MODE_PRIVATE)}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun time(ms:Long):String{val seconds=ms.coerceAtLeast(0)/1000;return "%d:%02d".format(seconds/60,seconds%60)}
    private val hide=Runnable{if(panel==null && pendingSeek==null && player?.isPlaying==true)hideControls()}
    private val ticker=object:Runnable{
        override fun run(){
            val engine=player
            if(engine!=null){
                val duration=engine.duration.coerceAtLeast(0)
                val position=pendingSeek?:engine.currentPosition
                seek.progress=if(duration>0)(position*1000/duration).toInt().coerceIn(0,1000)else 0
                clock.text="${time(position)} / ${time(duration)}"+if(pendingSeek!=null)" · OK — перейти · Назад — отменить"else ""
                pause.text=if(engine.isPlaying)"Пауза"else"Продолжить"
                val icon=getDrawable(if(engine.isPlaying)R.drawable.ic_pause else R.drawable.ic_play)!!.mutate().apply{setBounds(0,0,dp(20),dp(20));setTintList(pause.textColors)}
                pause.setCompoundDrawablesRelative(icon,null,null,null)
                if(++progressTicks%10==0)save()
            }
            if(!stopped)handler.postDelayed(this,1000)
        }
    }
    private val countdown=object:Runnable{
        override fun run(){
            if(!countdownRunning || stopped || panel!=null)return
            nextRemaining--
            nextOffer?.getChildAt(0)?.let{(it as TextView).text="Следующий эпизод через $nextRemaining с"}
            if(nextRemaining<=0){cancelNext();nextEpisode(release?.episodes.orEmpty(),current?.id)?.let{startEpisode(it)}}
            else handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        quality=state?.getInt("quality")?:0;desiredPlay=state?.getBoolean("playing")?:true
        root=FrameLayout(this).apply{setBackgroundColor(android.graphics.Color.BLACK)}
        video=PlayerView(this).apply{useController=false}
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        buffering=android.widget.ProgressBar(this).apply{isFocusable=false;contentDescription="Загрузка видео"}
        root.addView(buffering,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
        controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(28),dp(16),dp(28),dp(20));setBackgroundColor(0xEE121212.toInt());visibility=View.GONE}
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        title=TextView(this).apply{label("Загрузка…",18f,WHITE,true);maxLines=2};controls.addView(title)
        clock=TextView(this).apply{label("",14f,MUTED)};controls.addView(clock)
        seek=SeekBar(this).apply{max=1000;progressTintList=android.content.res.ColorStateList.valueOf(RED);contentDescription="Перемотка"}
        seek.setOnKeyListener{_,key,event->
            when(key){
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{
                    if(event.action==KeyEvent.ACTION_DOWN){
                        val engine=player
                        val step=if(event.repeatCount>5)30000L else 10000L
                        pendingSeek=((pendingSeek?:engine?.currentPosition?:0)+(if(key==KeyEvent.KEYCODE_DPAD_RIGHT)step else -step)).coerceIn(0,(engine?.duration?:0).coerceAtLeast(0))
                        refreshControls()
                    };true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{if(event.action==KeyEvent.ACTION_UP){pendingSeek?.let{player?.seekTo(it)};pendingSeek=null;refreshControls()};true}
                else->false
            }
        }
        controls.addView(seek,LinearLayout.LayoutParams(-1,dp(34)))
        val buttons=LinearLayout(this);controls.addView(android.widget.HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(buttons)})
        previousButton=action(buttons,"Предыдущий",icon=R.drawable.ic_previous){
            val items=release?.episodes.orEmpty();val index=items.indexOfFirst{it.id==current?.id};items.getOrNull(index-1)?.let{startEpisode(it)}
        };buttons.addView(previousButton)
        pause=action(buttons,"Пауза",true,R.drawable.ic_play){togglePause()};buttons.addView(pause,LinearLayout.LayoutParams(dp(150),-2))
        nextButton=action(buttons,"Следующий",icon=R.drawable.ic_next){nextEpisode(release?.episodes.orEmpty(),current?.id)?.let{startEpisode(it)}};buttons.addView(nextButton)
        episodesButton=action(buttons,"Эпизоды",icon=R.drawable.ic_episodes){episodes()};buttons.addView(episodesButton)
        qualityButton=action(buttons,"Качество",icon=R.drawable.ic_settings){qualities()};buttons.addView(qualityButton)
        autoNextButton=action(buttons,if(prefs.getBoolean("auto-next",true))"Автопереход: вкл"else "Автопереход: выкл"){
            val enabled=!prefs.getBoolean("auto-next",true);prefs.edit().putBoolean("auto-next",enabled).apply()
            autoNextButton.text=if(enabled)"Автопереход: вкл"else "Автопереход: выкл"
            if(!enabled)cancelNext()
            Toast.makeText(this,if(enabled)"Автопереход включён"else"Автопереход отключён",Toast.LENGTH_SHORT).show()
        };buttons.addView(autoNextButton)
        for(index in 0 until buttons.childCount){buttons.getChildAt(index).layoutParams=(buttons.getChildAt(index).layoutParams as?LinearLayout.LayoutParams?:LinearLayout.LayoutParams(-2,-2)).apply{marginEnd=dp(8)}}
        setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){
            when{
                panel!=null->closePanel()
                pendingSeek!=null->{pendingSeek=null;refreshControls()}
                nextOffer!=null->{cancelNext();showControls()}
                controls.visibility==View.VISIBLE->hideControls()
                else->finish()
            }
        }})
        val id=intent.getIntExtra("release",0)
        val episodeId=state?.getString("episode")?:intent.getStringExtra("episode")
        lifecycleScope.launch{
            try{
                val item=Api().release(id);release=item
                if(item.blocked)error("Просмотр ограничен")
                val episode=item.episodes.find{it.id==episodeId}?:error("Эпизод не найден")
                if(episode.streams.isEmpty())error("Нет доступного потока")
                current=episode
                if(!stopped)startEpisode(episode,quality,desiredPlay)
            }catch(e:CancellationException){throw e}catch(_:Exception){
                if(!isFinishing && !isDestroyed)showPlaybackError("Просмотр недоступен или нет подключения."){recreate()}
            }
        }
    }
    private fun showPlaybackError(message:String,retry:()->Unit){
        buffering.visibility=View.GONE;errorPanel?.dismiss()
        val dialog=TvSidePanel(this,"Не удалось воспроизвести")
        errorPanel=dialog;dialog.message(message)
        dialog.addChoice("Повторить",icon=R.drawable.ic_refresh){dialog.dismiss();retry()}
        if(current?.streams?.size?.let{it>1}==true)dialog.addChoice("Выбрать качество",icon=R.drawable.ic_settings){dialog.dismiss();qualities()}
        dialog.addAction("Вернуться к тайтлу"){dialog.dismiss();finish()}
        dialog.setOnCancelListener{if(current==null)finish()else showControls()}
        dialog.show()
    }
    private fun refreshControls(){handler.removeCallbacks(hide);handler.postDelayed(hide,5000)}
    private fun showControls(focus:View?=null){controls.visibility=View.VISIBLE;(focus?:pause).requestFocus();refreshControls()}
    private fun hideControls(){controls.visibility=View.GONE;pendingSeek=null;video.requestFocus()}
    private fun togglePause(){val engine=player?:return;if(engine.isPlaying)engine.pause()else engine.play();desiredPlay=engine.playWhenReady;save();showControls()}
    private fun startEpisode(episode:Episode, requestedQuality:Int=0, play:Boolean=true){
        val selected=requestedQuality.takeIf{it in episode.streams.indices}?:0
        val stream=episode.streams.getOrNull(selected)?:run{Toast.makeText(this,"Нет доступного потока",Toast.LENGTH_LONG).show();return}
        errorPanel?.dismiss();errorPanel=null
        buffering.visibility=View.VISIBLE
        save();cancelNext();player?.release();current=episode;quality=selected;desiredPlay=play
        val engine=ExoPlayer.Builder(this).build();player=engine;video.player=engine
        title.text="${release?.title.orEmpty()} · ${episode.label}"
        previousButton.visibility=if(release?.episodes?.indexOfFirst{it.id==episode.id}?.let{it>0}==true)View.VISIBLE else View.GONE
        nextButton.visibility=if(nextEpisode(release?.episodes.orEmpty(),episode.id)!=null)View.VISIBLE else View.GONE
        engine.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                if(player!==engine || stopped)return
                save();cancelNext();showControls()
                showPlaybackError("Повторите загрузку или выберите другое качество."){startEpisode(episode,quality)}
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(!isPlaying)save()}
            override fun onPlaybackStateChanged(state:Int){
                if(player!==engine)return
                buffering.visibility=if(state==Player.STATE_BUFFERING)View.VISIBLE else View.GONE
                if(state==Player.STATE_ENDED){save();offerNext()}
            }
        })
        engine.setMediaItem(MediaItem.fromUri(stream.second));engine.prepare()
        val saved=prefs.getLong("position:${episode.id}",0)
        val duration=prefs.getLong("duration:${episode.id}",0)
        engine.seekTo(if(duration>0 && saved>=duration-1000)0 else saved);engine.playWhenReady=play
        if(panel==null)hideControls()
    }
    private fun save(){
        val engine=player?:return;val episode=current?:return;val item=release?:return
        val duration=engine.duration.takeIf{it>0}?:episode.duration*1000
        if(engine.currentPosition==0L && engine.duration<=0 && prefs.getLong("position:${episode.id}",0)>0)return
        ViewingHistory(prefs).save(item,episode,engine.currentPosition,duration)
        prefs.edit().putLong("position:${episode.id}",engine.currentPosition.coerceAtLeast(0)).putLong("duration:${episode.id}",duration).putString("last:${item.id}",episode.id).apply()
    }
    private fun openPanel(caption:String,caller:View,build:(LinearLayout)->View?){
        cancelNext();handler.removeCallbacks(hide)
        panel?.let{root.removeView(it)};panelCaller=caller
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));setBackgroundColor(0xFF212121.toInt())}
        content.addView(TextView(this).apply{label(caption,20f,WHITE,true)},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
        content.addView(action(content,"Закрыть"){closePanel()},LinearLayout.LayoutParams(-1,dp(46)))
        val target=build(content)
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(content);isFocusable=false}
        panel=scroll;controls.visibility=View.GONE
        root.addView(scroll,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*.42f).toInt(),-1,Gravity.END))
        scroll.post{(target?:content.getChildAt(1)).requestFocus()}
    }
    private fun closePanel(){panel?.let{root.removeView(it)};panel=null;showControls(panelCaller);panelCaller=null}
    private fun episodes(){
        val item=release?:return
        openPanel("Эпизоды",episodesButton){content->
            var target:View?=null
            item.episodes.forEach{episode->
                val position=prefs.getLong("position:${episode.id}",0)
                val duration=prefs.getLong("duration:${episode.id}",0)
                val status=when{episode.id==current?.id->"Сейчас";duration>0 && position>=duration-1000->"Просмотрено";position>0->"Продолжить · ${time(position)}";else->""}
                val button=action(content,listOf(episode.label,status).filter{it.isNotBlank()}.joinToString("\n")){
                    startEpisode(episode);closePanel()
                }.apply{gravity=Gravity.CENTER_VERTICAL;maxLines=3;setPadding(dp(12),dp(8),dp(12),dp(8))}
                content.addView(button,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
                if(episode.id==current?.id)target=button
            };target
        }
    }
    private fun qualities(){
        val episode=current?:return
        openPanel("Качество",qualityButton){content->
            var target:View?=null
            episode.streams.forEachIndexed{index,stream->
                val button=action(content,(if(index==quality)"✓  "else"")+stream.first){val playing=player?.playWhenReady?:desiredPlay;startEpisode(episode,index,playing);closePanel()}
                content.addView(button,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})
                if(index==quality)target=button
            };target
        }
    }
    private fun cancelNext(){countdownRunning=false;handler.removeCallbacks(countdown);nextOffer?.let{root.removeView(it)};nextOffer=null}
    private fun offerNext(){
        cancelNext();val episode=nextEpisode(release?.episodes.orEmpty(),current?.id)?:run{showControls();return}
        if(panel!=null || stopped)return
        if(episode.streams.isEmpty()){showControls();Toast.makeText(this,"Следующая серия пока недоступна",Toast.LENGTH_LONG).show();return}
        val offer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(16));setBackgroundColor(0xFF212121.toInt())}
        nextOffer=offer;nextRemaining=10
        val auto=prefs.getBoolean("auto-next",true)
        offer.addView(TextView(this).apply{label(if(auto)"Следующий эпизод через 10 с"else"Следующий эпизод",18f,WHITE,true)})
        offer.addView(TextView(this).apply{label(episode.label,16f,MUTED)})
        val now=action(offer,"Смотреть сейчас",true){cancelNext();startEpisode(episode)};offer.addView(now)
        offer.addView(action(offer,"Отмена"){cancelNext();showControls()})
        root.addView(offer,FrameLayout.LayoutParams(dp(340),-2,Gravity.END or Gravity.BOTTOM).apply{setMargins(dp(20),dp(20),dp(20),dp(20))})
        hideControls();now.requestFocus();countdownRunning=auto;if(auto)handler.postDelayed(countdown,1000)
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(event.keyCode==KeyEvent.KEYCODE_BACK)return super.dispatchKeyEvent(event)
        if(panel!=null){
            val direction=when(event.keyCode){KeyEvent.KEYCODE_DPAD_LEFT->View.FOCUS_LEFT;KeyEvent.KEYCODE_DPAD_RIGHT->View.FOCUS_RIGHT;KeyEvent.KEYCODE_DPAD_UP->View.FOCUS_UP;KeyEvent.KEYCODE_DPAD_DOWN->View.FOCUS_DOWN;else->0}
            if(direction!=0){val candidate=currentFocus?.focusSearch(direction);if(candidate==null || !insidePanel(candidate))return true}
            return super.dispatchKeyEvent(event)
        }
        if(nextOffer!=null){
            val direction=when(event.keyCode){KeyEvent.KEYCODE_DPAD_LEFT->View.FOCUS_LEFT;KeyEvent.KEYCODE_DPAD_RIGHT->View.FOCUS_RIGHT;KeyEvent.KEYCODE_DPAD_UP->View.FOCUS_UP;KeyEvent.KEYCODE_DPAD_DOWN->View.FOCUS_DOWN;else->0}
            if(direction!=0){val target=currentFocus?.focusSearch(direction);if(target==null || !insideView(target,nextOffer!!))return true}
            return super.dispatchKeyEvent(event)
        }
        when(event.keyCode){
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{if(event.action==KeyEvent.ACTION_DOWN)togglePause();return true}
            KeyEvent.KEYCODE_MEDIA_PLAY->{if(event.action==KeyEvent.ACTION_DOWN)player?.play();return true}
            KeyEvent.KEYCODE_MEDIA_PAUSE->{if(event.action==KeyEvent.ACTION_DOWN){player?.pause();save()};return true}
            KeyEvent.KEYCODE_MENU->{if(event.action==KeyEvent.ACTION_DOWN)episodes();return true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{if(controls.visibility!=View.VISIBLE){if(event.action==KeyEvent.ACTION_UP)showControls();return true}}
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{if(controls.visibility!=View.VISIBLE){if(event.action==KeyEvent.ACTION_UP){showControls(seek);pendingSeek=player?.currentPosition};return true}}
        }
        if(event.action==KeyEvent.ACTION_DOWN)refreshControls()
        return super.dispatchKeyEvent(event)
    }
    private fun insidePanel(view:View):Boolean=panel?.let{insideView(view,it)}?:false
    private fun insideView(view:View,container:View):Boolean{var node:android.view.ViewParent?=view.parent;while(node!=null){if(node===container)return true;node=node.parent};return false}
    override fun onStart(){super.onStart();stopped=false;handler.post(ticker);if(player==null)current?.let{startEpisode(it,quality,desiredPlay)}}
    override fun onStop(){errorPanel?.dismiss();errorPanel=null;stopped=true;desiredPlay=player?.playWhenReady?:desiredPlay;save();cancelNext();handler.removeCallbacksAndMessages(null);video.player=null;player?.release();player=null;super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("episode",current?.id);out.putInt("quality",quality);out.putBoolean("playing",player?.playWhenReady?:desiredPlay);super.onSaveInstanceState(out)}
}
