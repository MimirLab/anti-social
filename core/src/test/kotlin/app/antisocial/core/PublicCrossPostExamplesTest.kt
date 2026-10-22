/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Originally authored structural counterparts to independently observed public
 * cases. The fixture records research URLs while every test caption and identity
 * is synthetic; third-party social captions are not redistributed in the source.
 */
class PublicCrossPostExamplesTest {
    private val fixture=JsonParser.parseReader(javaClass.getResourceAsStream("/crosspost-public-examples.json")!!.reader()).asJsonObject
    private fun example(id: String)=fixture.getAsJsonArray("cases").map {it.asJsonObject}.single {it["id"].asString==id}
    private fun posts(example: JsonObject)=example.getAsJsonArray("posts").map {value->
        val post=value.asJsonObject
        Post(post["id"].asString,Platform.valueOf(post["platform"].asString),post["canonicalUrl"].asString,
            post["author"].asString,post["body"].asString,post["title"].asString,post["publishedAt"].asLong,
            links=post.getAsJsonArray("links").map {it.asString})
    }
    private fun accounts()=fixture.getAsJsonObject("syntheticAccounts").getAsJsonObject("profiles").entrySet().map {(platform,url)->
        val p=Platform.valueOf(platform)
        Follow(p.id,Target(p,TargetKind.PROFILE,if(p==Platform.BLUESKY)"merger-fixture.example"else"merger_fixture",url.asString),"Repair fixture",personId="synthetic-workshop-explicit-local-links")
    }
    private fun occurrences(text: String,passage: String)=Regex(Regex.escape(passage)).findAll(text).count()

    @Test fun observedInlineAttributionPatternDoesNotRepeatEitherSyntheticQuotation() {
        val example=example("interior-attribution");val posts=posts(example);val before=posts.map {Gson().toJson(it)}
        for(editions in listOf(posts,posts.reversed())) {
            val fusion=CrossPostFusion.compose(editions)
            assertNotNull("The two substantial quotations surround different source attributions",fusion)
            val rendered=fusion!!.segments.joinToString("\n"){it.text}
            example.getAsJsonArray("sharedPassages").forEach {value->
                assertEquals("Shared quotation should be read once",1,occurrences(rendered,value.asString))
                assertTrue(fusion.segments.any {it.shared&&it.text.contains(value.asString)&&it.platforms==posts.map {post->post.platform}.toSet()})
            }
            example.getAsJsonObject("uniquePassages").entrySet().forEach {(platform,values)->
                values.asJsonArray.forEach {value->
                    assertTrue("Each original attribution remains attached to its source",
                        fusion.segments.any {!it.shared&&Platform.valueOf(platform) in it.platforms&&it.text.contains(value.asString)})
                }
            }
        }
        assertEquals(before,posts.map {Gson().toJson(it)})
    }

    @Test fun observedShortenedLabelPatternYieldsOneSyntheticCaptionAndTheFullDestination() {
        val example=example("shortened-link-label");val posts=posts(example);val before=posts.map {Gson().toJson(it)}
        assertEquals(1,CrossPosts.group(posts,accounts()).size)
        val fusion=CrossPostFusion.compose(posts)
        assertNotNull("Display labels differ, but both sources explicitly name the same full destination",fusion)
        val rendered=fusion!!.segments.joinToString("\n"){it.text}
        example.getAsJsonArray("sharedPassages").forEach {value->assertEquals(1,occurrences(rendered,value.asString))}
        assertEquals(1,occurrences(rendered,example["preferredDestination"].asString))
        assertFalse(rendered.contains("example.org/workshops/battery..."))
        assertEquals(before,posts.map {Gson().toJson(it)})
    }

    @Test fun observedSameArticlePatternDoesNotTurnDifferentSyntheticUpdatesIntoOnePost() {
        val posts=posts(example("same-article-distinct-captions"))
        assertEquals(posts[0].links,posts[1].links)
        assertEquals(2,CrossPosts.group(posts,accounts()).size)
        assertFalse(CrossPostFusion.matches(posts[0],posts[1]))
        assertNull(CrossPostFusion.compose(posts))
    }
}
