package tv.aniliberty.next

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CatalogFilterPanel(context:Context,screen:Screen.Catalog,private val references:Map<String,List<CatalogChoice>>,private val apply:(CatalogFilter)->Unit):Dialog(context){
    private var draft=screen.filters.copy(genres=screen.filters.genres+listOfNotNull(screen.genre?.toString()))
    private var focusIndex=0
    private val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val shell=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));setBackgroundColor(0xFF212121.toInt())}
    private fun dp(n:Int)=(context.resources.displayMetrics.density*n).toInt()
    init{
        shell.addView(TextView(context).apply{label("Фильтры",24f,WHITE,true)},LinearLayout.LayoutParams(-1,dp(48)))
        shell.addView(ScrollView(context).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        shell.addView(action(shell,"Применить",primary=true){apply(draft);dismiss()},LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(16)})
        shell.addView(action(shell,"Сбросить"){draft=CatalogFilter(sorting=draft.sorting);build()},LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(8)})
        setContentView(shell);build()
    }
    private fun selection(key:String):Set<String> = when(key){
        "genres"->draft.genres;"types"->draft.types;"seasons"->draft.seasons;"age-ratings"->draft.ages;"publish-statuses"->draft.publish;"production-statuses"->draft.production;else->emptySet()
    }
    private fun change(key:String,values:Set<String>){draft=when(key){
        "genres"->draft.copy(genres=values);"types"->draft.copy(types=values);"seasons"->draft.copy(seasons=values);"age-ratings"->draft.copy(ages=values);"publish-statuses"->draft.copy(publish=values);"production-statuses"->draft.copy(production=values);else->draft
    }}
    private fun build(){
        body.removeAllViews()
        val fields=catalogReferencePaths.filterKeys{it !in setOf("years","sorting")}
        fields.forEach{(key,title)->
            val choices=references[key].orEmpty()
            val selected=selection(key)
            val summary=choices.filter{it.value in selected}.joinToString(" · "){it.title}.ifBlank{"Все"}
            body.addView(action(body,"$title\n$summary"){
                focusIndex=fields.keys.indexOf(key)
                val checked=selected.toMutableSet()
                AlertDialog.Builder(context).setTitle(title).setMultiChoiceItems(choices.map{it.title}.toTypedArray(),choices.map{it.value in checked}.toBooleanArray()){_,i,on->if(on)checked.add(choices[i].value)else checked.remove(choices[i].value)}
                    .setPositiveButton("Выбрать"){_,_->change(key,checked);build()}.setNegativeButton("Отмена",null).show()
            }.apply{maxLines=3;textSize=15f;setPadding(dp(12),dp(10),dp(12),dp(10))},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
        listOf(true,false).forEach{from->
            val current=if(from)draft.fromYear else draft.toYear
            body.addView(action(body,(if(from)"Год от: "else"Год до: ")+current.ifBlank{"Любой"}){
                focusIndex=fields.size+if(from)0 else 1
                val years=references["years"].orEmpty().filter{
                    if(from)draft.toYear.isBlank()||it.value.toInt()<=draft.toYear.toInt()
                    else draft.fromYear.isBlank()||it.value.toInt()>=draft.fromYear.toInt()
                }
                AlertDialog.Builder(context).setTitle(if(from)"Год от"else"Год до")
                    .setItems((listOf("Любой")+years.map{it.title}).toTypedArray()){_,i->
                        val value=if(i==0)""else years[i-1].value
                        draft=if(from)draft.copy(fromYear=value)else draft.copy(toYear=value);build()
                    }.setNegativeButton("Отмена",null).show()
            },LinearLayout.LayoutParams(-1,dp(46)).apply{bottomMargin=dp(8)})
        }
        body.post{body.getChildAt(focusIndex)?.requestFocus()}
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
        body.getChildAt(0)?.requestFocus()
    }
}
