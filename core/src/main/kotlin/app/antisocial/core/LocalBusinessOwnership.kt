/* AntiSocial, GPL-3.0-or-later. Identity and access are separate decisions. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalBusinessOwnership {
    private val generic=setOf("the","co","company","inc","llc","ltd","restaurant","park","trampoline","store","shop")
    private fun significant(value:String)=LocalDiscoveryIdentity.words(value.replace(Regex("['’]"),"")).filterNot(generic::contains)
    fun sameBrand(name:String,other:String):Boolean {
        val a=significant(name);val b=significant(other)
        if(a.isEmpty()||b.isEmpty())return false
        return a==b||a.size>=2&&a.all {it in b}||b.size>=2&&b.all {it in a}||a.size==1&&b.size==1&&a[0].length>=5&&a==b
    }
    fun firstParty(name:String,url:String,siteNames:List<String> = emptyList()):Boolean {
        if(siteNames.any {sameBrand(name,it.substringBefore('|'))})return true
        val host=url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return false
        val compact=significant(name).joinToString("")
        val branded=compact.length>=5&&host.split('.').any {label->
            val letters=label.filter(Char::isLetter)
            letters==compact||listOf("company","llc","inc","ltd").any {letters.removeSuffix(it)==compact}
        }
        if(!branded)return false
        // A publisher may shorten its own long name in its site title. Require
        // both the matching domain and a substantial compatible brand token;
        // a directory's unrelated title cannot claim this relationship.
        return siteNames.isEmpty()||siteNames.any {site->val words=significant(site.substringBefore('|'));words.joinToString("")==compact||words.isNotEmpty()&&words.all {it in significant(name)}&&words.any {it.length>=6}}
    }
    fun directoryLink(business:LocalBusiness,url:String):Boolean {
        val host=url.toHttpUrlOrNull()?.host ?: return false
        return business.evidence.any {it.kind==LocalEvidenceKind.DIRECTORY&&it.sourceUrl.toHttpUrlOrNull()?.host==host}||
            business.related.orEmpty().any {it.role=="source publisher"&&it.sourceUrl.toHttpUrlOrNull()?.host==host}
    }
    /** 0.35.37's footer-only evidence is recognizable. Preserve it as related
     * source information, while removing its false business-account association. */
    fun repairLegacy(business:LocalBusiness):LocalBusiness {
        val fields=business.evidence.filter {it.field=="socials"&&it.kind==LocalEvidenceKind.PUBLISHER&&!firstParty(business.name,it.sourceUrl)}
        if(fields.isEmpty())return business
        val moved=fields.flatMap {it.value.split(" · ")}.mapNotNull {LocalDiscoveryIdentity.url(it)}.toSet().intersect(business.socials.toSet())
        if(moved.isEmpty())return business
        val related=fields.mapNotNull {field->
            val accounts=field.value.split(" · ").filter {it in moved};if(accounts.isEmpty())null else LocalRelatedEntity(field.sourceUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "Source website","source publisher",accounts,field.sourceUrl)
        }
        return business.copy(socials=business.socials.filterNot(moved::contains),related=(business.related.orEmpty()+related).distinct().take(16),
            evidence=business.evidence.map {if(it in fields)it.copy(field="source publisher accounts")else it})
    }
}
