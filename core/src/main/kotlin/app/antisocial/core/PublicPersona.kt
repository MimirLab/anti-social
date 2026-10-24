/* anti social, GPL-3.0-or-later. First-party public-read identity construction.
 * Protocol/UA facts: Chromium UA reduction and dated owned wire observations.
 * No external fingerprint generator, device interrogation or network access. */
package app.antisocial.core

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom

/** A whole browser/OS/protocol cohort, not independent random fingerprint fields.
 * These initial contracts are research inputs, not enabled acquisition routes. */
internal enum class PersonaCohort(val major:Int,val fullVersion:String,val platform:String,val mobile:Boolean) {
    ANDROID_131(131,"131.0.6778.261","Android",true),
    MACOS_142(142,"142.0.7444.177","macOS",false)
}

internal data class PersonaBrand(val name:String,val version:String)
/** Declarative first-party wire contract. A transport must implement the whole
 * contract; applying these HTTP headers to an unrelated TLS stack is not enough.
 * GREASE values and TLS extension permutation remain connection-local. */
internal data class PersonaWireContract(val cipherSuites:List<Int>,val groups:List<Int>,val alpsExtension:Int,
    val alpn:List<String>,val h2Settings:List<Pair<Int,Int>>,val connectionWindowIncrement:Int,
    val pseudoHeaderOrder:List<String>,val decoders:Set<String>)
internal data class PersonaDevice(val model:String,val platform:String,val architecture:String,val bitness:String,
    val platformVersion:String,val screenWidth:Int,val screenHeight:Int,val pixelRatio:Double,
    val cores:Int,val memoryGiB:Int,val graphicsVendor:String,val graphicsRenderer:String)

/** All exposed identity surfaces derive from one validated release and device.
 * The contract describes synthetic values; it does not execute a browser realm. */
internal data class PublicPersona(val cohort:PersonaCohort,val browserVersion:String,val userAgent:String,
    val appVersion:String,val device:PersonaDevice,val brands:List<PersonaBrand>,val languages:List<String>) {
    val mobile get()=cohort.mobile
    val wire get()=PublicPersonaFactory.wire(cohort)
    fun headers(navigation:Boolean):List<Pair<String,String>> = buildList {
        add("sec-ch-ua" to brands.joinToString(", "){"\"${it.name}\";v=\"${it.version}\""})
        add("sec-ch-ua-mobile" to if(mobile)"?1"else "?0")
        add("sec-ch-ua-platform" to "\"${cohort.platform}\"")
        if(navigation)add("upgrade-insecure-requests" to "1")
        add("user-agent" to userAgent)
        add("accept" to if(navigation)"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"else "application/json, text/plain, */*")
        add("sec-fetch-site" to if(navigation)"none"else "same-origin")
        add("sec-fetch-mode" to if(navigation)"navigate"else "cors")
        if(navigation)add("sec-fetch-user" to "?1")
        add("sec-fetch-dest" to if(navigation)"document"else "empty")
        add("accept-encoding" to wire.decoders.joinToString(", "))
        add("accept-language" to languages.mapIndexed {i,s->if(i==0)s else "$s;q=0.${10-i}"}.joinToString(","))
    }
}

internal object PublicPersonaFactory {
    fun wire(cohort:PersonaCohort)=PersonaWireContract(
        listOf(4865,4866,4867,49195,49199,49196,49200,52393,52392,49171,49172,156,157,47,53),
        if(cohort.mobile)listOf(29,23,24)else listOf(4588,29,23,24),
        if(cohort.mobile)17513 else 17613,listOf("h2","http/1.1"),
        listOf(1 to 65536,2 to 0,4 to 6291456,6 to 262144),15663105,
        listOf(":method",":authority",":scheme",":path"),linkedSetOf("gzip","deflate","br","zstd"))
    private fun brands(cohort:PersonaCohort)=if(cohort.mobile)
        listOf(PersonaBrand("Google Chrome","131"),PersonaBrand("Chromium","131"),PersonaBrand("Not_A Brand","24"))
    else listOf(PersonaBrand("Chromium","142"),PersonaBrand("Google Chrome","142"),PersonaBrand("Not_A Brand","99"))
    private val android=listOf(
        PersonaDevice("SM-S911B","Linux armv8l","arm","64","14.0.0",360,780,3.0,8,8,"Qualcomm","Adreno (TM) 740"),
        PersonaDevice("SM-S911B","Linux armv8l","arm","64","15.0.0",360,780,3.0,8,8,"Qualcomm","Adreno (TM) 740")
    )
    private val mac=listOf(
        PersonaDevice("","MacIntel","arm","64","26.0.0",1512,982,2.0,8,8,"Google Inc. (Apple)","ANGLE (Apple, ANGLE Metal Renderer: Apple M1 Pro, Unspecified Version)"),
        PersonaDevice("","MacIntel","arm","64","26.0.0",1512,982,2.0,10,8,"Google Inc. (Apple)","ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)")
    )
    fun create(cohort:PersonaCohort,variant:Int):PublicPersona {
        val devices=if(cohort.mobile)android else mac
        val device=devices[Math.floorMod(variant,devices.size)]
        val os=if(cohort.mobile)"Linux; Android 10; K"else "Macintosh; Intel Mac OS X 10_15_7"
        val ua="Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${cohort.major}.0.0.0${if(cohort.mobile)" Mobile"else ""} Safari/537.36"
        return PublicPersona(cohort,cohort.fullVersion,ua,ua.removePrefix("Mozilla/"),device,
            brands(cohort),listOf("en-US","en"))
            .also {require(problems(it).isEmpty()){"Invalid constructed persona"}}
    }
    /** Independent cross-surface admission, including contradictions observed in
     * external generator output. A plausible UA alone is insufficient. */
    fun problems(p:PublicPersona):List<String> = buildList {
        val major=Regex("Chrome/([0-9]+)\\.").find(p.userAgent)?.groupValues?.get(1)
        if(major!=p.cohort.major.toString()||p.browserVersion!=p.cohort.fullVersion)add("release")
        if(p.appVersion!=p.userAgent.removePrefix("Mozilla/"))add("appVersion")
        val actual=p.brands.filter {it.name!="Not_A Brand"}.associate {it.name to it.version}
        if(actual!=mapOf("Chromium" to major,"Google Chrome" to major)||p.brands.map {it.name}.distinct().size!=p.brands.size)add("brands")
        if(p.brands!=brands(p.cohort))add("brand contract")
        if(p.mobile) {
            if(!p.userAgent.contains("Linux; Android 10; K")||!p.userAgent.contains(" Mobile ")||p.device.platform!="Linux armv8l"||p.device.model.isBlank()||p.device.architecture!="arm")add("mobile")
        }else if(!p.userAgent.contains("Macintosh; Intel Mac OS X 10_15_7")||p.userAgent.contains(" Mobile ")||p.device.platform!="MacIntel"||p.device.model.isNotEmpty())add("desktop")
        if(p.device.screenWidth !in 240..4096||p.device.screenHeight !in 240..4096||p.device.pixelRatio !in 1.0..4.0||p.device.cores !in setOf(4,8,10)||p.device.memoryGiB !in setOf(4,8))add("device")
        if(p.device !in if(p.mobile)android else mac)add("device contract")
        if(p.languages.size !in 1..3||p.languages.distinct().size!=p.languages.size||p.languages.any {!it.matches(Regex("[a-z]{2}(-[A-Z]{2})?"))})add("languages")
        if(p.headers(true).any {(k,v)->k.any {it.code !in 33..126}||v.length>1024||v.any {it.code !in 32..126}})add("headers")
    }
}

/** A bounded, disposable public-session bank. It grants no permission to fetch:
 * Gateway must still admit purpose, destination, source, transport and budget.
 * Nothing is persisted or shared with the reader's service credentials. */
internal class PublicPersonaBank(private val sessionFor:(String)->NetworkSession,private val clock:()->Long=System::currentTimeMillis,
    private val entropy:(ByteArray)->Unit=SecureRandom()::nextBytes,
    private val capacity:Int=16,private val lifetimeMillis:Long=10*60_000L) {
    init {require(capacity in 1..32&&lifetimeMillis in 1..30*60_000L)}
    class Lease internal constructor(internal val id:String,val persona:PublicPersona) {
        override fun toString()="Public persona (${persona.cohort.name})"
    }
    private data class Scope(val source:String,val target:Target,val origin:String)
    private data class CookieKey(val name:String,val domain:String,val path:String)
    private data class Entry(val lease:Lease,val scope:Scope,val session:NetworkSession,val created:Long,
        val cookies:LinkedHashMap<CookieKey,Cookie> = linkedMapOf())
    private val entries=linkedMapOf<String,Entry>()
    private fun origin(url:HttpUrl)=url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    private fun address(value:String)=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null}
        ?: throw IllegalArgumentException("Invalid public persona address")
    private fun scope(source:Source,target:Target,url:HttpUrl):Scope {
        require(source.platform==target.platform&&source.root.length<=4096&&target.canonicalUrl.length<=8192)
        require(origin(address(source.root))==origin(url)){"Persona destination changed its selected source"}
        return Scope(source.key+"|"+source.role.name,target,origin(url))
    }
    private fun purge(now:Long){entries.entries.removeAll {now<it.value.created||now-it.value.created>=lifetimeMillis}}
    @Synchronized fun acquire(source:Source,target:Target,url:String,cohort:PersonaCohort):Lease {
        val parsed=address(url);val session=sessionFor(parsed.toString());val scope=scope(source,target,parsed);val now=clock();purge(now)
        entries.values.firstOrNull {it.scope==scope&&it.session===session&&it.lease.persona.cohort==cohort}?.let {return it.lease}
        require(!session.policy.tor||session.policy.ready&&session.policy.socksPort in 1..65535){"Selected Tor is not ready"}
        val bytes=ByteArray(16).also(entropy);val id=bytes.joinToString(""){"%02x".format(it)}
        check(id !in entries){"Persona entropy reused a live identity"}
        val lease=Lease(id,PublicPersonaFactory.create(cohort,bytes[0].toInt()))
        while(entries.size>=capacity)entries.remove(entries.keys.first())
        entries[id]=Entry(lease,scope,session,now);return lease
    }
    private fun current(lease:Lease,source:Source,target:Target,url:HttpUrl):Entry {
        val session=sessionFor(url.toString());purge(clock());val entry=entries[lease.id]
        check(entry!=null&&entry.lease===lease&&entry.session===session&&entry.scope==scope(source,target,url)){"Public persona expired or changed scope"}
        return entry
    }
    @Synchronized fun cookies(lease:Lease,source:Source,target:Target,url:String):String? {
        val parsed=address(url);val e=current(lease,source,target,parsed);val now=clock()
        e.cookies.entries.removeAll {it.value.expiresAt<=now}
        return e.cookies.values.filter {it.matches(parsed)}.sortedByDescending {it.path.length}.takeIf {it.isNotEmpty()}?.joinToString("; "){"${it.name}=${it.value}"}
    }
    @Synchronized fun receive(lease:Lease,source:Source,target:Target,url:String,setCookies:List<String>) {
        val parsed=address(url);val e=current(lease,source,target,parsed);val now=clock()
        for(raw in setCookies.take(32)) {
            if(raw.length>2048||raw.any {it=='\r'||it=='\n'||it=='\u0000'})continue
            val cookie=Cookie.parse(parsed,raw) ?: continue
            if(cookie.name.startsWith("__Host-")&&(!cookie.secure||!cookie.hostOnly||cookie.path!="/"))continue
            if(cookie.name.startsWith("__Secure-")&&!cookie.secure)continue
            val key=CookieKey(cookie.name,cookie.domain,cookie.path)
            if(cookie.expiresAt<=now){e.cookies.remove(key);continue}
            if(!e.cookies.containsKey(key)&&e.cookies.size>=8)e.cookies.remove(e.cookies.keys.first())
            e.cookies[key]=cookie
        }
    }
    @Synchronized fun clear(){entries.clear()}
    @Synchronized fun retainedSessions():Int {purge(clock());return entries.size}
}
