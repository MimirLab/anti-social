/* AntiSocial, GPL-3.0-or-later. Independently reviewed pathways extend Darwin's donor pool. */
package app.antisocial.core

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Bundled protocol/path metadata is separate from Darwin's reverse-link mappings:
 * a shared RSS renderer or API host is not a social URL alias. */
class PublicPathways(data: JsonObject?=null) {
    data class Entry(val source: Source,val selectedOrigin: Boolean=false,val priority: Int=0)
    val entries: List<Entry> = data?.getAsJsonArray("sources")?.take(1024)?.mapNotNull {node->runCatching {
        val item=node.asJsonObject
        if(item.get("enabled")?.asBoolean!=true)return@runCatching null
        val platform=Platform.entries.firstOrNull {it.id==item["platform"].asString} ?: return@runCatching null
        val root=item["root"].asString.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.query==null&&it.fragment==null} ?: return@runCatching null
        val frontend=item["frontend"].asString.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))} ?: return@runCatching null
        val role=item["role"]?.asString?.let {SourceRole.valueOf(it)} ?: SourceRole.ALTERNATE
        val selected=item["scope"]?.asString=="selected-origin"
        if(selected&&(platform !in FEDERATED||role!=SourceRole.ORIGINAL))return@runCatching null
        Entry(Source(platform,frontend,root.toString().trimEnd('/'),item["fallbackOnly"]?.asBoolean ?: true,role),selected,item["priority"]?.asInt?.coerceIn(-10,10) ?: 0)
    }.getOrNull() }?.distinctBy {it.source.key to it.selectedOrigin}.orEmpty()
    fun sources(platform: Platform)=entries.filter {!it.selectedOrigin&&it.source.platform==platform}.map {it.source}
    fun sources(target: Target): List<Source> = entries.filter {it.source.platform==target.platform}.mapNotNull {entry->
        if(!entry.selectedOrigin)entry.source else target.canonicalUrl.toHttpUrlOrNull()?.let {url->
            entry.source.copy(root=url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
        }
    }
    fun priority(source: Source)=entries.firstOrNull {it.source.platform==source.platform&&it.source.frontend==source.frontend&&(it.selectedOrigin||it.source.root==source.root)}?.priority ?: 0
    companion object {private val FEDERATED=setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY)}
}
