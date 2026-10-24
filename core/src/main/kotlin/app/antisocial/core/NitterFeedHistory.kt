/* anti social, GPL-3.0-or-later. Protocol facts from Nitter; see FEED_COVERAGE.md. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader

/** Nitter publishes an opaque RSS cursor in Min-Id. It is data for the same
 * account/feed operation, never a URL supplied by the publisher. The local
 * bookmark binds that operation to its source, target and current session. */
internal object NitterFeedHistory {
    private const val PREFIX="nitter-rss-v1:"
    private const val MAX_CURSOR=4096
    private const val MAX_BOOKMARK=24*1024
    private val handle=Regex("[A-Za-z0-9_]{1,15}")
    private val listId=Regex("[0-9]{1,30}")
    private val tabs=setOf("with_replies","media","articles")
    private val fields=setOf("source","target","url","session")
    fun bookmark(value:String)=value.startsWith(PREFIX)
    private fun sourceKey(source:Source)=source.key+"|"+source.role.name
    private fun token(value:String)=value.length in 1..MAX_CURSOR&&value.all {it.code in 33..126&&it!=','}&&!value.equals("suspended",true)
    private fun address(value:String,target:Target,source:Source):HttpUrl? {
        if(source.platform!=Platform.X||source.frontend!="nitter"||source.role!=SourceRole.ALTERNATE||target.platform!=Platform.X||target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY))return null
        val original=target.canonicalUrl.toHttpUrlOrNull() ?: return null
        if(!original.isHttps||original.username.isNotEmpty()||original.password.isNotEmpty()||original.host !in setOf("x.com","www.x.com","twitter.com","www.twitter.com","mobile.twitter.com")||original.query!=null||original.fragment!=null)return null
        val logical=original.pathSegments
        val list=logical.size==3&&logical[0]=="i"&&logical[1]=="lists"&&listId.matches(logical[2])
        val account=(logical.size==1||logical.size==2&&logical[1] in tabs)&&handle.matches(logical[0])
        if(!list&&(!account||target.kind!=TargetKind.PROFILE))return null
        if(!target.identity.removePrefix("@").equals(logical.joinToString("/"),true))return null
        val root=source.root.toHttpUrlOrNull() ?: return null
        val url=value.toHttpUrlOrNull() ?: return null
        if(root.username.isNotEmpty()||root.password.isNotEmpty()||root.query!=null||root.fragment!=null||url.username.isNotEmpty()||url.password.isNotEmpty()||url.fragment!=null)return null
        if(url.scheme!=root.scheme||url.host!=root.host||url.port!=root.port)return null
        val prefix=root.encodedPath.trimEnd('/')+"/"
        if(!url.encodedPath.startsWith(prefix))return null
        val parts=url.encodedPath.removePrefix(prefix).split('/')
        if(parts.size!=logical.size+1||parts.last()!="rss"||logical.indices.any {i->if(account&&i==0)!parts[i].equals(logical[i],true)||!handle.matches(parts[i])else parts[i]!=logical[i]})return null
        if(url.query!=null&&(url.queryParameterNames!=setOf("cursor")||url.queryParameterValues("cursor").size!=1||!token(url.queryParameter("cursor").orEmpty())))return null
        return url
    }
    fun continuation(value:String,target:Target,source:Source)=address(value,target,source)?.queryParameter("cursor")!=null
    fun navigation(requested:String,target:Target,source:Source,response:GatewayDocument,page:Page,session:NetworkSession,previous:String?=null):String? {
        if(address(requested,target,source)==null||page.provenance?.source!=source||!page.limitedTimeline||FeedRefresh.pendingReason(page,target.platform)!=null)return null
        val final=address(response.finalUrl,target,source) ?: return null
        val values=response.headers.entries.filter {it.key.equals("Min-Id",true)}.flatMap {it.value}
        if(values.isEmpty()&&response.status==304)return previous
        val cursor=values.singleOrNull()?.trim()?.takeIf(::token) ?: return null
        val next=final.newBuilder().query(null).addQueryParameter("cursor",cursor).build().toString()
        val data=JsonObject().apply {
            addProperty("source",sourceKey(source));addProperty("target",target.canonicalUrl)
            addProperty("url",next);addProperty("session",session.feedCursorEpoch)
        }
        return (PREFIX+data).takeIf {it.length<=MAX_BOOKMARK}
    }
    private fun invalid()=SourceFailure(HealthStatus.EMPTY,"Refresh this feed to continue its public history")
    data class Read(val url:String,val session:NetworkSession)
    fun resolve(value:String,target:Target,source:Source,gateway:NetworkGateway):Read {
        if(!bookmark(value)||value.length>MAX_BOOKMARK)throw invalid()
        val data=try {
            JsonReader(StringReader(value.removePrefix(PREFIX))).use {reader->
                reader.strictness=Strictness.STRICT
                reader.nestingLimit=2
                reader.beginObject();val result=mutableMapOf<String,String>()
                while(reader.hasNext()) {
                    val name=reader.nextName()
                    if(name !in fields||name in result||reader.peek()!=JsonToken.STRING)throw invalid()
                    result[name]=reader.nextString()
                }
                reader.endObject()
                if(reader.peek()!=JsonToken.END_DOCUMENT||result.keys!=fields)throw invalid()
                result
            }
        }catch(e:Exception){throw invalid()}
        if(data["source"]!=sourceKey(source)||data["target"]!=target.canonicalUrl)throw invalid()
        val url=address(data.getValue("url"),target,source)?.takeIf {it.queryParameter("cursor")!=null&&it.toString()==data["url"]} ?: throw invalid()
        val session=gateway.sessionFor(url.toString())
        if(data["session"]!=session.feedCursorEpoch)throw CancellationException("Feed connection changed; refresh this feed to continue")
        return Read(url.toString(),session)
    }
}
