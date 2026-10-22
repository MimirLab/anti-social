package app.antisocial.android

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit retained public cache audit. No model or network requests. */
@RunWith(AndroidJUnit4::class)
class CachedDedupAuditTest {
    @Test fun inspectRetainedCaptionAndGalleryGroups() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialCachedDedupAudit")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=(context as AntiSocialApplication).repo
        val dir=File(context.noBackupFilesDir,"dedup-fixture")
        val posts=repo.gson.fromJson(File(dir,"posts.json").readText(),Array<Post>::class.java).toList()
        val follows=repo.gson.fromJson(File(dir,"follows.json").readText(),Array<Follow>::class.java).toList()
        val memberships=JsonParser.parseString(File(dir,"memberships.json").readText()).asJsonObject.entrySet().associate {(key,value)->key to value.asJsonArray.map {it.asString}.toSet()}
        val bank=MediaIdentityStore();var decoded=0
        val seen=mutableSetOf<String>()
        for(item in JsonParser.parseString(File(dir,"images.json").readText()).asJsonArray) {
            val row=item.asJsonObject;val url=row["url"].asString
            if(!seen.add(url))continue
            val file=File(dir,row["file"].asString)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2
            val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: continue
            try {bank.observe(url,bitmap);decoded++}finally {bitmap.recycle()}
        }
        val captured=posts.map(bank::captured)
        val groups=CrossPosts.group(captured,follows,memberships,bank::same)
        val owner=groups.flatMapIndexed {index,group->group.posts.map {CrossPosts.originalKey(it) to index}}.toMap()
        val pairs=buildList {
            for(i in posts.indices)for(j in i+1 until posts.size) {
                val a=posts[i];val b=posts[j]
                fun text(p:Post):String {var value=p.body;repeat(2){value=org.jsoup.parser.Parser.unescapeEntities(value,false)};return value.replace(Regex("[\\s\\p{Z}]+")," ").trim().lowercase()}
                if(a.platform==b.platform||a.body.isBlank()||text(a)!=text(b))continue
                add(mapOf("a" to i,"b" to j,"grouped" to (owner[CrossPosts.originalKey(a)]==owner[CrossPosts.originalKey(b)]),
                    "sameMedia" to bank.same(a,b),"caption" to a.body))
            }
        }
        File(context.filesDir,"cached-dedup-audit.json").writeText(repo.gson.toJson(mapOf("posts" to captured,"groups" to groups,"pairs" to pairs,"decoded" to decoded)))
        assertEquals(posts.size,groups.sumOf {it.posts.size})
        assertTrue("Retained identical cross-platform captions must share a card: ${pairs.filter {it["grouped"]!=true}}",pairs.all {it["grouped"]==true})
        val gallery=groups.single {group->group.posts.any {it.canonicalUrl.endsWith("/DevQziOkYZa")}}
        assertEquals("The gallery must retain all three separately posted pictures and its complete edition",4,gallery.posts.size)
        val reshare=groups.single {group->group.posts.any {it.canonicalUrl.endsWith("/2112307287398158518")}}
        assertEquals("A flags-only reshare should accompany the complete cross-post",3,reshare.posts.size)
        groups.filter {it.posts.size>1}.forEach {group->
            assertNotNull("Collected captions must render with their complete media: ${group.posts.map {it.canonicalUrl}}",collectedPostFusion(group.posts))
            repo.library.normalizedFusionRoots(group.primary,group.posts)
        }
    }
}
