package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.Platform
import app.antisocial.core.PlatformOrder
import com.google.gson.JsonParser
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformOrderPreferencesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val raw get()=context.getSharedPreferences("antisocial",Context.MODE_PRIVATE)
    private val preferences get()=Preferences(context)
    private lateinit var saved: String
    @Before fun preserve() {saved=preferences.export().toString();raw.edit().clear().commit()}
    @After fun restore() {
        raw.edit().clear().commit();preferences.restore(JSONObject(saved))
        assertEquals(JsonParser.parseString(saved),JsonParser.parseString(preferences.export().toString()))
    }
    @Test fun aNewInstallUsesPopularDefaultTabsWithoutWritingAnExplicitOrder() {
        assertEquals(listOf(Platform.FACEBOOK,Platform.INSTAGRAM,Platform.TIKTOK,Platform.X,Platform.REDDIT),preferences.selected)
        assertEquals(Platform.FACEBOOK,preferences.activePlatform)
        assertFalse(raw.contains("platforms"));assertFalse(raw.contains("active"))
    }
    @Test fun existingCustomOrderAndActiveTabSurviveBackupRestoration() {
        val custom=listOf(Platform.PIXELFED,Platform.REDDIT,Platform.X)
        preferences.selected=custom;preferences.activePlatform=Platform.REDDIT
        val backup=preferences.export()
        raw.edit().clear().commit();preferences.restore(backup)
        assertEquals(custom,preferences.selected);assertEquals(Platform.REDDIT,preferences.activePlatform)
    }
    @Test fun explicitPopularSortingKeepsSelectionAndTheActiveTab() {
        preferences.selected=listOf(Platform.PIXELFED,Platform.REDDIT,Platform.FACEBOOK)
        preferences.activePlatform=Platform.REDDIT
        preferences.selected=PlatformOrder.sort(preferences.selected)
        assertEquals(listOf(Platform.FACEBOOK,Platform.REDDIT,Platform.PIXELFED),Preferences(context).selected)
        assertEquals(Platform.REDDIT,Preferences(context).activePlatform)
    }
    @Test fun olderYouTubeTabChoicesBecomeSocialChoicesWithoutRewritingPrivateState() {
        raw.edit().putString("platforms","twitter,youtube,reddit").putString("active","youtube").commit()
        val before=preferences.export().toString()
        assertEquals(listOf(Platform.X,Platform.REDDIT),preferences.selected)
        assertEquals(Platform.X,preferences.activePlatform)
        assertEquals(before,preferences.export().toString())
        raw.edit().putString("platforms","youtube").commit()
        assertEquals(PlatformOrder.defaultSelected,preferences.selected)
        assertFalse(Platform.YOUTUBE in preferences.selected)
    }
}
