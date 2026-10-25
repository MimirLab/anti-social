/* anti social, GPL-3.0-or-later. Literal selected public comment connection. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object InstagramPublicComments {
    data class Window(val posts:List<Post>,val accounts:List<Profile>,val more:Boolean,val after:String?)
    private val number=Regex("[0-9]{1,40}")
    private val handle=Regex("[A-Za-z0-9_.]{1,30}")
    private fun JsonObject.s(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun JsonObject.o(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.id(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.asString?.takeIf {number.matches(it)}
    private fun JsonObject.n(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString.toLongOrNull()}.getOrNull()}
    private fun safe(value:String)=value.takeIf {it.length<=8192}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    private fun fail(message:String):Nothing=throw SourceFailure(HealthStatus.EMPTY,message)
    fun parse(data:JsonObject,target:Target,provenance:Provenance):Window {
        val selected=InstagramPublicGraphQl.identity(target)?.takeUnless {it.profile} ?: fail("Select a post for its public comments")
        val connection=data.o("xdt_api__v1__media__media_id__comments__connection") ?: fail("The public source returned no selected comment connection")
        val edges=connection.get("edges")?.takeIf {it.isJsonArray}?.asJsonArray ?: fail("The public source omitted its comments")
        if(edges.size()>64)fail("The public comment source exceeded its window")
        val root="post:${selected.value}";val accounts=mutableListOf<Profile>()
        val posts=edges.mapNotNull {edge->
            val node=edge.takeIf {it.isJsonObject}?.asJsonObject?.o("node") ?: return@mapNotNull null
            if(node.s("__typename")!="XDTCommentDict")return@mapNotNull null
            val id=node.id("pk") ?: return@mapNotNull null
            if(node.get("media_id")?.isJsonNull==false&&node.id("media_id")!=InstagramPublicGraphQl.mediaId(selected.value))fail("The public comments belong to another post")
            val text=node.s("text").take(100_000).takeIf {it.isNotBlank()} ?: return@mapNotNull null
            val user=node.o("user");val username=user?.s("username")?.takeIf {handle.matches(it)}
            val fullName=user?.s("full_name")?.take(400).orEmpty()
            val avatar=user?.s("profile_pic_url")?.let(::safe)
            if(username!=null)accounts+=Profile(Target(Platform.INSTAGRAM,TargetKind.PROFILE,username,"https://www.instagram.com/$username/"),
                fullName.ifBlank {username},avatar=avatar,displayNameSupplied=fullName.isNotBlank(),persistentId=user?.id("pk"))
            val parent=node.id("parent_comment_id")
            val top=node.get("parent_comment_id")?.isJsonNull==true
            val url="https://www.instagram.com/p/${selected.value}/c/$id/"
            Post("comment:${selected.value}:$id",Platform.INSTAGRAM,url,fullName.ifBlank {username ?: "Unavailable account"},text,
                publishedAt=node.n("created_at")?.takeIf {it in 1..253402300799L}?.times(1000),avatar=avatar,
                parentId=parent?.let {"comment:${selected.value}:$it"} ?: root.takeIf {top},conversationId=root,sourceParentId=parent,
                authorAccount=username?.let {PostAuthorAccounts.instagram(it,user?.id("pk"))},
                replyCount=node.n("child_comment_count")?.coerceAtLeast(0),score=node.n("comment_like_count")?.coerceAtLeast(0),provenance=provenance,
                extractionCoverage=ExtractionCoverage(text=ExtractionStatus.PRESENT,replyParent=if(parent!=null||top)ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,
                    note=if((node.n("child_comment_count") ?: 0)>0)"Replies may require another public source."else null))
        }.distinctBy {it.identityKey}
        val page=connection.o("page_info") ?: fail("The public comment source omitted pagination state")
        val more=page.get("has_next_page")?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean ?: fail("The public comment source omitted pagination state")
        val after=page.s("end_cursor").takeIf {it.length in 1..8192&&it.all {c->c.code in 32..126}}
        return Window(posts,accounts.distinctBy {it.target},more,after)
    }
}
