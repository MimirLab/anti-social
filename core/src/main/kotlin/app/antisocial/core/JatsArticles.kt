/* AntiSocial, GPL-3.0-or-later. Inert bounded scientific full-text extraction.
 * JATS vocabulary: https://jats.nlm.nih.gov/publishing/tag-library/1.4/ */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** No URL loader, stylesheet, DTD or subresource fetch. Production calls run in
 * the existing permissionless, deadline-controlled document worker. */
internal object JatsArticles {
    private const val MAX_INPUT=4*1024*1024
    private const val MAX_ELEMENTS=30_000
    private val pmcid=Regex("PMC[1-9][0-9]{0,11}")
    private val endpointPath=Regex("^/europepmc/webservices/rest/(PMC[1-9][0-9]{0,11})/fullTextXML$")
    private val articlePath=Regex("^/articles/(PMC[1-9][0-9]{0,11})/?$")
    private val legacyPath=Regex("^/pmc/articles/(PMC[1-9][0-9]{0,11})/?$")
    private val space=Regex("[\\s\\p{Z}]+")
    private val identities by lazy {DarwinArticleRoutes()}
    fun endpoint(id:String)="https://www.ebi.ac.uk/europepmc/webservices/rest/$id/fullTextXML".also {require(pmcid.matches(id))}
    fun endpointId(raw:String):String?=raw.toHttpUrlOrNull()?.let(::endpointId)
    private fun endpointId(u:okhttp3.HttpUrl):String? {
        if(!u.isHttps||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty()||u.host!="www.ebi.ac.uk"||u.query!=null)return null
        return endpointPath.matchEntire(u.encodedPath)?.groupValues?.get(1)
    }
    fun pageId(raw:String):String? {
        val u=raw.toHttpUrlOrNull() ?: return null
        endpointId(u)?.let {return it}
        if(!u.isHttps||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty()||u.query!=null)return null
        val pattern=when(u.host) {
            "pmc.ncbi.nlm.nih.gov","europepmc.org","www.europepmc.org"->articlePath
            "www.ncbi.nlm.nih.gov"->legacyPath
            else->return null
        }
        return pattern.matchEntire(u.encodedPath)?.groupValues?.get(1)
    }
    private fun fail(message:String,status:HealthStatus=HealthStatus.UNSUPPORTED):Nothing=throw SourceFailure(status,message)
    private fun direct(node:Element,tag:String)=node.children().filter {it.normalName()==tag}
    private fun one(node:Element,tag:String)=direct(node,tag).singleOrNull()
    private fun plain(node:Element?)=node?.text()?.replace(space," ")?.trim().orEmpty()

    /** Entity declarations are rejected before invoking a platform parser.
     * Literal examples in comments, processing instructions and CDATA survive. */
    private fun admit(raw:String) {
        if(raw.length>MAX_INPUT||'\u0000' in raw)fail("This structured article exceeds its input allowance")
        var at=0
        while(true) {
            val next=raw.indexOf('<',at);if(next<0)break
            val (prefix,end)=when {
                raw.startsWith("<!--",next)->"<!--" to "-->"
                raw.startsWith("<![CDATA[",next)->"<![CDATA[" to "]]>"
                raw.startsWith("<?",next)->"<?" to "?>"
                else->"" to ""
            }
            if(prefix.isNotEmpty()) {
                val finish=raw.indexOf(end,next+prefix.length)
                if(finish<0)fail("The structured article is incomplete")
                at=finish+end.length
            }else {
                if(raw.regionMatches(next,"<!ENTITY",0,8,true))fail("Entity declarations are not accepted in structured articles")
                at=next+1
            }
        }
    }
    private fun document(raw:String):Element {
        admit(raw)
        val container=Element("jats-input");val stack=ArrayList<Element>().apply {add(container)}
        var elements=0;var characters=0
        val handler=object:DefaultHandler() {
            override fun resolveEntity(publicId:String?,systemId:String?)=InputSource(StringReader(""))
            override fun skippedEntity(name:String){throw SAXException("An undeclared entity cannot supply article text")}
            override fun error(e:org.xml.sax.SAXParseException){throw e}
            override fun fatalError(e:org.xml.sax.SAXParseException){throw e}
            override fun startElement(uri:String?,localName:String?,qName:String?,attributes:Attributes) {
                val name=localName?.takeIf {it.isNotEmpty()} ?: qName.orEmpty().substringAfter(':')
                if(++elements>MAX_ELEMENTS||stack.size>96||name.length !in 1..128||attributes.length>64)fail("This structured article exceeds its document allowance")
                val element=Element(name.lowercase(Locale.ROOT))
                for(i in 0 until attributes.length) {
                    val key=attributes.getLocalName(i).ifBlank {attributes.getQName(i)}.lowercase(Locale.ROOT)
                    val value=attributes.getValue(i)
                    if(key.length>128||value.length>8192)fail("This structured article exceeds its attribute allowance")
                    if(attributes.getURI(i).isNullOrEmpty()||attributes.getURI(i)=="http://www.w3.org/1999/xlink"||attributes.getURI(i)=="http://www.w3.org/XML/1998/namespace")element.attr(key,value)
                }
                stack.last().appendChild(element);stack.add(element)
            }
            override fun endElement(uri:String?,localName:String?,qName:String?){stack.removeAt(stack.lastIndex)}
            override fun characters(ch:CharArray,start:Int,length:Int) {
                characters+=length;if(characters>MAX_INPUT)fail("This structured article exceeds its text allowance")
                if(length>0)stack.last().appendChild(TextNode(String(ch,start,length)))
            }
        }
        try {
            val factory=SAXParserFactory.newInstance().apply {isNamespaceAware=true;isValidating=false}
            val reader=factory.newSAXParser().xmlReader
            // Android and JVM expose different optional JAXP features. The
            // mandatory resolver never opens a resource; the declaration scan
            // and event bounds do not depend on an optional feature existing.
            for(feature in listOf("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities","http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try {reader.setFeature(feature,false)}catch(_:org.xml.sax.SAXNotRecognizedException){}catch(_:org.xml.sax.SAXNotSupportedException){}
            }
            reader.entityResolver=handler;reader.contentHandler=handler;reader.errorHandler=handler
            reader.parse(InputSource(StringReader(raw)))
        }catch(e:SourceFailure){throw e}catch(e:Exception){fail("The source did not supply complete structured article XML",HealthStatus.EMPTY)}
        return container.children().singleOrNull()?.takeIf {it.normalName()=="article"} ?: fail("The structured source supplied no article",HealthStatus.EMPTY)
    }
    private fun link(raw:String,base:String):String? {
        if(raw.isBlank()||raw.length>4096||raw.any {it.isISOControl()})return null
        val u=base.toHttpUrlOrNull()?.resolve(raw) ?: return null
        return u.takeIf {it.scheme in setOf("https","http")&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    }
    private fun date(meta:Element):Long?=direct(meta,"pub-date").sortedBy {if(it.attr("publication-format")=="electronic"||it.attr("pub-type")=="epub")0 else 1}.firstNotNullOfOrNull {node->runCatching {
        val iso=node.attr("iso-8601-date").takeIf {it.isNotBlank()}
        val date=if(iso!=null)LocalDate.parse(iso.take(10))else LocalDate.of(one(node,"year")?.text()?.toIntOrNull() ?: return@runCatching null,
            one(node,"month")?.text()?.toIntOrNull() ?: return@runCatching null,one(node,"day")?.text()?.toIntOrNull() ?: return@runCatching null)
        date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()}
    private fun math(node:Element):String {
        node.selectFirst("tex-math")?.let {return it.wholeText().trim()}
        val children=node.children()
        fun part(i:Int)=children.getOrNull(i)?.let(::math).orEmpty()
        return when(node.normalName()) {
            "mfrac"->"(${part(0)})/(${part(1)})"
            "msup"->"(${part(0)})^(${part(1)})"
            "msub"->"(${part(0)})_(${part(1)})"
            "msubsup"->"(${part(0)})_(${part(1)})^(${part(2)})"
            "msqrt"->"sqrt("+children.joinToString(""){math(it)}+")"
            "mroot"->"root(${part(0)}, ${part(1)})"
            "mfenced"->node.attr("open").ifBlank {"("}+children.joinToString(node.attr("separators").ifBlank {","}){math(it)}+node.attr("close").ifBlank {")"}
            "mtable"->"["+children.joinToString("; "){math(it)}+"]"
            "mtr"->children.joinToString(" | "){math(it)}
            "semantics","alternatives"->children.firstOrNull {it.normalName() !in setOf("annotation","annotation-xml","graphic")}?.let(::math).orEmpty()
            else->if(children.isEmpty())node.wholeText().trim()else node.childNodes().joinToString(""){if(it is Element)math(it)else if(it is TextNode)it.wholeText else ""}
        }
    }
    fun extract(payload:Payload,original:String,at:Long):ContextArticleEvidence {
        val id=endpointId(payload.finalUrl) ?: fail("This structured article has no recognized retrieval identity")
        if(payload.status !in 200..299)fail("The structured article source was unavailable",HealthStatus.UNAVAILABLE)
        val source=document(payload.text)
        val front=one(source,"front") ?: fail("The structured article supplied no unique front matter",HealthStatus.EMPTY)
        val meta=one(front,"article-meta") ?: fail("The structured article supplied no unique article metadata",HealthStatus.EMPTY)
        val declaredIds=direct(meta,"article-id")
        fun ids(kind:String)=declaredIds.filter {it.attr("pub-id-type")==kind}.map {plain(it)}.distinct()
        val pmcs=(ids("pmcid")+ids("pmc")).map {if(it.startsWith("PMC"))it else "PMC$it"}.distinct()
        if(pmcs!=listOf(id))fail("The structured copy did not identify the requested article",HealthStatus.EMPTY)
        val doiDeclarations=ids("doi")
        val dois=doiDeclarations.map {DarwinArticleRoutes.normalizeDoi(it) ?: fail("The structured article has an invalid publication identifier",HealthStatus.EMPTY)}.distinct()
        if(dois.size>1)fail("The structured article has conflicting publication identifiers",HealthStatus.EMPTY)
        val doi=dois.singleOrNull();val expected=identities.scientific(original)
        if(expected?.doi!=null&&doi!=expected.doi||expected?.pmid!=null&&ids("pmid")!=listOf(expected.pmid)||pageId(original)?.let {it!=id}==true)
            fail("The structured copy did not match the original publication",HealthStatus.EMPTY)
        val body=one(source,"body") ?: fail("The structured source supplied only metadata or an abstract",HealthStatus.EMPTY)
        if(plain(body).length<160)fail("The structured source supplied no substantial article body",HealthStatus.EMPTY)
        val title=plain(one(meta,"title-group")?.let {one(it,"article-title")}).take(1000)
        if(title.isBlank())fail("The structured article supplied no title",HealthStatus.EMPTY)
        val authors=meta.select("contrib[contrib-type=author]").take(100).mapNotNull {person->
            one(person,"name")?.let {name->listOf(plain(one(name,"given-names")),plain(one(name,"surname"))).filter {it.isNotBlank()}.joinToString(" ")}
                ?: one(person,"collab")?.let(::plain)
        }.filter {it.isNotBlank()}.distinct().joinToString(", ").take(300).takeIf {it.isNotBlank()}
        val journal=one(front,"journal-meta")?.let {one(it,"journal-title-group")?.let {group->one(group,"journal-title")} ?: one(it,"journal-title")}
        val base="https://pmc.ncbi.nlm.nih.gov/articles/$id/"
        val rendered=Element("article",base);rendered.appendElement("h1").text(title)
        val refGroups=source.select("ref-list ref[id]").filter {ref->
            ref.parents().any {it.normalName() in setOf("body","back")&&it.parent()==source}&&
                ref.parents().none {it.normalName() in setOf("sub-article","response")}
        }.groupBy {it.id()}
        val refs=refGroups.filterValues {it.size==1}.mapValues {it.value.single()}
        fun external(node:Element):String? {
            val value=node.attr("href").ifBlank {node.text()}.trim()
            return when(node.attr("ext-link-type")) {
                "doi"->DarwinArticleRoutes.normalizeDoi(value)?.let {"https://doi.org/$it"}
                "pmcid"->value.takeIf {pmcid.matches(it)}?.let {"https://pmc.ncbi.nlm.nih.gov/articles/$it/"}
                "pmid"->value.takeIf {it.matches(Regex("[1-9][0-9]{0,8}"))}?.let {"https://pubmed.ncbi.nlm.nih.gov/$it/"}
                "google-scholar"->null // A citation search string is not a URL.
                else->link(value,base)
            }
        }
        fun referenceUrls(ref:Element):List<String> {
            val doiUrls=ref.select("pub-id[pub-id-type=doi]").mapNotNull {DarwinArticleRoutes.normalizeDoi(it.text())?.takeIf {value->value!=doi}?.let {value->"https://doi.org/$value"}}
            return (doiUrls+ref.select("ext-link[href]").mapNotNull(::external)).filterNot {it=="https://doi.org/$doi"}.distinct().take(8)
        }
        val citationUrls=refs.mapValues {referenceUrls(it.value)}
        fun inline(node:Element,target:Element) {
            for(child in node.childNodes())when(child) {
                is TextNode->target.appendChild(TextNode(child.wholeText))
                is Element->when(child.normalName()) {
                    "script","style","object","iframe","email"->if(child.normalName()=="email")target.appendText(child.text())else Unit
                    "xref"->{
                        val urls=if(child.attr("ref-type")=="bibr")child.attr("rid").split(Regex("\\s+")).flatMap {citationUrls[it].orEmpty()}.distinct()else listOf(base+"#"+child.attr("rid")).takeIf {child.attr("rid").matches(Regex("[A-Za-z0-9_.:-]{1,200}"))}.orEmpty()
                        if(urls.isEmpty())target.appendText(child.text())else {target.appendElement("a").attr("href",urls.first()).attr("rel","cite").text(child.text());urls.drop(1).forEach {target.appendElement("a").attr("href",it).attr("rel","cite")}}
                    }
                    "ext-link","uri"->{val url=external(child);if(url==null)inline(child,target)else inline(child,target.appendElement("a").attr("href",url))}
                    "inline-formula","math","tex-math"->target.appendText(math(child))
                    "sup"->target.appendText("^("+plain(child)+")")
                    "sub"->target.appendText("_("+plain(child)+")")
                    "break"->target.appendText("\n")
                    "name","string-name"->{
                        val name=listOf(plain(one(child,"given-names")),plain(one(child,"surname"))).filter {it.isNotBlank()}.joinToString(" ")
                        target.appendText(if(name.isNotEmpty())name else plain(child))
                    }
                    "title","p","label","person-group","article-title","source","year","volume","issue","fpage","lpage","pub-id"->{
                        target.appendText(" ");inline(child,target);target.appendText(" ")
                    }
                    else->inline(child,target)
                }
            }
        }
        fun render(node:Element,target:Element,level:Int=2) {
            when(node.normalName()) {
                "p"->inline(node,target.appendElement("p"))
                "sec","app","ack","boxed-text","abstract","fn"->{
                    val heading=one(node,"title")?.let(::plain) ?: if(node.normalName()=="abstract")"Abstract"else ""
                    if(heading.isNotBlank())target.appendElement("h${level.coerceIn(2,6)}").text(heading)
                    direct(node,"label").forEach {target.appendElement("p").text(plain(it))}
                    node.children().filter {it.normalName() !in setOf("title","label")}.forEach {render(it,target,level+1)}
                }
                "list"->{val ordered=node.attr("list-type") in setOf("order","ordered")&&node.select("list-item > label").isEmpty();val list=target.appendElement(if(ordered)"ol"else "ul");node.children().forEach {render(it,list,level)}}
                "list-item"->{val item=target.appendElement("li");node.children().forEach {if(it.normalName()=="label")item.appendText(it.text()+" ")else render(it,item,level)}}
                "disp-quote"->{val quote=target.appendElement("blockquote");node.children().forEach {render(it,quote,level)}}
                "preformat","code"->target.appendElement("pre").text(node.wholeText())
                "disp-formula"->target.appendElement("pre").text(listOf(plain(one(node,"label")),node.children().firstOrNull {it.normalName() in setOf("math","tex-math","alternatives")}?.let(::math).orEmpty()).filter {it.isNotBlank()}.joinToString(" "))
                "fig","table-wrap","supplementary-material","media"->{
                    val media=direct(node,"media")
                    val caption=target.appendElement("figcaption");val label=plain(one(node,"label") ?: media.firstNotNullOfOrNull {one(it,"label")});if(label.isNotBlank())caption.appendText(label+". ")
                    (direct(node,"caption")+media.flatMap {direct(it,"caption")}).forEach {inline(it,caption);caption.appendText(" ")}
                    if(node.normalName()=="table-wrap"&&node.select("table").isEmpty())caption.appendText(" [Table image available at the source.]")
                    if(node.id().matches(Regex("[A-Za-z0-9_.:-]{1,200}")))caption.appendElement("a").attr("href",base+"#"+node.id())
                    direct(node,"table").forEach {render(it,target,level)}
                    direct(node,"table-wrap-foot").forEach {render(it,target,level)}
                    node.children().filter {it.normalName() in setOf("p","list","disp-formula","alt-text")}.forEach {render(it,target,level)}
                }
                "table","thead","tbody","tfoot","tr","th","td"->{val table=target.appendElement(node.normalName());listOf("colspan","rowspan").forEach {if(node.hasAttr(it))table.attr(it,node.attr(it))};if(node.normalName() in setOf("td","th"))inline(node,table)else node.children().forEach {render(it,table,level)}}
                "ref-list"->{target.appendElement("h${level.coerceIn(2,6)}").text(plain(one(node,"title")).ifBlank {"References"});node.children().filter {it.normalName()!="title"}.forEach {render(it,target,level+1)}}
                "ref"->{val line=target.appendElement("p");inline(node,line);citationUrls[node.id()].orEmpty().forEach {line.appendElement("a").attr("href",it).attr("rel","cite")}}
                "alt-text","label","title"->target.appendElement("p").text(plain(node))
                "graphic","inline-graphic","front","sub-article","response","permissions","script","style","object","iframe"->Unit
                else->if(node.ownText().isNotBlank())inline(node,target.appendElement("p"))else node.children().forEach {render(it,target,level)}
            }
        }
        direct(meta,"abstract").forEach {render(it,rendered)}
        body.children().forEach {render(it,rendered)}
        direct(source,"back").forEach {render(it,rendered)}
        direct(source,"floats-group").forEach {render(it,rendered)}
        if(rendered.allElements.size>MAX_ELEMENTS)fail("This structured article exceeds its reading allowance")
        val (blocks,limited)=ContextArticles.structuredBlocks(rendered)
        val allowed=citationUrls.values.flatten().toSet()
        val references=SignalDiscovery.references(rendered,base).filter {it.url in allowed}.take(12)
        val license=meta.select("permissions license").mapNotNull {node->link(node.attr("href"),base)?.takeIf {node.hasAttr("href")} ?: node.select("ext-link[href]").firstNotNullOfOrNull {link(it.attr("href"),base)}}.firstOrNull()
        return ContextArticleEvidence(original,payload.finalUrl,title,authors,plain(journal).take(200).takeIf {it.isNotBlank()},date(meta),blocks,at,
            truncated=limited||direct(source,"sub-article").isNotEmpty()||direct(source,"response").isNotEmpty(),
            access=ArticleAccess("Europe PMC full text",original,payload.finalUrl,license=license),references=references,publicationDoi=doi)
    }
}
