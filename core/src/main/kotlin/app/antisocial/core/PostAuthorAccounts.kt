/* anti social, GPL-3.0-or-later. A source account is separate from its label. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Literal source declaration, not an inference of common control. */
data class PostAuthorAccount(val profileUrl:String,val persistentId:String?=null) {
    private data class Resolved(val target:Target?)
    @Transient @Volatile private var resolved:Resolved?=null
    internal fun instagram():Target? {
        resolved?.let {return it.target}
        val target=runCatching {PostAuthorAccounts.resolve(this)}.getOrNull()
        resolved=Resolved(target);return target
    }
}

/** Only the currently qualified Instagram declaration is consumed. No I/O or
 * global history bank: the bounded derived result stays on its source value. */
object PostAuthorAccounts {
    private val handle=Regex("[A-Za-z0-9_.]{1,30}")
    private val number=Regex("[1-9][0-9]{0,39}")
    private val reserved=setOf("p","reel","reels","tv","accounts","api","graphql","direct","explore","stories","challenge","about","privacy","terms")
    fun instagram(username:String,persistentId:String?=null):PostAuthorAccount? {
        if(!handle.matches(username)||username.lowercase(java.util.Locale.ROOT) in reserved)return null
        return PostAuthorAccount("https://www.instagram.com/$username/",persistentId?.takeIf {number.matches(it)}).takeIf {it.instagram()!=null}
    }
    internal fun resolve(account:PostAuthorAccount):Target? {
        if(account.profileUrl.length>8192||account.persistentId?.let {number.matches(it)}==false)return null
        val url=account.profileUrl.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.host in setOf("instagram.com","www.instagram.com")&&
            it.username.isEmpty()&&it.password.isEmpty()&&it.query==null&&it.fragment==null} ?: return null
        val parts=url.pathSegments.filter(String::isNotBlank)
        if(parts.size!=1||!handle.matches(parts[0])||parts[0].lowercase(java.util.Locale.ROOT) in reserved)return null
        // Reject path encodings/extra separators rather than resolving another
        // identity after URL decoding or cleanup at a later action boundary.
        if(url.encodedPath!="/${parts[0]}"&&url.encodedPath!="/${parts[0]}/")return null
        return Target(Platform.INSTAGRAM,TargetKind.PROFILE,parts[0],"https://www.instagram.com/${parts[0]}/")
    }
    fun target(platform:Platform,account:PostAuthorAccount?):Target?=if(platform==Platform.INSTAGRAM)account?.instagram()else null
    fun target(post:Post):Target?=target(post.platform,post.authorAccount)
    fun valid(post:Post)=post.authorAccount==null||target(post)!=null
}
