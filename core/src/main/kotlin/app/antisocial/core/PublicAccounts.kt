/* anti social, GPL-3.0-or-later. Discovery identities are broader than reader subscriptions. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A public account may be useful as a route even when it cannot supply a reader feed. */
data class PublicAccount(val provider:String,val url:String,val name:String="",val id:String?=null,
    val kind:AccountLinkKind=AccountLinkKind.PROFILE,val sourceUrl:String?=null,val identityGroup:String?=null,
    val anchors:List<String> = emptyList(),val links:List<String> = emptyList())

object PublicAccounts {
    private val extra=mapOf("soundcloud.com" to "soundcloud","www.soundcloud.com" to "soundcloud",
        "mixcloud.com" to "mixcloud","www.mixcloud.com" to "mixcloud","twitch.tv" to "twitch","www.twitch.tv" to "twitch")
    private val reserved=setOf("search","discover","explore","login","signup","settings","terms","privacy","about","genres","directory","popular","stream","you","upload","jobs","pages","messages","notifications")
    fun target(account:PublicAccount,routes:DarwinRoutes)=routes.recognize(account.url)?.takeIf {AccountLinkage.validIdentity(it)}
    fun key(account:PublicAccount)=account.provider+":"+(account.id?.takeIf(String::isNotBlank) ?: account.url.trimEnd('/'))
    fun label(provider:String)=when(provider){"youtube"->"YouTube";"soundcloud"->"SoundCloud";"mixcloud"->"Mixcloud";"bandcamp"->"Bandcamp";"twitch"->"Twitch";"nostr"->"Nostr";else->Platform.entries.firstOrNull {it.id==provider}?.label ?: "Public website"}
    fun parse(raw:String,routes:DarwinRoutes,name:String="",kind:AccountLinkKind=AccountLinkKind.PROFILE,source:String?=null,group:String?=null,anchors:List<String> = emptyList()):PublicAccount? {
        val clean=PublicRelations.url(raw,source ?: raw) ?: return null
        val known=routes.recognize(clean)?.takeIf(AccountLinkage::validIdentity)
        if(known!=null)return PublicAccount(known.platform.id,known.canonicalUrl,name.take(160),
            if(known.platform==Platform.YOUTUBE&&known.identity.startsWith("channel/"))known.identity.removePrefix("channel/")else null,kind,source,group,anchors.take(24))
        val u=clean.toHttpUrlOrNull() ?: return null;val parts=u.pathSegments.filter(String::isNotBlank)
        val provider=extra[u.host] ?: when {
            u.host.endsWith(".bandcamp.com")&&u.host.substringBefore(".bandcamp.com").matches(Regex("[a-z0-9-]{1,63}"))&&parts.isEmpty()->"bandcamp"
            u.host in setOf("njump.me","primal.net")&&parts.size==1&&parts[0].matches(Regex("npub1[a-z0-9]{58}"))->"nostr"
            else->return null
        }
        if(provider !in setOf("bandcamp","nostr")&&(parts.size!=1||parts[0].lowercase() in reserved||!parts[0].matches(Regex("[A-Za-z0-9_-]{1,100}"))))return null
        val canonicalHost=when(provider){"soundcloud"->"soundcloud.com";"mixcloud"->"www.mixcloud.com";"twitch"->"www.twitch.tv";else->u.host}
        val url=u.newBuilder().host(canonicalHost).query(null).fragment(null).build().toString().trimEnd('/')
        return PublicAccount(provider,url,name.take(160),kind=kind,sourceUrl=source,identityGroup=group,anchors=anchors.take(24))
    }
    fun from(target:Target,name:String="",kind:AccountLinkKind=AccountLinkKind.ENTERED)=PublicAccount(target.platform.id,target.canonicalUrl,name.take(160),
        if(target.platform==Platform.YOUTUBE&&target.identity.startsWith("channel/"))target.identity.removePrefix("channel/")else null,kind,target.canonicalUrl)
    fun valid(value:PublicAccount,routes:DarwinRoutes)=value.name.length<=160&&value.links.size<=24&&value.anchors.size<=24&&parse(value.url,routes)!=null
    fun identityDeclared(account:PublicAccount,known:kotlin.collections.Collection<PublicAccount>)=account.kind in setOf(AccountLinkKind.ENTERED,AccountLinkKind.LOCAL,AccountLinkKind.DIRECTORY)||
        account.kind==AccountLinkKind.WEBSITE&&account.identityGroup!=null&&account.anchors.any {url->known.any {it.url.trimEnd('/')==url.trimEnd('/')}}
}
