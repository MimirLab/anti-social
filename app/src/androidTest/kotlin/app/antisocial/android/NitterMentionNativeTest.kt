/* anti social, GPL-3.0-or-later. Native ICU and original-link controls. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class NitterMentionNativeTest {
    private val source=Source(Platform.X,"nitter","https://reader.fixture.invalid")
    private val classify=CrossPosts::class.java.declaredMethods.single {it.name.substringBefore('$')=="nitterMentionLink"}
    private val prepared=CrossPostFusion::class.java.declaredMethods.single {it.name.substringBefore('$')=="withPrepared"}
    private fun actual(body:String,handle:String):Boolean {
        val post=Post("70001",Platform.X,"https://x.com/publicstudio/status/70001","publicstudio",body,
            provenance=Provenance(source,1,source.root+"/publicstudio/status/70001"))
        return classify.invoke(CrossPosts,post,source.root+"/"+handle) as Boolean
    }
    @Test fun nativeMentionGrammarMatchesThePreviousLiteralExpression() {
      prepared.invoke(CrossPostFusion,{
        val handles=listOf("i","I","s","k","ss","ffi","ff","fi","fl","Studio_1","ABCDEFGHIJKLMNO","a_9")
        val edges=listOf(""," ","\n",".","-","/","@","_","a","9","é","漢","𝟠","𐐀","\u0301","\u200b","\ud800","\udfff")
        val old=handles.associateWith {Regex("(?<![\\p{L}\\p{N}_])@${Regex.escape(it)}(?![A-Za-z0-9_])",RegexOption.IGNORE_CASE)}
        fun check(body:String,handle:String)=assertEquals(old.getValue(handle).containsMatchIn(body),actual(body,handle))
        for(handle in handles)for(before in edges)for(after in edges)check(before+"@"+handle+after,handle)
        val random=Random(180_301)
        for(handle in handles)check("@ß @ẞ @ﬃ @ﬀ @ﬁ @ﬂ",handle)
        val atoms=edges+listOf("@i","@İ","@ı","@ſ","@s","@k","@K","@ß","@ẞ","@ﬃ","@ﬀ","@ﬁ","@ﬂ","@studio_1","@STUDIO_1","@ABCDEFGHIJKLMNO","@ABCDEFGHIJKLMNOP")
        repeat(1000){val body=buildString {repeat(1+random.nextInt(30)){append(atoms[random.nextInt(atoms.size)])}};handles.forEach {check(body,it)}}
      })
    }
    @Test fun nativeOriginalLinksRetainRssIdentityAndRejectInvalidHandles() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        kotlinx.coroutines.runBlocking {repo.awaitReady()}
        for(handle in listOf("A","publicstudio","ABCDEFGHIJKLMNO","a_9")) {
            assertEquals("https://x.com/"+handle,repo.publicUrl("https://nitter.net/"+handle+"/rss"))
            assertEquals("https://x.com/"+handle,repo.publicUrl("https://nitter.net/"+handle+"/rss/"))
            assertEquals("https://x.com/"+handle+"/status/70001?Case=A%2FB#reply",repo.publicUrl("https://nitter.net/"+handle+"/status/70001?Case=A%2FB#reply"))
        }
        for(handle in listOf("ABCDEFGHIJKLMNOP","a-b","Studío")) {
            val url="https://nitter.net/"+handle+"/rss"
            assertEquals(url,repo.publicUrl(url))
        }
    }
}
