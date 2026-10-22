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

}
