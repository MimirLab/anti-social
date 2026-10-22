/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface IncomingLinkResult {
    data class Open(val target: Target): IncomingLinkResult
    data class External(val url: String): IncomingLinkResult
    data class Unavailable(val message: String): IncomingLinkResult
}

/** Classifies before any network access. Only the injected gateway resolver may
 * expand a known short link, once; ordinary unselected links stay in the browser.
 */
class IncomingLinks(
    private val routes: DarwinRoutes,
    private val cleaner: UrlCleaner,
    private val resolveUrl: suspend (String) -> String
) {
    suspend fun resolve(input: String,selected: Set<Platform>,explicit: Boolean=false,hint: Platform?=null,allowOriginalResolution: Boolean=true): IncomingLinkResult {
        currentCoroutineContext().ensureActive()
        if(publicUrl(input.trim())==null)return IncomingLinkResult.Unavailable("Enter a public HTTP or HTTPS link without credentials")
        val clean=cleaner.clean(input.trim())
        if(publicUrl(clean)==null)return IncomingLinkResult.Unavailable("This link does not point to a public destination")
        val initial=classify(clean,hint)
        val platform=initial.first ?: return IncomingLinkResult.External(clean)
        if(login(initial.second))return IncomingLinkResult.Unavailable("This link opens a sign-in page, not public content")
        if(platform==Platform.YOUTUBE&&initial.third?.let(ReaderScope::canOpen)!=true)return IncomingLinkResult.Unavailable(ReaderScope.VIDEO_ONLY)
        if(!explicit&&platform!=Platform.YOUTUBE&&platform !in selected)return IncomingLinkResult.External(initial.second)
        if(!SocialLinkHosts.needsExpansion(initial.second))return result(initial.second,platform,initial.third,selected,explicit)
        if(!allowOriginalResolution)return IncomingLinkResult.Unavailable("Resolving this short link requires public original access")
        val expanded=try {
            resolveUrl(initial.second)
        }catch(e: CancellationException){throw e}
        catch(e: SourceFailure){throw e}
        catch(e: IOException){throw SourceFailure(HealthStatus.UNAVAILABLE,"This public social link could not be resolved")}
        currentCoroutineContext().ensureActive()
        if(publicUrl(expanded)==null)throw SourceFailure(HealthStatus.UNAVAILABLE,"The social link resolved to an unsafe destination")
        val destination=cleaner.clean(expanded)
        if(publicUrl(destination)==null)throw SourceFailure(HealthStatus.UNAVAILABLE,"The social link resolved to an unsafe destination")
        val found=classify(destination,hint)
        if(found.first!=null&&login(found.second)) {
            if(found.first==platform)continuation(found.second,platform)?.let {return result(it.canonicalUrl,platform,it,selected,explicit)}
            throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"The social link resolved to a sign-in page")
        }
        if(SocialLinkHosts.needsExpansion(found.second))throw SourceFailure(HealthStatus.UNAVAILABLE,"The short link did not resolve to public content")
        return result(found.second,found.first,found.third,selected,explicit)
    }

    /** A login redirect may disclose the requested permalink in one URL field.
     * Extract its identity locally, never fetch that continuation or accept the
     * login page as readable content. No nested redirects or token expansion.
     */
    private fun continuation(loginUrl: String,platform: Platform): Target? {
        val url=loginUrl.toHttpUrlOrNull() ?: return null
        val fields=url.queryParameterNames.filter {it in setOf("next","continue","return_to")}
        if(fields.size!=1)return null
        val values=url.queryParameterValues(fields.single())
        if(values.size!=1)return null
        val value=values.single()?.takeIf {it.length in 1..8192} ?: return null
        if(publicUrl(value)==null||SocialLinkHosts.platform(value)!=platform)return null
        val cleaned=cleaner.clean(value)
        if(publicUrl(cleaned)==null||SocialLinkHosts.needsExpansion(cleaned)||login(cleaned))return null
        val found=classify(cleaned,null)
        val target=found.third ?: return null
        return target.takeIf {found.first==platform&&it.platform==platform&&it.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&
            ContentIdentity.key(it.platform,it.canonicalUrl).startsWith("post:")}
    }

    private fun classify(url: String,hint: Platform?): Triple<Platform?,String,Target?> {
        val declared=SocialLinkHosts.platform(url)
        val canonical=SocialLinkHosts.canonical(url) ?: url
        val target=routes.recognize(canonical,declared ?: hint)
        // The host registry cannot turn another site's content into this platform.
        val verified=target?.takeIf {declared==null||it.platform==declared}
        return Triple(declared ?: verified?.platform,canonical,verified)
    }
    private fun result(url: String,platform: Platform?,target: Target?,selected: Set<Platform>,explicit: Boolean): IncomingLinkResult {
        if(platform==Platform.YOUTUBE&&target?.let(ReaderScope::canOpen)!=true)return IncomingLinkResult.Unavailable(ReaderScope.VIDEO_ONLY)
        if(platform==null||!explicit&&platform!=Platform.YOUTUBE&&platform !in selected)return IncomingLinkResult.External(url)
        if(target==null||target.platform!=platform)return IncomingLinkResult.External(url)
        if(publicUrl(target.canonicalUrl)==null)return IncomingLinkResult.Unavailable("This link does not identify public content")
        if(target.kind==TargetKind.PROFILE&&!PublicSearchAdapter.profileAllowed(target))return IncomingLinkResult.External(url)
        if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&!ContentIdentity.key(target.platform,target.canonicalUrl).let {it.startsWith("post:")||it.startsWith("comment:")})return IncomingLinkResult.External(url)
        if(target.kind==TargetKind.SEARCH)try {SearchScopes.from(target)}catch(_: IllegalArgumentException){return IncomingLinkResult.Unavailable("This link contains an invalid search scope")}
        return IncomingLinkResult.Open(target)
    }
    private fun login(url: String): Boolean {
        val parts=runCatching {URI(url).path.orEmpty().trim('/').lowercase(Locale.ROOT).split('/')}.getOrDefault(emptyList())
        return parts.firstOrNull() in setOf("login","login.php","signin","sign-in","signup","sign-up","register","auth","authwall","oauth","oauth2","accounts","checkpoint","challenge","logout","session","sessions","consent")||
            parts.take(3)==listOf("i","flow","login")||parts.take(2)==listOf("account","access")||parts.take(2)==listOf("dialog","oauth")
    }
    /** No DNS is performed here. Gateway validates DNS answers on every actual
     * request; literal/private/local inputs are rejected before even that step. */
    private fun publicUrl(value: String): HttpUrl? {
        if(value.isBlank()||value.length>16*1024||value.any {it<=' '||it=='\u007f'||it=='\\'})return null
        val uri=runCatching {URI(value)}.getOrNull() ?: return null
        if(uri.scheme?.lowercase(Locale.ROOT) !in setOf("http","https")||uri.host==null||uri.rawUserInfo!=null)return null
        val url=value.toHttpUrlOrNull()?.takeIf {it.username.isEmpty()&&it.password.isEmpty()&&it.port in 1..65535} ?: return null
        val host=url.host.lowercase(Locale.ROOT).trimEnd('.')
        if(host=="localhost"||listOf(".localhost",".local",".internal",".lan",".home").any(host::endsWith))return null
        val literal=when {
            ':' in host->runCatching {InetAddress.getByName(host)}.getOrNull() ?: return null // Validated IPv6 literal, never a hostname lookup.
            host.matches(Regex("[0-9.]+"))-> {
                val octets=host.split('.')
                if(octets.size!=4||octets.any {it.length !in 1..3||it.length>1&&it.startsWith('0')||it.toIntOrNull() !in 0..255})return null
                val parts=octets.map(String::toInt)
                if(parts[0]==0||parts[0]>=224||parts[0]==100&&parts[1] in 64..127||parts[0]==198&&parts[1] in 18..19)return null
                InetAddress.getByAddress(parts.map(Int::toByte).toByteArray())
            }
            else->{if('.' !in host)return null;null}
        }
        if(literal!=null&&(literal.isAnyLocalAddress||literal.isLoopbackAddress||literal.isLinkLocalAddress||literal.isSiteLocalAddress||literal.isMulticastAddress||host.startsWith("fc",true)||host.startsWith("fd",true)))return null
        return url
    }
}
