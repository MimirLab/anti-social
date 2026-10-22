/* anti social, GPL-3.0-or-later. Rebrand boundaries exercised on native Android. */
package app.antisocial.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.NativeBridge
import app.antisocial.core.Platform
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrandIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedIdentityAndAllOwnedComponentsUseTheReleaseNamespace() {
        val manager=context.packageManager
        assertEquals("app.antisocial.android",context.packageName)
        val info=manager.getPackageInfo(context.packageName,PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals("anti social",manager.getApplicationLabel(info.applicationInfo!!).toString())
        assertEquals(AntiSocialApplication::class.java.name,info.applicationInfo!!.className)
        val components=info.activities.orEmpty().map {it.name}+info.services.orEmpty().map {it.name}+info.providers.orEmpty().map {it.name}
        assertTrue(components.filter {it.startsWith("app.")}.all {it.startsWith(context.packageName+".")})
        assertNotNull(manager.getLaunchIntentForPackage(context.packageName))
        assertNotNull(manager.getApplicationIcon(context.packageName))
        for(platform in Platform.entries.filterNot {it==Platform.RSS}) {
            val alias=manager.getActivityInfo(LinkOpening.component(context,platform),PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(MainActivity::class.java.name,alias.targetActivity)
        }
    }

    @Test fun selectedSocialLinksStillResolveToTheNativeReader() {
        val repo=(context.applicationContext as AntiSocialApplication).repo
        val selected=repo.prefs.selected
        try {
            LinkOpening.sync(context,listOf(Platform.X,Platform.FACEBOOK))
            for(url in listOf("https://x.com/example/status/1234567890","https://www.facebook.com/example/posts/1234567890","https://youtu.be/abcdefghijk")) {
                val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
                val matches=context.packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY)
                assertTrue("No native reader handler for $url",matches.any {it.activityInfo.targetActivity==MainActivity::class.java.name})
            }
        }finally {LinkOpening.sync(context,selected)}
    }

    @Test fun renamedNativeBridgesLoadAndInvokeWithoutAModelDownload() {
        val filter=NativeBridge.create("||tracker.example^")
        try {assertTrue(NativeBridge.check(filter,"https://tracker.example/pixel","https://reader.example","image").contains("true"))}
        finally {NativeBridge.release(filter)}
        // Merely opening the reader must not unpack or load optional text code.
        assertNotNull(LocalTextRuntime.pack(context))
        System.loadLibrary("antisocial_embedding")
        val id="brand-native-linkage"
        ContextMediaNative.start(id);ContextMediaNative.cancel(id);ContextMediaNative.finish(id)
        val detector=File(context.cacheDir,"brand-language-detector.ftz")
        try {
            context.assets.open("translation/lid.176.ftz").use {input->detector.outputStream().use {input.copyTo(it)}}
            val result=LanguageNative.detect(detector.path.toByteArray(),"This is an English sentence about reading public information.".toByteArray())
            assertEquals("en",JsonParser.parseString(result.toString(Charsets.UTF_8)).asJsonArray[0].asJsonObject["language"].asString)
        }finally {LanguageNative.release();detector.delete()}
    }
}
