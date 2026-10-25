/* AntiSocial, GPL-3.0-or-later. Reuse the native reader's inert Markdown washing;
 * structured facts are preserved before navigation and page husks are removed. */
package app.antisocial.core

object LocalDocumentWashing {
    /** Preserve announcement headings and date blocks while removing the husk. */
    fun lines(value:String,max:Int=24000):String {
        val page=org.jsoup.Jsoup.parseBodyFragment(value.take(max*2))
        page.select("script,style,noscript,template,iframe,object,embed").remove()
        page.select("br").before("\n")
        page.select("p,div,li,h1,h2,h3,h4").forEach {it.before("\n");it.after("\n")}
        return page.body().wholeText().lineSequence().map {text(it,1000).trim()}.filter(String::isNotBlank).joinToString("\n").take(max)
    }
    fun text(value:String,max:Int=8000):String {
        val plain=org.jsoup.Jsoup.parseBodyFragment(value.take(max*2)).text()
        // Some calendars encode HTML twice. A bounded second inert parse
        // removes visible paragraph/list tags without executing anything.
        return (if(Regex("</?(?:p|div|br|ul|ol|li|strong|em|a)(?:\\s|/?>)",RegexOption.IGNORE_CASE).containsMatchIn(plain))org.jsoup.Jsoup.parseBodyFragment(plain).text()else plain).take(max)
    }
    fun extract(payload:Payload,at:Long,area:LocalArea,business:LocalBusiness?=null,menuSource:Boolean=false,menuBranch:Boolean=false):LocalExtraction {
        require(payload.text.length<=LocalStructuredData.MAX_DOCUMENT)
        HtmlAdmission.check(payload.text)
        // Readability is an in-memory parser. Its Markdown has no executable
        // HTML, widgets, image fetches or browser state. No .md/HTML temporary
        // file is created, retained, or left behind by a background reader.
        val markdown=try {ContextArticles.extract(payload,payload.finalUrl,at).readingText}
        catch(e:SourceFailure){if(e.status in setOf(HealthStatus.LOGIN_REQUIRED,HealthStatus.CHALLENGE))throw e;null}
        val structured=LocalStructuredData.html(payload.text,payload.finalUrl,at,area,business)
        val directory=LocalBusinessSocial.directory(payload.text,payload.finalUrl,at,structured.businesses)
        val contact=if(structured.businesses.none {LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)})LocalBusinessPages.extract(payload.text,payload.finalUrl,markdown,at,area)else LocalExtraction()
        var facts=structured.copy(businesses=LocalDiscoveryMerge.businesses(structured.businesses+directory.businesses+contact.businesses),sources=(structured.sources+directory.sources+contact.sources).distinctBy {it.url}.take(48))
        val subject=business ?: facts.businesses.firstOrNull {LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),it)}
        subject?.let {owner->LocalBusinessFacts.html(payload.text,payload.finalUrl,at,owner,facts.businesses)?.let {extra->
            val existing=facts.businesses.firstOrNull {it.id==extra.id}
            val result=if(existing==null)extra else LocalDiscoveryMerge.business(existing,extra)
            facts=facts.copy(businesses=facts.businesses.filterNot {it.id==result.id}+result)
        }}
        subject?.let {owner->
            val current=facts.businesses.firstOrNull {it.id==owner.id} ?: owner
            val menu=LocalMenuRouting.discover(payload.text,payload.finalUrl,current,facts.businesses,at,menuSource,menuBranch,area)
            if(menu.businesses.isNotEmpty())facts=facts.copy(businesses=facts.businesses.filterNot {it.id==owner.id}+menu.businesses)
            facts=facts.copy(sources=(menu.sources+facts.sources).distinctBy {it.url}.take(48))
        }
        if(markdown.isNullOrBlank()||business==null||!LocalBusinessOwnership.firstParty(business.name,payload.finalUrl))return facts
        // A business's own washed page can fill a missing description. Do not
        // infer event dates, prices, cancellation or locations from prose.
        val description=markdown.lineSequence().filter {it.isNotBlank()&&!it.startsWith('#')}.take(8).joinToString("\n").take(2000)
        if(description.isBlank()||business.description.isNotBlank()||facts.businesses.any {it.id==business.id&&it.description.isNotBlank()})return facts
        // Description fallback must extend the projection already extracted
        // above, not replace its new accounts/contacts with the old subject.
        val current=facts.businesses.firstOrNull {it.id==business.id} ?: business
        val enriched=current.copy(description=description,checkedAt=at,evidence=current.evidence+
            LocalDiscoveryIdentity.evidence("description",description,payload.finalUrl,at,LocalEvidenceKind.PUBLISHER))
        return facts.copy(businesses=facts.businesses.filterNot {it.id==business.id}+enriched)
    }
}
