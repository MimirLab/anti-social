/* anti social, GPL-3.0-or-later. Optional private captured-input regression. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CrossPostPacketTest {
    @Test fun suppliedCapturedEditionsFormTheExpectedCollectionWithoutLosingOriginals() {
        val path=System.getenv("ANTISOCIAL_PRIVATE_CROSSPOST_PACKET").orEmpty()
        assumeTrue("An explicit private capture is required",path.isNotBlank())
        val file=File(path);require(file.length() in 1..4*1024*1024)
        val packet=JsonParser.parseReader(file.reader()).asJsonObject;val gson=Gson()
        val posts=packet.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}
        val follows=packet.getAsJsonArray("follows").map {gson.fromJson(it,Follow::class.java)}
        val memberships=packet.getAsJsonObject("memberships").entrySet().associate {(key,value)->key to value.asJsonArray.map {it.asString}.toSet()}
        val before=posts.map(gson::toJson)
        val selected=packet.getAsJsonArray("selectedOriginals")?.map {it.asString}?.toSet() ?: posts.map(CrossPosts::originalKey).toSet()
        for(input in listOf(posts,posts.reversed())) {
            val groups=CrossPosts.group(input,follows,memberships)
            assertEquals(packet["expectedGroups"].asInt,groups.count {g->g.posts.any {CrossPosts.originalKey(it) in selected}})
            assertEquals(selected,groups.flatMap {it.posts}.map(CrossPosts::originalKey).toSet().intersect(selected))
            packet.getAsJsonArray("oncePassages")?.let {passages->
                val editions=groups.single {g->g.posts.any {CrossPosts.originalKey(it) in selected}}.posts
                val fusion=CrossPostFusion.compose(editions) ?: CrossPostFusion.composeMedia(editions) ?: CrossPostFusion.composeRelated(editions)
                assertNotNull("The collected caption must be presentable",fusion)
                val text=TextWhitespace.collapse(fusion!!.segments.joinToString("\n"){it.text}).lowercase()
                passages.forEach {value->assertEquals("A shared passage must be read once",1,Regex(Regex.escape(TextWhitespace.collapse(value.asString).lowercase())).findAll(text).count())}
            }
        }
        assertEquals(before,posts.map(gson::toJson))
    }
}
