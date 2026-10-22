/* AntiSocial, GPL-3.0-or-later. Passive, source-backed records; no reputation score. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class SourceRecordKind { PAPER, WEBSITE, ACCOUNT }
data class SourceRecordRequest(val kind:SourceRecordKind,val identity:String) {
    val key:String get()="${kind.name}:$identity"
}
data class SourceFact(val kind:String,val title:String,val detail:String,val sourceUrl:String,val recordedAt:Long?=null,val destination:String?=null)
data class PublicSourceRecord(val request:SourceRecordRequest,val facts:List<SourceFact>,val checkedAt:Long,val complete:Boolean=true,val message:String="")
data class ProfileWebsite(val url:String,val label:String,val verifiedAt:Long)

/** Exact identifiers are the relevance gate. Text similarity never assigns a
 * paper, account or owner to an item. Directory assertions stay assertions. */
object PublicSourceRecords {
    private val identities by lazy {DarwinArticleRoutes()}
    internal fun linkedDoi(raw:String):String?=identities.scientific(raw)?.doi
    fun website(raw:String):SourceRecordRequest? {
        val u=safe(raw)?.toHttpUrlOrNull() ?: return null
        val host=u.topPrivateDomain() ?: return null
        if(host.endsWith(".onion")||host.endsWith(".invalid"))return null
        return SourceRecordRequest(SourceRecordKind.WEBSITE,host)
    }
    fun paper(raw:String):SourceRecordRequest?=linkedDoi(raw)?.takeUnless {it.startsWith("10.2210/pdb") }?.let {SourceRecordRequest(SourceRecordKind.PAPER,it)}
    fun account(did:String?):SourceRecordRequest?=did?.takeIf {Regex("did:plc:[a-z2-7]{24}").matches(it)}?.let {SourceRecordRequest(SourceRecordKind.ACCOUNT,it)}
    fun safe(raw:String?):String?=raw?.takeIf {it.length<=2048&&it.none(Char::isISOControl)}?.toHttpUrlOrNull()?.takeIf {
        it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()
    }?.toString()
    internal fun stamp(raw:String?):Long?=runCatching {Instant.parse(raw).toEpochMilli()}.getOrNull()
    internal fun text(o:JsonObject,key:String)=o[key]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun rows(o:JsonObject,key:String)=o[key]?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    private fun obj(v:JsonElement?)=v?.takeIf {it.isJsonObject}?.asJsonObject
    private fun clean(raw:String,max:Int=320)=Jsoup.parseBodyFragment(raw.take(4096)).text().filterNot(Char::isISOControl).take(max)
    private fun doi(raw:String?)=DarwinArticleRoutes.normalizeDoi(raw)
    private fun date(o:JsonObject):Long? {
        val d=obj(o["updated"]) ?: return null
        stamp(text(d,"date-time"))?.let {return it}
        return runCatching {val p=d.getAsJsonArray("date-parts")[0].asJsonArray.toList()
            LocalDate.of(p[0].asInt,p.getOrNull(1)?.asInt ?: 1,p.getOrNull(2)?.asInt ?: 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
    /** updated-by is inbound: notice -> requested paper. update-to on a notice
     * points the other way and must never retract the notice itself. */
    fun crossref(request:SourceRecordRequest,raw:String,at:Long):PublicSourceRecord {
        require(request.kind==SourceRecordKind.PAPER)
        val root=JsonParser.parseString(raw).asJsonObject
        require(text(root,"status")=="ok")
        val work=root.getAsJsonObject("message")
        require(doi(text(work,"DOI"))==request.identity){"Publication identity did not match"}
        val facts=rows(work,"updated-by").take(100).mapNotNull {entry->
            val e=obj(entry) ?: return@mapNotNull null
            val notice=doi(text(e,"DOI")) ?: return@mapNotNull null
            val type=text(e,"type").lowercase()
            val label=when(type){"retraction"->"Retraction";"correction","corrigendum","erratum"->"Correction";"expression-of-concern","concern"->"Expression of concern";"reinstatement"->"Reinstatement";"withdrawal"->"Withdrawal";else->"Publication update"}
            val source=when(text(e,"source")){"retraction-watch"->"Retraction Watch";"publisher"->"the publisher";else->"Crossref"}
            SourceFact("paper:$type",label,"Recorded by $source for this paper.","https://api.crossref.org/works/${request.identity}",date(e),"https://doi.org/$notice")
        }.groupBy {Triple(it.kind,it.destination,it.recordedAt)}.values.map {group->
            if(group.size>1)group.first().copy(detail="Recorded by "+group.map {it.detail.removePrefix("Recorded by ").removeSuffix(" for this paper.")}.distinct().joinToString(" and ")+" for this paper.")else group.first()
        }.sortedByDescending {it.recordedAt ?: 0}.take(30)
        return PublicSourceRecord(request,facts,at,message=if(facts.isEmpty())"No publication updates are listed in this Crossref record. Coverage can be incomplete."else "Publication notices describe changes to this paper; they do not evaluate the post citing it.")
    }
    fun bootstrap(raw:String,domain:String):String? {
        val root=JsonParser.parseString(raw).asJsonObject
        return rows(root,"services").take(2000).mapNotNull {v->runCatching {
            val a=v.asJsonArray;val suffixes=a[0].asJsonArray.map {it.asString.lowercase()}
            val match=suffixes.filter {domain==it||domain.endsWith(".$it")}.maxByOrNull {it.length} ?: return@runCatching null
            val endpoint=a[1].asJsonArray.mapNotNull {safe(it.asString)}.firstOrNull {it.toHttpUrlOrNull()?.query==null} ?: return@runCatching null
            match.length to endpoint
        }.getOrNull()}.maxByOrNull {it.first}?.second
    }
    fun rdap(request:SourceRecordRequest,raw:String,source:String):List<SourceFact> {
        require(request.kind==SourceRecordKind.WEBSITE)
        val root=JsonParser.parseString(raw).asJsonObject
        require(text(root,"objectClassName")=="domain"&&text(root,"ldhName").equals(request.identity,true)){"Registration identity did not match"}
        val facts=mutableListOf<SourceFact>()
        rows(root,"events").take(40).mapNotNull(::obj).firstOrNull {text(it,"eventAction")=="registration"}?.let {event->
            stamp(text(event,"eventDate"))?.let {facts+=SourceFact("registration","Domain registered","Registration date for ${request.identity}; this is not the publisher's founding date.",source,it)}
        }
        rows(root,"entities").take(40).mapNotNull(::obj).filter {rows(it,"roles").any {r->r.isJsonPrimitive&&r.asString=="registrar"}}.firstOrNull()?.let {registrar->
            val card=registrar["vcardArray"]?.takeIf {it.isJsonArray}?.asJsonArray
            val name=runCatching {card?.get(1)?.asJsonArray?.take(40)?.firstOrNull {it.isJsonArray&&it.asJsonArray.size()>=4&&it.asJsonArray[0].asString=="fn"}?.asJsonArray?.get(3)?.asString}.getOrNull()
            name?.let {clean(it)}?.takeIf(String::isNotBlank)?.let {facts+=SourceFact("registrar","Registrar",it,source)}
        }
        val statuses=rows(root,"status").take(20).filter {it.isJsonPrimitive&&it.asJsonPrimitive.isString}.map {clean(it.asString,100)}
        if(statuses.isNotEmpty())facts+=SourceFact("registration-status","Registration status",statuses.joinToString(", "),source)
        return facts
    }
    /** Only explicit owner/manager declarations. Seller IDs and DIRECT are
     * advertising accounts, never proof that two publications share an owner. */
    fun advertising(raw:String,source:String):List<SourceFact> {
        require(safe(source)!=null)
        if(raw.trimStart().startsWith("<"))return emptyList()
        return raw.lineSequence().take(10_000).mapNotNull {line->
            val parts=line.substringBefore('#').split('=',limit=2);if(parts.size!=2)return@mapNotNull null
            val type=parts[0].trim().uppercase();if(type !in setOf("OWNERDOMAIN","MANAGERDOMAIN"))return@mapNotNull null
            val values=parts[1].split(',').map(String::trim);val domain=values.first()
            val url=safe("https://$domain/")?.toHttpUrlOrNull()?.takeIf {it.encodedPath=="/"&&it.query==null&&it.fragment==null&&it.host==domain.lowercase()&&it.topPrivateDomain()!=null} ?: return@mapNotNull null
            val region=values.drop(1).take(1).firstOrNull()?.takeIf {Regex("[A-Za-z]{2}").matches(it)}
            SourceFact(if(type=="OWNERDOMAIN")"declared-owner"else "declared-ad-manager",if(type=="OWNERDOMAIN")"Declared owner"else "Declared advertising manager",
                url.host+(region?.let {" · ${it.uppercase()}"} ?: ""),source,destination=url.toString())
        }.distinctBy {it.kind+it.detail}.take(20).toList()
    }
    fun plc(request:SourceRecordRequest,raw:String,at:Long):PublicSourceRecord {
        require(account(request.identity)==request)
        val all=JsonParser.parseString(raw).asJsonArray
        require(all.size()<=512){"Account history exceeds its allowance"}
        val entries=all.mapNotNull(::obj).filter {text(it,"did")==request.identity&&it["nullified"]?.takeIf {v->v.isJsonPrimitive&&v.asJsonPrimitive.isBoolean}?.asBoolean==false}
            .mapNotNull {row->stamp(text(row,"createdAt"))?.let {it to row}}.sortedBy {it.first}
        require(entries.isNotEmpty())
        val facts=mutableListOf<SourceFact>();var handles=emptyList<String>();var host:String?=null
        for((time,row) in entries) {
            val op=obj(row["operation"]) ?: continue
            if(text(op,"type")=="plc_tombstone") {facts+=SourceFact("account-deactivated","Account deactivated","The directory records a tombstone for this identity.",plcUrl(request),time);continue}
            val next=rows(op,"alsoKnownAs").filter {it.isJsonPrimitive&&it.asJsonPrimitive.isString}.map {it.asString}.filter {it.startsWith("at://")}.map {it.removePrefix("at://")}.filter {Regex("[a-zA-Z0-9.-]{3,253}").matches(it)&&'.' in it}.distinct().take(5)
                .ifEmpty {text(op,"handle").takeIf {Regex("[a-zA-Z0-9.-]{3,253}").matches(it)&&'.' in it}?.let(::listOf).orEmpty()}
            val pds=obj(obj(op["services"])?.get("atproto_pds"))?.let {safe(text(it,"endpoint"))} ?: safe(text(op,"service"))
            if(next.isNotEmpty()&&next!=handles){facts+=SourceFact("account-handle",if(handles.isEmpty())"Recorded handle"else "Handle changed",next.joinToString(", "),plcUrl(request),time);handles=next}
            if(pds!=null&&pds!=host){facts+=SourceFact("account-host",if(host==null)"Recorded hosting"else "Hosting changed",pds.toHttpUrlOrNull()!!.host,plcUrl(request),time);host=pds}
        }
        return PublicSourceRecord(request,facts.sortedByDescending {it.recordedAt}.take(60),at,message="PLC directory declarations for one persistent identity. Historical handles may now belong to someone else. Signatures have not been independently audited by anti social.")
    }
    private fun plcUrl(r:SourceRecordRequest)="https://plc.directory/${r.identity}/log/audit"
    fun verifiedWebsites(node:JsonObject,base:String):List<ProfileWebsite> = rows(node,"fields").take(12).mapNotNull(::obj).flatMap {field->
        val time=stamp(text(field,"verified_at")) ?: return@flatMap emptyList()
        val urls=Jsoup.parseBodyFragment(text(field,"value"),base).select("a[href]").mapNotNull {a->safe(a.absUrl("href"))}.distinct()
        if(urls.size==1)listOf(ProfileWebsite(urls.single(),clean(text(field,"name"),80),time))else emptyList()
    }.distinctBy {it.url}.take(12)
}

/** Small read-only requests. All network policy/filtering remains in Gateway. */
class PublicSourceRecordReader(private val gateway:NetworkGateway,private val clock:()->Long=System::currentTimeMillis) {
    internal var read:suspend (String,Long)->String={url,max->
        val parsed=url.toHttpUrlOrNull()!!
        val origins=if(parsed.encodedPath=="/ads.txt"&&parsed.host==parsed.topPrivateDomain())setOf(origin(url),"https://www.${parsed.host}")else setOf(origin(url))
        val p=gateway.text(url,url,maxBytes=max,allowedOrigins=origins)
        require(p.status in 200..299){"Public record is unavailable"};p.text
    }
    private var bootstrap:Pair<Long,String>?=null
    suspend fun lookup(request:SourceRecordRequest,onPartial:suspend (PublicSourceRecord)->Unit={}):PublicSourceRecord {
        val mode=gateway.currentPolicy();require(!mode.tor||mode.ready){"Tor is not ready"}
        suspend fun get(url:String,max:Long=512*1024):String {require(gateway.currentPolicy()==mode){"Network mode changed"};val text=read(url,max);require(gateway.currentPolicy()==mode){"Network mode changed"};return text}
        return when(request.kind) {
            SourceRecordKind.PAPER->{require(PublicSourceRecords.paper("https://doi.org/${request.identity}")==request)
                val url="https://api.crossref.org/works/".toHttpUrlOrNull()!!.newBuilder().addPathSegment(request.identity).build().toString()
                PublicSourceRecords.crossref(request,get(url),clock())}
            SourceRecordKind.ACCOUNT->{require(PublicSourceRecords.account(request.identity)==request)
                PublicSourceRecords.plc(request,get("https://plc.directory/${request.identity}/log/audit"),clock())}
            SourceRecordKind.WEBSITE->{require(PublicSourceRecords.website("https://${request.identity}/")==request)
                val facts=mutableListOf<SourceFact>();var failures=0
                suspend fun attempt(block:suspend ()->Unit){try{block()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){failures++}}
                attempt {
                    val data=bootstrap?.takeIf {clock()-it.first in 0..7*CachePolicy.DAY}?.second ?: get("https://data.iana.org/rdap/dns.json",256*1024).also {bootstrap=clock() to it}
                    val root=PublicSourceRecords.bootstrap(data,request.identity) ?: error("No HTTPS registry")
                    val url=root.toHttpUrlOrNull()!!.newBuilder().addPathSegment("domain").addPathSegment(request.identity).build().toString()
                    facts+=PublicSourceRecords.rdap(request,get(url),url)
                }
                onPartial(PublicSourceRecord(request,facts.toList(),clock(),false,"Checking publisher declarations…"))
                attempt {val url="https://${request.identity}/ads.txt";facts+=PublicSourceRecords.advertising(get(url,256*1024),url)}
                PublicSourceRecord(request,facts,clock(),failures==0,if(failures>0)"Some public records were unavailable. Missing records make no claim about this publisher."else "Owner and advertising manager are the website's own declarations. Registration describes the domain.")
            }
        }
    }
    private fun origin(url:String)=url.toHttpUrlOrNull()!!.let {"${it.scheme}://${it.host}"+(if(it.port!=443)":${it.port}"else "")}
}
