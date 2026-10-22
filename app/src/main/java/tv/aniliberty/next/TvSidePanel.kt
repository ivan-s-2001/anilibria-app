package tv.aniliberty.next

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A single, remote-friendly surface shared by catalogue tools. */
open class TvSidePanel(context:Context,title:String):Dialog(context){
    val heading=TextView(context).apply{label(title,24f,WHITE,true);includeFontPadding=false;setPadding(0,dp(4),0,dp(20))}
    val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    val footer=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val scroll=ScrollView(context).apply{isFillViewport=false;addView(body);overScrollMode=View.OVER_SCROLL_NEVER}
    private val shell=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(24),dp(24),dp(24),dp(24))
        setBackgroundColor(0xFF212121.toInt())
        addView(heading,LinearLayout.LayoutParams(-1,-2))
        addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        addView(footer,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
    }
    protected fun dp(n:Int)=(context.resources.displayMetrics.density*n).toInt()
    init{setContentView(shell)}
    fun message(text:String){
        body.removeAllViews()
        body.addView(TextView(context).apply{label(text,16f,MUTED);setPadding(0,dp(12),0,dp(12))})
    }
    fun addAction(text:String,primary:Boolean=false,callback:()->Unit):TextView =
        action(footer,text,primary=primary,click=callback).also{footer.addView(it,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(8)})}
    fun addChoice(text:String,selected:Boolean=false,icon:Int=0,callback:()->Unit):TextView =
        action(body,if(selected)"✓  $text"else text,icon=icon,click=callback).apply{
            textSize=16f;maxLines=3;setPadding(dp(12),dp(12),dp(12),dp(12))
            body.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
    override fun show(){
        super.show()
        window?.apply{
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels*.42f).toInt(),-1)
            setGravity(Gravity.END)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.55f)
        }
        body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
            ?:footer.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
    }
}
