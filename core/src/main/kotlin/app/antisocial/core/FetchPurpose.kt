/* anti social, GPL-3.0-or-later. Local policy; no URL leaves the device here. */
package app.antisocial.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class FetchPurpose { SUPPLEMENTAL, DELIBERATE, MEDIA, API }

/** Visiting an action URL can change state even with scripts/cookies disabled.
 * Only supplemental document reads are restricted. The source URL is retained;
 * deliberately opened links, signed media and declared API operations keep
 * their normal gateway/filter/Tor rules. Match path components, not prose. */
object AutomaticLinks {
    private val actions=setOf("unsubscribe","unsub","optout","opt-out","logout","log-out","signout","sign-out","reset-password","reset_password","resetpassword","password-reset","password_reset","confirmsubscription","confirm-subscription","delete-account","delete_account","accept-invite","accept_invite","accept-invitation","redeem")
    private val content=setOf("news","articles","article","blog","docs","guides","guide","help","learn","posts","research","story","stories")
    private val verbs=setOf("unsubscribe","optout","opt-out","logout","reset_password","resetpassword","password_reset","delete_account","accept_invite","accept-invitation","confirm_subscription","confirm","verify","accept","activate","delete","approve","revoke","redeem","reset","purchase","checkout")
    private val tokens=setOf("token","code","key","ticket","signature","sig","auth","authkey","hash","confirmation","invite")
    fun mayRead(value:String):Boolean=value.toHttpUrlOrNull()?.let(::mayRead) ?: false
    fun mayRead(url:HttpUrl):Boolean {
        if(url.username.isNotEmpty()||url.password.isNotEmpty())return false
        val parts=url.pathSegments.flatMap {it.split('/','\\')}.map {it.substringBefore(';').lowercase(java.util.Locale.ROOT).removeSuffix(".php").removeSuffix(".aspx").removeSuffix(".asp")}.filter {it.isNotEmpty()}
        val query=url.queryParameterNames.associateWith {url.queryParameter(it).orEmpty()}
        val signed=query.keys.any {it.lowercase(java.util.Locale.ROOT).let {key->key in tokens||key.endsWith("_token")||key.endsWith("_code")}}
        if(parts.any {it in actions}&&(signed||parts.none {it in content}))return false
        if(parts.none {it in content}&&parts.zipWithNext().any {(owner,action)->
            owner in setOf("account","accounts")&&action in setOf("delete","remove","deactivate")
        })return false
        if(query.keys.any {key->key.lowercase(java.util.Locale.ROOT) in setOf("action","do","operation","cmd")&&url.queryParameterValues(key).any {it?.trim()?.lowercase(java.util.Locale.ROOT) in verbs}})return false
        val confirmations=setOf("confirm","confirmation","verify","activate","activation","accept")
        if(parts.any {it in confirmations}&&parts.none {it in content}&&(signed||parts.size<=2||parts.any {it in setOf("account","accounts","auth","invitation","invitations","invite","login-actions","action-token")}))return false
        if(signed&&(parts.any {it in setOf("confirm","confirmation","verify","activate","activation","accept","invitation","invite","reset")}||parts.takeLast(2)==listOf("password","reset")))return false
        return true
    }
}
