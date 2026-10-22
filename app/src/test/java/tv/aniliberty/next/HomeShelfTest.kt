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
    @Test fun wideFeatureShelfHasMatchingArrowGeometry(){
        val cards=shelfCards("genres","Жанры",listOf(Row("genre:1",Kind.FEATURE,"Детектив")),true){ }
        val adapter=PageAdapter({},{},{},{},{},{0L to 0L},{null},{},shelfMode=true)
        adapter.submit(cards)
        val parent=FrameLayout(RuntimeEnvironment.getApplication())
        val feature=adapter.onCreateViewHolder(parent,Kind.FEATURE.ordinal);adapter.onBindViewHolder(feature,0)
        val more=adapter.onCreateViewHolder(parent,Kind.MORE.ordinal);adapter.onBindViewHolder(more,1)
        assertEquals(feature.itemView.layoutParams.width,more.itemView.layoutParams.width)
        assertEquals(feature.itemView.layoutParams.height,more.itemView.layoutParams.height)
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
        val release=parseRelease(org.json.JSONObject("""{"id":1,"name":{"main":"Название"},"year":2026}"""))
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
        assertEquals(card.dp((150*1.18).toInt()),card.layoutParams.height)
        assertEquals(250,(card.getChildAt(3)as android.widget.ProgressBar).progress)
        val caption=card.getChildAt(2)as android.widget.LinearLayout
        val title=caption.getChildAt(0)as android.widget.TextView
        val information=caption.getChildAt(1)as android.widget.TextView
        assertEquals("Тайтл",title.text.toString())
        assertEquals(android.view.View.VISIBLE,title.visibility)
        assertEquals(android.view.View.GONE,information.visibility)
        assertTrue(information.text.contains("12.5"))
        card.requestFocus()
        assertEquals(android.view.View.VISIBLE,title.visibility)
        assertEquals(android.view.View.VISIBLE,information.visibility)
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
