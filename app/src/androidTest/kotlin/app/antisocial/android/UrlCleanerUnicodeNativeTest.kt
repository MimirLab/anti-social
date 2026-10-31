package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.UrlCleaner
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual Android ICU and ordered rule evaluation; no network requests. */
class UrlCleanerUnicodeNativeTest {
    private fun legacyCleaner(data:JsonObject):UrlCleaner=UrlCleaner::class.java.getDeclaredConstructor(JsonObject::class.java,
        Boolean::class.javaPrimitiveType).newInstance(data,false)
    @Test fun unicodeFoldAndRedirectsKeepTheirRealAndroidCleaningResults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val data=JsonParser.parseString("""{"providers":{
            "source":{"urlPattern":"^https://source[.]example/","redirections":["target=([^&]+)"]},
            "target":{"urlPattern":"ss[.]example","rules":["tracking"]},
            "ligature":{"urlPattern":"ffi[.]example","rules":["tracking"]},
            "kelvin":{"urlPattern":"kexample","rules":["tracking"]}
        }}""").asJsonObject
        val cleaner=UrlCleaner(data);val legacy=legacyCleaner(data);cleaner.prepare();legacy.prepare()
        val cases=mapOf("https://ß.example/?tracking=one" to "https://ß.example/",
            "https://ﬃ.example/?tracking=one" to "https://ﬃ.example/",
            "https://Kexample.org/?tracking=one" to "https://Kexample.org/",
            "https://source.example/?target=https%3A%2F%2Fss.example%2Fpath%3Ftracking%3Done%26keep%3Dtwo" to "https://ss.example/path?keep=two",
            "https://source.example/?target=https%3A%2F%2F%C3%9F.example%2Fpath%3Ftracking%3Done" to "https://ß.example/path",
            "https://ss.example/?token=stay&tracking=one" to "https://ss.example/?token=stay&tracking=one")
        cases.forEach {(value,expected)->assertEquals(value,expected,legacy.clean(value));assertEquals(value,expected,cleaner.clean(value))}
    }
    @Test fun theCompleteBundledCleanerKeepsItsLegacyResultsOnAndroid() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val data=context.assets.open("clean-links.json").reader().use {JsonParser.parseReader(it).asJsonObject}
        val cleaner=UrlCleaner(data);val legacy=legacyCleaner(data);cleaner.prepare();legacy.prepare()
        val hosts=listOf("www.amazon.com","www.google.com","l.facebook.com","www.bing.com","x.com","www.reddit.com","example.org")
        val queries=listOf("utm_source=test&keep=1","token=stay&utm_source=keep","sig=stay&fbclid=keep","q=coffee&ved=1&source=hp","foo=😀&utm_source=test","url=https%3A%2F%2Fexample.org%2Fpage%3Futm_source%3Dtest")
        for(host in hosts)for(query in queries)for(path in listOf("article","url","l.php")) {
            val value="https://$host/$path?$query#section"
            assertEquals(value,legacy.clean(value),cleaner.clean(value))
        }
    }
}
