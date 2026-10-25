package app.antisocial.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/** Offline usability audit of explicitly captured public pages, not a live probe. */
fun main() {
    val loader=object {}.javaClass
    fun asset(name:String)=JsonParser.parseReader(loader.getResourceAsStream("/$name")!!.reader()).asJsonObject
    val cleaner=UrlCleaner(asset("clean-links.json"));val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    val root=File(System.getProperty("antisocial.previewEvidence"))
    val urls=listOf("https://www.instagram.com/nasa/","https://www.facebook.com/NASA/","https://www.tiktok.com/@nasa","https://x.com/NASA")
    val samples=urls.flatMapIndexed {index,url->
        val original=File(root,"response-$index.txt")
        if(original.exists())listOf(url to original)else listOf("standard","desktop","mobile").map {url to File(root,"${routes.recognize(url)!!.platform.id}-$it.html")}
    }
    val rows=samples.map { (url,file)->
        val target=requireNotNull(routes.recognize(url))
        require(file.length() in 1..4L*1024*1024){"A bounded captured public page is required"}
        val source=Source(target.platform,"original",java.net.URI(url).let {"https://${it.host}"},role=SourceRole.ORIGINAL)
        val row=linkedMapOf<String,Any?>("url" to url,"sample" to file.name,"bytes" to file.length())
        val payload=Payload(file.readText(),url,"text/html",200)
        if(target.platform==Platform.FACEBOOK)try {
            val owner=FacebookProtocol.numericProfileId(target) ?: FacebookProtocol.profileId(payload,target)
            val page=FacebookProtocol.timeline(payload,target,source,owner)
            row["facebookOwner"]=owner;row["facebookPosts"]=page.posts.size
            row["facebookExamples"]=page.posts.take(2).map {mapOf("url" to it.canonicalUrl,"body" to it.body.take(200),"publishedAt" to it.publishedAt,"media" to it.media.size)}
        }catch(e:Exception){row["facebookError"]=e.message}
        try {
            val page=if(target.platform==Platform.FACEBOOK)PublicPreviewPages.parse(payload,target,source,routes)else Extraction.parse(payload,target,source,routes)
            row["posts"]=page.posts.size;row["dated"]=page.posts.count {it.publishedAt!=null};row["profile"]=page.profile?.name
            row["previewPosts"]=page.posts.count {it.preview};row["examples"]=page.posts.take(2).map {mapOf("url" to it.canonicalUrl,"body" to it.body.take(160))}
        }catch(e:Exception){row["failure"]=e.javaClass.simpleName;row["message"]=e.message}
        row
    }
    val json=GsonBuilder().setPrettyPrinting().create().toJson(rows)
    File(root,"usability.json").writeText(json);println(json)
}
