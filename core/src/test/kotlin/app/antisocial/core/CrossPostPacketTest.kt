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
        System.getenv("ANTISOCIAL_PRIVATE_CROSSPOST_REPORT")?.takeIf(String::isNotBlank)?.let {path->
            val accounts=follows.filter {it.enabled}.map {Subscriptions.id(it) to it}.groupBy {it.second.target.platform}
            val method=CrossPosts.javaClass.declaredMethods.single {it.name=="features"}.apply {isAccessible=true}
            val chosen=posts.filter {CrossPosts.originalKey(it) in selected}
            val report=mapOf("features" to chosen.map {method.invoke(CrossPosts,it,accounts,memberships)},
                "groups" to CrossPosts.group(posts,follows,memberships),
                "pairs" to chosen.flatMapIndexed {i,a->chosen.drop(i+1).map {b->mapOf("a" to a.identityKey,"b" to b.identityKey,
                    "copies" to CrossPosts.sameContentCopies(listOf(a,b)),"fusion" to (CrossPostFusion.compose(listOf(a,b))!=null),
                    "mediaFusion" to (CrossPostFusion.composeMedia(listOf(a,b))!=null))}})
            File(path).writeText(gson.toJson(report))
        }
        for(input in listOf(posts,posts.reversed())) {
            val groups=CrossPosts.group(input,follows,memberships)
            assertEquals(packet["expectedGroups"].asInt,groups.count {g->g.posts.any {CrossPosts.originalKey(it) in selected}})
            assertEquals(selected,groups.flatMap {it.posts}.map(CrossPosts::originalKey).toSet().intersect(selected))
            packet.getAsJsonArray("oncePassages")?.let {passages->
                val editions=groups.single {g->g.posts.any {CrossPosts.originalKey(it) in selected}}.posts
                val fusion=CrossPostFusion.composeCollectedEditions(editions) ?: CrossPostFusion.compose(editions) ?: CrossPostFusion.composeMedia(editions) ?: CrossPostFusion.composeRelated(editions)
                assertNotNull("The collected caption must be presentable",fusion)
                val text=TextWhitespace.collapse(fusion!!.segments.joinToString("\n"){it.text}).lowercase()
                passages.forEach {value->assertEquals("A shared passage must be read once",1,Regex(Regex.escape(TextWhitespace.collapse(value.asString).lowercase())).findAll(text).count())}
            }
        }
        assertEquals(before,posts.map(gson::toJson))
    }
}
