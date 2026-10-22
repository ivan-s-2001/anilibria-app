package tv.aniliberty.next

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class CatalogPanelTest{
    @Test fun resetIsOnlyADraftAndCancelDoesNotApply(){
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity=controller.get()
        var applied:CatalogFilter?=null
        val original=CatalogFilter(genres=setOf("15"),sorting="RATING_DESC")
        val panel=CatalogFilterPanel(activity,Screen.Catalog(filters=original),mapOf("genres" to listOf(CatalogChoice("15","Фэнтези")))){applied=it}
        panel.show()
        val content=panel.findViewById<android.widget.FrameLayout>(android.R.id.content)
        val shell=content.getChildAt(0)as LinearLayout
        (shell.getChildAt(3)as TextView).performClick()
        assertNull(applied)
        panel.cancel()
        assertNull(applied)
        assertEquals(setOf("15"),original.genres)
        controller.pause().stop().destroy()
    }
    @Test fun applyingAfterResetPreservesSortAndClearsGenreDeepLink(){
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var applied:CatalogFilter?=null
        val panel=CatalogFilterPanel(controller.get(),Screen.Catalog(genre=15,filters=CatalogFilter(sorting="YEAR_DESC")),emptyMap()){applied=it}
        panel.show()
        val shell=panel.findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0)as LinearLayout
        val body=(shell.getChildAt(1)as ScrollView).getChildAt(0)as LinearLayout
        assertTrue((body.getChildAt(0)as TextView).isFocusable)
        (shell.getChildAt(3)as TextView).performClick()
        (shell.getChildAt(2)as TextView).performClick()
        assertNotNull(applied)
        assertTrue(applied!!.genres.isEmpty())
        assertEquals("YEAR_DESC",applied!!.sorting)
        assertFalse(panel.isShowing)
        controller.pause().stop().destroy()
    }
}
