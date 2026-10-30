/* anti social, GPL-3.0-or-later. Preserve source-link classification. */
package app.antisocial.core

import java.net.URI
import java.util.Random
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class NitterMentionLinksTest {
    private val source=Source(Platform.X,"nitter","https://reader.fixture.invalid")
    private fun post(body:String)=Post("70001",Platform.X,"https://x.com/publicstudio/status/70001","publicstudio",body,
        provenance=Provenance(source,1,source.root+"/publicstudio/status/70001"))
    // Frozen 0.38.70 classifier; source gates are tested independently below.
    private fun previous(body:String,handle:String)=Regex("(?<![\\p{L}\\p{N}_])@${Regex.escape(handle)}(?![A-Za-z0-9_])",RegexOption.IGNORE_CASE).containsMatchIn(body)
    private fun check(body:String,handle:String) {
        assertEquals("handle=$handle body=$body",previous(body,handle),CrossPosts.nitterMentionLink(post(body),source.root+"/"+handle))
    }
    @Test fun literalExpressionsKeepHandleBoundariesLengthsAndUnicodeCaseFolding() = CrossPostFusion.withPrepared {
        val handles=listOf("i","I","s","k","ss","ffi","ff","fi","fl","Studio_1","ABCDEFGHIJKLMNO","a_9")
        val edges=listOf(""," ","\n","\t",".","-","/","@","_","a","9","é","漢","𝟠","𐐀","\u0301","\u200b","\ud800","\udfff")
        for(handle in handles)for(before in edges)for(after in edges)check(before+"@"+handle+after,handle)
        for(handle in handles)for(body in listOf("@İ @ı @ſ @K","@ß @ẞ @ﬃ @ﬀ @ﬁ @ﬂ","@studio_1 @STUDIO_1","@ABCDEFGHIJKLMNOx @ABCDEFGHIJKLMNO", "@other@"+handle,"a@"+handle+" @"+handle, "@"+handle+"_ @other"))check(body,handle)
        val random=Random(180_301)
        val atoms=edges+listOf("@i","@İ","@ı","@ſ","@s","@k","@K","@ß","@ẞ","@ﬃ","@ﬀ","@ﬁ","@ﬂ","@studio_1","@STUDIO_1","@ABCDEFGHIJKLMNO","@ABCDEFGHIJKLMNOP")
        repeat(3000) {
            val body=buildString {repeat(1+random.nextInt(30)){append(atoms[random.nextInt(atoms.size)])}}
            handles.forEach {check(body,it)}
        }
    }
    @Test fun sourceAndLiteralDestinationGatesRemainIndependentOfMentionSpelling() {
        val original=post("Thanks @Studio_1 for the photograph.")
        val link=source.root+"/Studio_1"
        assertTrue(CrossPosts.nitterMentionLink(original,link))
        for(value in listOf(link+"?q=x",link+"#reply","https://other.fixture.invalid/Studio_1",source.root+"/Studio_1/status/9",source.root+"/ABCDEFGHIJKLMNOP",source.root+"/Studío"))assertFalse(CrossPosts.nitterMentionLink(original,value))
        assertFalse(CrossPosts.nitterMentionLink(original.copy(platform=Platform.INSTAGRAM),link))
        assertFalse(CrossPosts.nitterMentionLink(original.copy(provenance=null),link))
        assertFalse(CrossPosts.nitterMentionLink(original.copy(provenance=original.provenance!!.copy(source=source.copy(frontend="rsshub"))),link))
        assertFalse(CrossPosts.nitterMentionLink(original.copy(body=original.body+" Read "+link),link))
        assertTrue(CrossPosts.nitterMentionLink(original,source.root+"/studio_1/"))
        assertEquals("/Studio_1",URI(link).path)
        // The existing classifier accepts root profile paths only; a reader
        // mount prefix is not silently stripped by this optimization.
        assertFalse(CrossPosts.nitterMentionLink(original.copy(provenance=original.provenance!!.copy(source=source.copy(root=source.root+"/social"))),source.root+"/social/Studio_1"))
    }
    @Test fun simultaneousReadersNeverShareMutableMatcherState() {
        val executor=Executors.newFixedThreadPool(6)
        try {
            val jobs=(0 until 24).map {id->executor.submit {CrossPostFusion.withPrepared {
                repeat(200){n->val handle=if(id%2==0)"Studio_1"else "i";check("thread$id @other @"+handle+if(n%2==0)"x"else ".",handle)}
            }}}
            jobs.forEach {it.get()}
        }finally {executor.shutdownNow()}
    }
}
