package tv.aniliberty.next

import android.app.Application
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],application=Application::class)
class HomeShelfTest {
    @Test fun genreAndVideoDarkenOnlyWhileFocusedAndVideoRevealsInfo(){
        for(kind in listOf(Kind.GENRE,Kind.FEATURE)){
            val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
            adapter.submit(listOf(Row("preview",kind,"Название","Данные")))
            val parent=FrameLayout(RuntimeEnvironment.getApplication())
            val holder=adapter.onCreateViewHolder(parent,kind.ordinal)
            adapter.onBindViewHolder(holder,0)
            val card=holder.itemView as FrameLayout
            parent.addView(card)
            val other=android.widget.TextView(parent.context).apply{isFocusable=true;isFocusableInTouchMode=true}
            parent.addView(other)
            val image=card.getChildAt(0)as android.widget.ImageView
            assertNull(image.colorFilter)
            card.requestFocus();assertNotNull(image.colorFilter)
            if(kind==Kind.FEATURE)assertEquals(android.view.View.VISIBLE,card.getChildAt(2).visibility)
            other.requestFocus();assertFalse(card.hasFocus());assertNull(image.colorFilter)
            if(kind==Kind.FEATURE)assertEquals(android.view.View.GONE,card.getChildAt(2).visibility)
        }
    }
    @Test fun franchiseCompositionHasOneNumberedLinePerPartOnFocus(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        val parts=(1..3).map{parseRelease(org.json.JSONObject().put("id",it).put("name",org.json.JSONObject().put("main","Часть $it")))}
        adapter.submit(listOf(Row("franchise",Kind.FRANCHISE,"Франшиза","3 части",items=parts)))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.FRANCHISE.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        val content=card.getChildAt(2)as android.widget.LinearLayout
        val lines=card.findViewWithTag<android.widget.LinearLayout>("franchise-composition")
        assertEquals(android.view.View.GONE,lines.visibility)
        card.requestFocus()
        assertEquals(android.view.View.VISIBLE,lines.visibility)
        assertEquals(3,lines.childCount)
        assertEquals(card.getChildAt(0).layoutParams.width,lines.layoutParams.width)
        assertEquals(android.view.View.VISIBLE,content.getChildAt(0).visibility)
        assertEquals(android.view.View.VISIBLE,content.getChildAt(1).visibility)
        for(index in 0..2){val line=lines.getChildAt(index)as android.widget.TextView;assertEquals("${index+1}. Часть ${index+1}",line.text.toString());assertEquals(1,line.maxLines)}
    }
    @Test fun franchiseDirectoryKeepsHomeAspectWhenColumnWidthChanges(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{})
        adapter.submit(listOf(Row("franchise",Kind.FRANCHISE,"Франшиза",homeStyle=true)))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.FRANCHISE.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        for(width in listOf(624,936)){
            card.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
            assertEquals(312f/190f,width.toFloat()/card.measuredHeight,.01f)
            assertEquals(150f/312f,card.getChildAt(0).layoutParams.width.toFloat()/width,.01f)
        }
    }
    @Test fun inlineSearchRetainsEditorAndCursorWhenResultsRebind(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{})
        var query=""
        val row=Row("catalogue-tools",Kind.CATALOG_BAR,title="",selected=1,queryChanged={query=it},controls=listOf("Поиск","Фильтры","Сортировка","Сбросить").map{it to {}})
        adapter.submit(listOf(row))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.CATALOG_BAR.ordinal)
        adapter.onBindViewHolder(holder,0)
        val line=(holder.itemView as android.widget.HorizontalScrollView).getChildAt(0)as android.widget.LinearLayout
        val input=line.getChildAt(0)as android.widget.EditText
        input.setText("Наруто");input.setSelection(3)
        assertEquals("Наруто",query)
        adapter.submit(listOf(row.copy(title=query)))
        adapter.onBindViewHolder(holder,0)
        assertSame(input,line.getChildAt(0))
        assertEquals(3,input.selectionStart)
        assertEquals(4,line.childCount)
        holder.itemView.measure(android.view.View.MeasureSpec.makeMeasureSpec(900,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(120,android.view.View.MeasureSpec.AT_MOST))
        holder.itemView.layout(0,0,900,holder.itemView.measuredHeight)
        holder.itemView.measure(android.view.View.MeasureSpec.makeMeasureSpec(900,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(120,android.view.View.MeasureSpec.AT_MOST))
        holder.itemView.layout(0,0,900,holder.itemView.measuredHeight)
        assertEquals(0,input.left)
        assertEquals(900,line.getChildAt(3).right)
        assertTrue(input.width>0)
        for(index in 1..3)assertEquals(line.getChildAt(index).height,line.getChildAt(index).width)
    }
    @Test fun previewHasNineItemsThenAnExplicitDestination(){
        var opened=false
        val source=(1..20).map{Row("genre:$it",Kind.FEATURE,"Жанр $it")}
        val cards=shelfCards("genres","Жанры",source,true){opened=true}
        assertEquals(10,cards.size)
        assertEquals((1..9).map{"genre:$it"},cards.dropLast(1).map{it.key})
        assertEquals(Kind.MORE,cards.last().kind)
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(cards)
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.MORE.ordinal)
        adapter.onBindViewHolder(holder,9)
        assertTrue(holder.itemView.isFocusable)
        holder.itemView.performClick()
        assertTrue(opened)
    }
    @Test fun emptyOrShortPreviewStillOpensFullSection(){
        val empty=shelfCards("videos","Видео",emptyList()){ }
        assertEquals(1,empty.size);assertEquals("videos:all",empty.single().key)
        val short=shelfCards("latest","Новые эпизоды",listOf(Row("release:1",Kind.POSTER))){ }
        assertEquals(2,short.size);assertEquals(Kind.MORE,short.last().kind)
    }
    @Test fun shelfEndUsesSameCompactSizeBesideWideCards(){
        val cards=shelfCards("genres","Жанры",listOf(Row("genre:1",Kind.FEATURE,"Детектив")),true){ }
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(cards)
        val parent=FrameLayout(RuntimeEnvironment.getApplication())
        val feature=adapter.onCreateViewHolder(parent,Kind.FEATURE.ordinal);adapter.onBindViewHolder(feature,0)
        val more=adapter.onCreateViewHolder(parent,Kind.MORE.ordinal);adapter.onBindViewHolder(more,1)
        assertEquals(more.itemView.dp(136),more.itemView.layoutParams.width)
        assertEquals(feature.itemView.layoutParams.height,more.itemView.layoutParams.height)
    }
    @Test fun everyShelfEndUsesSameSizeAndCentersIconAboveCaption(){
        val context=RuntimeEnvironment.getApplication()
        val cases=listOf(Triple(0,Kind.POSTER,270),Triple(1,Kind.FEATURE,204),Triple(2,Kind.GENRE,236),Triple(3,Kind.FRANCHISE,190),Triple(4,Kind.POSTER,206),Triple(5,Kind.CONTINUE,150))
        for((style,kind,height)in cases){
            val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
            adapter.submit(listOf(Row("end",Kind.MORE,"Очень длинное название раздела",selected=style,click={})))
            val holder=adapter.onCreateViewHolder(FrameLayout(context),Kind.MORE.ordinal)
            adapter.onBindViewHolder(holder,0)
            val box=holder.itemView as android.widget.LinearLayout
            assertEquals(box.dp((height*1.18f).toInt()),box.layoutParams.height)
            val width=when(kind){Kind.FEATURE->232;Kind.GENRE->172;Kind.FRANCHISE->312;Kind.CONTINUE->272;else->144}
            assertEquals(box.dp(136),box.layoutParams.width)
            assertEquals(android.view.Gravity.CENTER,box.gravity)
            assertEquals(android.widget.LinearLayout.VERTICAL,box.orientation)
            assertTrue(box.getChildAt(0)is android.widget.ImageView)
            assertEquals("Открыть всё",(box.getChildAt(1)as android.widget.TextView).text.toString())
            assertFalse(box.getChildAt(1).isFocusable)
        }
    }

    @Test fun shelfUpdatesKeepStableItemIdsWithoutFullListReset(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        val a=Row("a",Kind.MORE,"Все",click={})
        val b=Row("b",Kind.REFRESH,"Обновить",click={})
        adapter.submit(listOf(a,b))
        val id=adapter.getItemId(0)
        var fullReset=false
        adapter.registerAdapterDataObserver(object:RecyclerView.AdapterDataObserver(){override fun onChanged(){fullReset=true}})
        adapter.submit(listOf(b,a))
        assertEquals(id,adapter.getItemId(1))
        assertFalse(fullReset)
        assertTrue(adapter.hasStableIds())
    }

    @Test fun shelfActionVectorSurvivesRecyclingAndRefreshRebinding(){
        for(kind in listOf(Kind.MORE,Kind.REFRESH)){
            val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
            val row=Row("action",kind,"Обновить",selected=2,click={})
            adapter.submit(listOf(row))
            val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),kind.ordinal)
            adapter.onBindViewHolder(holder,0)
            val image=(holder.itemView as android.widget.LinearLayout).getChildAt(0)as android.widget.ImageView
            val icon=image.drawable
            assertNotNull(icon)
            adapter.onViewRecycled(holder)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame(icon,image.drawable)
            adapter.submit(listOf(row.copy(title="Обновление…",click=null)))
            adapter.onBindViewHolder(holder,0)
            assertNotNull(image.drawable)
        }
    }

    @Test fun genrePlacesCaptionInsideImageLikeWeb(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(listOf(Row("genre:1",Kind.GENRE,"Фэнтези","628 релизов")))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.GENRE.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        assertEquals(-1,card.getChildAt(0).layoutParams.height)
        val content=card.getChildAt(2)as android.widget.LinearLayout
        assertEquals(android.view.Gravity.BOTTOM,(content.layoutParams as FrameLayout.LayoutParams).gravity)
        assertEquals(android.view.Gravity.CENTER,(content.getChildAt(0)as android.widget.TextView).gravity)
        assertEquals(android.view.View.VISIBLE,card.getChildAt(1).visibility)
    }
    @Test fun franchiseUsesSeparateSideInformationPanel(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(listOf(Row("franchise:1",Kind.FRANCHISE,"Франшиза","2020–2026 · 3 части")))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.FRANCHISE.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        val image=card.getChildAt(0)
        val content=card.getChildAt(2)
        assertTrue(image.layoutParams.width>0)
        assertEquals(image.layoutParams.width,(content.layoutParams as FrameLayout.LayoutParams).marginStart)
        assertEquals(android.view.View.GONE,card.getChildAt(1).visibility)
    }

    @Test fun latestShowsWebInformationOverlayOnlyOnFocus(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        val release=parseRelease(org.json.JSONObject("""{"id":1,"name":{"main":"Название"},"year":2026,"is_ongoing":true}"""))
        adapter.submit(listOf(Row("latest:1",Kind.POSTER,release=release,selected=1)))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.POSTER.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        val height=card.layoutParams.height
        assertEquals(android.view.View.GONE,card.getChildAt(2).visibility)
        card.requestFocus()
        assertEquals(android.view.View.VISIBLE,card.getChildAt(2).visibility)
        assertNotNull((card.getChildAt(0)as android.widget.ImageView).colorFilter)
        assertEquals(height,card.layoutParams.height)
        assertEquals(android.view.View.VISIBLE,card.getChildAt(3).visibility)
        assertEquals(5,card.childCount)
        assertEquals(android.view.View.GONE,card.getChildAt(4).visibility)
    }

    @Test fun catalogueShowsTitleAndMetadataOnlyOnFocus(){
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{})
        val release=parseRelease(org.json.JSONObject("""{"id":1,"name":{"main":"Название"},"year":2026,"is_ongoing":true}"""))
        adapter.submit(listOf(Row("catalogue:1",Kind.POSTER,release=release)))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.POSTER.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        assertEquals(android.view.View.GONE,card.getChildAt(2).visibility)
        assertEquals(android.view.View.GONE,card.getChildAt(3).visibility)
        card.requestFocus()
        assertEquals(android.view.View.VISIBLE,card.getChildAt(3).visibility)
        assertEquals(5,card.childCount)
        val status=card.getChildAt(4)as android.widget.TextView
        assertEquals("Онгоинг",status.text.toString())
        assertEquals(android.view.View.VISIBLE,status.visibility)
        assertNull(status.background)
        assertEquals(false,parseRelease(org.json.JSONObject("""{"id":2,"is_ongoing":false}""")).ongoing)
        assertNull(parseRelease(org.json.JSONObject("""{"id":3}""")).ongoing)
        assertNotNull((card.getChildAt(0)as android.widget.ImageView).colorFilter)
    }

    @Test fun discoveryContainsFourCardsRefreshAndAll(){
        var refreshed=false;var opened=false
        val source=(1..9).map{Row("genre:$it",Kind.GENRE,"Жанр $it")}
        val cards=discoveryShelfCards("genres","Жанры",source,2,refresh={refreshed=true},openAll={opened=true})
        assertEquals(6,cards.size)
        assertEquals(listOf("genre:1","genre:2","genre:3","genre:4"),cards.take(4).map{it.key})
        assertEquals(Kind.REFRESH,cards[4].kind);assertEquals(Kind.MORE,cards[5].kind)
        cards[4].click!!.invoke();assertTrue(refreshed);assertFalse(opened)
        cards[5].click!!.invoke();assertTrue(opened)
    }
    @Test fun busyDiscoveryKeepsOldCardsAndFullSectionAvailable(){
        val source=listOf(Row("genre:7",Kind.GENRE,"Фэнтези"))
        val cards=discoveryShelfCards("genres","Жанры",source,2,busy=true,refresh={},openAll={})
        assertEquals("genre:7",cards.first().key)
        assertNull(cards[1].click);assertNotNull(cards[2].click)
    }
    @Test fun refreshChangesOnlyTheRequestedHomeCollection(){
        val old=listOf(Feature("old","Старое","",null))
        val home=HomeData(null,emptyList(),null,old,old,emptyList())
        val updated=replaceHomeFeatures(home,HomeSection.GENRES,(1..5).map{Feature("$it","Жанр $it","",null)})
        assertEquals(4,updated.genres!!.size)
        assertSame(home.franchises,updated.franchises)
        assertSame(home.latest,updated.latest)
        assertSame(home.videos,updated.videos)
        assertSame(old,home.genres)
    }

    @Test fun franchisePreviewHasThreeItemsWhileGenresHaveFour(){
        val items=(1..9).map{Row("part:$it",Kind.FRANCHISE,"Часть $it")}
        val cards=discoveryShelfCards("franchises","Франшизы",items,3,refresh={},openAll={})
        assertEquals(5,cards.size)
        assertEquals(3,cards.count{it.kind==Kind.FRANCHISE})
        assertEquals(Kind.REFRESH,cards[3].kind)
        assertEquals(Kind.MORE,cards[4].kind)
        val original=HomeData(null,emptyList(),null,emptyList(),emptyList())
        val replaced=replaceHomeFeatures(original,HomeSection.FRANCHISES,(1..9).map{Feature("$it","Часть $it","",null)})
        assertEquals(3,replaced.franchises!!.size)
    }

    @Test fun scheduleHeadingAndDayChoicesShareOneHorizontalRow(){
        var day=-1
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},day={day=it})
        adapter.submit(listOf(Row("schedule-days",Kind.DAYS,"Расписание релизов",selected=1)))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.DAYS.ordinal)
        adapter.onBindViewHolder(holder,0)
        val line=(holder.itemView as android.widget.HorizontalScrollView).getChildAt(0)as android.widget.LinearLayout
        assertEquals(android.widget.LinearLayout.HORIZONTAL,line.orientation)
        assertEquals(4,line.childCount)
        assertEquals("Расписание релизов",(line.getChildAt(0)as android.widget.TextView).text.toString())
        assertFalse(line.getChildAt(0).isFocusable)
        line.getChildAt(2).performClick();assertEquals(1,day)
    }

    @Test fun continueCardDisplaysEpisodeAndLaunchesItsExactUuid(){
        var uuid:String?=null
        val item=ContinueEpisode(42,"uuid-12.5","Тайтл",12.5,"Особая серия",null,300000,1200000,1)
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(listOf(Row("continue:42:uuid-12.5",Kind.CONTINUE,item.releaseTitle,item.episodeTitle,watching=item,click={uuid=item.episodeId})))
        val holder=adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()),Kind.CONTINUE.ordinal)
        adapter.onBindViewHolder(holder,0)
        val card=holder.itemView as FrameLayout
        assertEquals(card.dp((150*1.18f).toInt()),card.layoutParams.height)
        assertEquals(250,(card.getChildAt(3)as android.widget.ProgressBar).progress)
        val caption=card.getChildAt(2)as android.widget.LinearLayout
        val title=caption.getChildAt(0)as android.widget.TextView
        val information=caption.getChildAt(1)as android.widget.TextView
        assertEquals("Тайтл",title.text.toString())
        assertEquals(android.view.View.VISIBLE,title.visibility)
        assertEquals(android.view.View.GONE,information.visibility)
        val badge=card.getChildAt(4)as android.widget.TextView
        assertEquals("12.5 эпизод",badge.text.toString())
        assertEquals(android.view.View.GONE,badge.visibility)
        card.requestFocus()
        assertEquals(android.view.View.VISIBLE,title.visibility)
        assertEquals(android.view.View.VISIBLE,information.visibility)
        assertEquals(android.view.View.VISIBLE,badge.visibility)
        assertEquals(5,card.childCount)
        assertNotNull((card.getChildAt(0)as android.widget.ImageView).colorFilter)
        val root=FrameLayout(RuntimeEnvironment.getApplication())
        root.addView(card)
        val other=android.widget.TextView(root.context).apply{isFocusable=true;isFocusableInTouchMode=true}
        root.addView(other)
        other.requestFocus()
        assertEquals(android.view.View.VISIBLE,title.visibility)
        assertEquals(android.view.View.GONE,information.visibility)
        assertNull((card.getChildAt(0)as android.widget.ImageView).colorFilter)
        card.performClick();assertEquals("uuid-12.5",uuid)
    }

}
