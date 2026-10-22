package app.antisocial.core

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public social surfaces beyond the imported Darwin selection.
 * A federated server is an origin, not an interchangeable mirror of its peers.
 */
internal class ExpandedRoutes {
    private val servers=ConcurrentHashMap<String,Platform>().apply {
        federated.forEach {p->p.origin.toHttpUrlOrNull()?.let {put(authority(it),p)}}
        put("mstdn.social",Platform.MASTODON)
    }
    fun register(target: Target) {
        if(target.platform !in federated)return
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: return
        if(publicServer(url)&&servers.size<2048)servers.putIfAbsent(authority(url),target.platform)
    }
    fun recognize(uri: URI,selected: Platform?=null): Target? {
        val url=uri.toString().toHttpUrlOrNull()?.takeIf {it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        val host=url.host.removePrefix("www.")
        val fixed=when {
            host in setOf("facebook.com","m.facebook.com","mbasic.facebook.com","web.facebook.com","touch.facebook.com")||facebookLocale.matches(host) -> Platform.FACEBOOK
            host=="truthsocial.com" -> Platform.TRUTH_SOCIAL
            host in setOf("gettr.com","m.gettr.com") -> Platform.GETTR
            host=="gab.com" -> Platform.GAB
            host in setOf("t.me","telegram.me","telegram.dog") -> Platform.TELEGRAM
            host=="linkedin.com"||Regex("[a-z]{2}\\.linkedin\\.com").matches(host) -> Platform.LINKEDIN
            host=="snapchat.com" -> Platform.SNAPCHAT
            else -> null
        }
        val platform=fixed ?: servers[authority(url)] ?: selected?.takeIf {it in federated&&publicServer(url)} ?: return null
        val canonical=url.newBuilder().scheme("https").fragment(null).apply {
            if(fixed!=null){host(platform.origin.toHttpUrlOrNull()!!.host);port(443)}
            else if(url.scheme=="http"&&url.port==80)port(443)
        }.build()
        val target=if(platform in federated)federated(platform,canonical)else centralized(platform,canonical)
        if(target!=null)register(target)
        return target
    }
    fun input(platform: Platform,value: String,kind: TargetKind): Target? {
        if(platform !in supported)return null
        val input=value.trim();val raw=input.trim('/').removePrefix("@").removePrefix("!")
        if(platform in federated) {
            val qualified=Regex("^(?:([cu])/)?([A-Za-z0-9_.-]{1,100})@([A-Za-z0-9.-]+(?::[0-9]{1,5})?)$").matchEntire(raw)
            val root=qualified?.groupValues?.get(3)?.let {"https://$it"} ?: platform.origin
            val username=qualified?.groupValues?.get(2) ?: raw.removePrefix("c/").removePrefix("u/")
            require(username.matches(user)){"Enter a public username or a full server address"}
            val path=when(platform) {
                Platform.MASTODON,Platform.MISSKEY -> "/@$username"
                Platform.PIXELFED -> "/$username"
                Platform.LEMMY -> "/"+(if(qualified?.groupValues?.get(1)=="u"||raw.startsWith("u/")||kind==TargetKind.PROFILE&&input.startsWith('@'))"u"else "c")+"/$username"
                else -> error("Not a federated platform")
            }
            return recognize(URI(root+path),platform)
        }
        if(input.startsWith('#'))return null
        val path=when(platform) {
            Platform.TRUTH_SOCIAL -> "/@"+raw.removePrefix("@")
            Platform.GETTR -> if(raw.startsWith("user/")||raw.startsWith("post/"))"/$raw"else "/user/$raw"
            Platform.LINKEDIN -> if(raw.startsWith("company/")||raw.startsWith("in/")||raw.startsWith("school/"))"/$raw"else "/company/$raw"
            Platform.SNAPCHAT -> if(raw.startsWith("add/")||raw.startsWith("p/"))"/$raw"else "/add/$raw"
            else -> "/$raw"
        }
        return recognize(URI(platform.origin+path),platform)
    }
    private fun federated(platform: Platform,url: HttpUrl): Target? {
        val parts=url.pathSegments.filter(String::isNotBlank);val host=authority(url)
        val path=url.encodedPath.trim('/')
        if(parts.isEmpty())return null
        if(path.endsWith(".atom")||path.endsWith(".rss"))return Target(platform,TargetKind.FEED,"$path@$host",url.toString())
        if(parts.first()=="search")return Target(platform,TargetKind.SEARCH,url.queryParameter("q").orEmpty(),url.toString())
        if(parts.first() in setOf("tags","tag"))return Target(platform,TargetKind.TAG,parts.getOrNull(1).orEmpty()+"@$host",url.toString())
        return when(platform) {
            Platform.MASTODON -> {
                val profile=parts.first().startsWith('@')&&parts.first().removePrefix("@").matches(user)
                val userPath=parts.first()=="users"&&parts.getOrNull(1)?.matches(user)==true
                val username=if(profile)parts.first().removePrefix("@")else if(userPath)parts[1]else return null
                val id=if(profile&&parts.size==2)parts[1]else if(userPath&&parts.size==4&&parts[2]=="statuses")parts[3]else null
                if(id!=null&&id.matches(token))Target(platform,TargetKind.POST,"$path@$host",url.toString())
                else if(profile&&parts.size==1||userPath&&parts.size==2)Target(platform,TargetKind.PROFILE,"$username@$host",url.newBuilder().encodedPath("/@$username").query(null).build().toString())else null
            }
            Platform.LEMMY -> when {
                parts.size==2&&parts[0] in setOf("post","comment")&&parts[1].matches(number)->Target(platform,TargetKind.POST,"$path@$host",url.toString())
                parts.size==2&&parts[0] in setOf("c","u")&&parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}(?:@[A-Za-z0-9.-]+)?"))->Target(platform,if(parts[0]=="c")TargetKind.COMMUNITY else TargetKind.PROFILE,"$path@$host",url.toString())
                else -> null
            }
            Platform.PIXELFED -> when {
                parts.size==3&&parts[0]=="p"&&parts[2].matches(token)||parts.size==4&&parts.take(3)==listOf("i","web","post")&&parts[3].matches(token)->Target(platform,TargetKind.POST,"$path@$host",url.toString())
                parts.size==1&&parts[0].removePrefix("@").matches(user)&&parts[0] !in reserved-> {val username=parts[0].removePrefix("@");Target(platform,TargetKind.PROFILE,"$username@$host",url.newBuilder().encodedPath("/$username").query(null).build().toString())}
                else -> null
            }
            Platform.MISSKEY -> when {
                parts.size==2&&parts[0]=="notes"&&parts[1].matches(token)->Target(platform,TargetKind.POST,"$path@$host",url.toString())
                parts.size==1&&parts[0].startsWith('@')&&parts[0].removePrefix("@").matches(user)->Target(platform,TargetKind.PROFILE,"${parts[0].removePrefix("@")}@$host",url.newBuilder().query(null).build().toString())
                else -> null
            }
            else -> null
        }
    }
    private fun centralized(platform: Platform,url: HttpUrl): Target? {
        var canonical=url;var parts=url.pathSegments.filter(String::isNotBlank)
        // Internal discovery links retain their query even on sites whose public
        // reader does not implement a native search endpoint.
        if(parts.firstOrNull()=="search"&&url.queryParameter("q")!=null)return Target(platform,TargetKind.SEARCH,url.queryParameter("q").orEmpty(),url.toString())
        var kind=TargetKind.PROFILE
        when(platform) {
            Platform.FACEBOOK -> {
                val story=url.queryParameter("story_fbid");val owner=url.queryParameter("id")
                if(parts.firstOrNull() in setOf("story.php","permalink.php")&&story?.matches(token)==true&&owner?.matches(number)==true) {
                    canonical=url.newBuilder().encodedPath("/$owner/posts/$story").removeAllQueryParameters("id").removeAllQueryParameters("story_fbid").build()
                }else if(parts.firstOrNull()=="profile.php"&&owner?.matches(number)==true)canonical=url.newBuilder().encodedPath("/$owner").removeAllQueryParameters("id").build()
                parts=canonical.pathSegments.filter(String::isNotBlank)
                kind=when {
                    parts.firstOrNull()=="groups"&&parts.size<=2->TargetKind.COMMUNITY
                    parts.any {it in setOf("posts","videos","reel","photo","photos")}||parts.firstOrNull() in setOf("story.php","permalink.php","photo.php")->TargetKind.POST
                    parts.firstOrNull()=="search"->TargetKind.SEARCH
                    else -> TargetKind.PROFILE
                }
            }
            Platform.TRUTH_SOCIAL -> kind=when {parts.size>=2&&parts[0].startsWith('@')||parts.firstOrNull() in setOf("posts","statuses")->TargetKind.POST;parts.firstOrNull()=="search"->TargetKind.SEARCH;else->TargetKind.PROFILE}
            Platform.GETTR -> kind=if(parts.firstOrNull()=="post")TargetKind.POST else if(parts.firstOrNull()=="search")TargetKind.SEARCH else TargetKind.PROFILE
            Platform.GAB -> kind=if(parts.any {it in setOf("posts","statuses")})TargetKind.POST else if(parts.firstOrNull()=="search")TargetKind.SEARCH else TargetKind.PROFILE
            Platform.TELEGRAM -> {
                if(parts.firstOrNull()=="s")parts=parts.drop(1)
                if(parts.size !in 1..2||!parts[0].matches(Regex("[A-Za-z0-9_]{3,64}"))||parts.size==2&&!parts[1].matches(number)||parts[0] in setOf("joinchat","share","addstickers","proxy","socks","login","c"))return null
                canonical=url.newBuilder().encodedPath("/"+parts.joinToString("/")).query(null).build()
                kind=if(parts.size==2)TargetKind.POST else TargetKind.PROFILE
            }
            Platform.LINKEDIN -> kind=when {parts.firstOrNull() in setOf("posts","pulse")||parts.take(2)==listOf("feed","update")->TargetKind.POST;parts.firstOrNull()=="search"->TargetKind.SEARCH;else->TargetKind.PROFILE}
            Platform.SNAPCHAT -> kind=when {(parts.firstOrNull() in setOf("p","spotlight","stories")&&parts.size>1)||(parts.firstOrNull()?.startsWith('@')==true&&parts.getOrNull(1) in setOf("spotlight","story","stories")&&parts.size>2)->TargetKind.POST;parts.firstOrNull()=="search"->TargetKind.SEARCH;else->TargetKind.PROFILE}
            else -> return null
        }
        val path=canonical.pathSegments.filter(String::isNotBlank).joinToString("/")
        val identity=if(kind==TargetKind.SEARCH)canonical.queryParameter("q").orEmpty()else path
        return Target(platform,kind,identity,canonical.toString().trimEnd('/'))
    }
    companion object {
        val federated=setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)
        private val supported=federated+setOf(Platform.FACEBOOK,Platform.TRUTH_SOCIAL,Platform.GETTR,Platform.GAB,Platform.TELEGRAM,Platform.LINKEDIN,Platform.SNAPCHAT)
        private val user=Regex("[A-Za-z0-9_.-]{1,100}")
        private val token=Regex("[A-Za-z0-9_-]{1,200}")
        private val number=Regex("[0-9]{1,40}")
        private val reserved=setOf("login","register","explore","discover","search","about","settings","privacy","terms","auth","i","api")
        private val facebookLocale=Regex("[a-z]{2}(?:-[a-z]{2})?\\.facebook\\.com")
        private fun authority(url: HttpUrl)=url.host+(if(url.port!=80&&url.port!=443)":${url.port}"else "")
        private fun publicServer(url: HttpUrl)=url.host.contains('.')&&!url.host.endsWith(".local")&&!url.host.endsWith(".localhost")&&!url.host.matches(Regex("[0-9.]+"))&&Platform.entries.none {it !in federated&&it.origin.toHttpUrlOrNull()?.host?.removePrefix("www.")==url.host.removePrefix("www.")}
    }
}
