/* anti social, GPL-3.0-or-later. Literal public Polaris media; no script execution. */
package app.antisocial.core

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object InstagramPublicMedia {
    private val types=setOf("XIGPolarisImageMedia","XIGPolarisVideoMedia","XIGPolarisSidecarMedia")
    private fun JsonObject.text(name:String)=get(name)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun JsonObject.obj(name:String)=get(name)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun https(value:String)=value.takeIf {it.length<=8192}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    fun parse(node:JsonObject,target:Target,provenance:Provenance):Post? {
        val type=node.text("__typename")
        if(type !in types||node.text("__isXIGPolarisMedia")!=type)return null
        val code=node.text("code").takeIf {it.matches(Regex("[A-Za-z0-9_-]{5,64}"))} ?: return null
        val user=node.obj("user") ?: return null
        val handle=user.text("username").takeIf {it.matches(Regex("[A-Za-z0-9_.]{1,30}"))} ?: return null
        val selected=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank) ?: return null
        val matches=when(target.kind) {
            TargetKind.PROFILE->selected.size==1&&selected[0].equals(handle,true)
            TargetKind.POST,TargetKind.THREAD->selected.size==2&&selected[0] in setOf("p","reel","reels","tv")&&selected[1]==code
            else->false
        }
        if(!matches)return null
        val caption=node.obj("caption")?.text("text").orEmpty().take(100_000)
        val image=https(node.text("display_uri"))
        // display_uri is the poster for a video. It is never a playable stream.
        val media=image?.let {listOf(Media(it,description=if(type=="XIGPolarisVideoMedia")"Video preview; full media unavailable"else node.text("accessibility_caption").take(4000)))}.orEmpty()
        if(caption.isBlank()&&media.isEmpty())return null
        val url="https://www.instagram.com/p/$code"
        return Post(ContentIdentity.key(Platform.INSTAGRAM,url),Platform.INSTAGRAM,url,handle,caption,
            media=media,provenance=provenance,preview=true,extractionCoverage=ExtractionCoverage(
                text=if(caption.isBlank())ExtractionStatus.UNKNOWN else ExtractionStatus.PRESENT,
                attachments=ExtractionStatus.PARTIAL,note="Public caption and media preview; publication time and full media were not supplied."))
    }
}
