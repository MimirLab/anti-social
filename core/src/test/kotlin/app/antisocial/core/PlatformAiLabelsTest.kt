/* AntiSocial, GPL-3.0-or-later. Synthetic protocol fixtures; no external requests. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PlatformAiLabelsTest {
    private val code="CExampleAB1"
    private val pk="2391809882311884917"
    private val otherCode="CAnotherAB2"
    private val otherPk="2316999583490375798"
    private val url="https://www.instagram.com/p/$code/"
    private val target=Target(Platform.INSTAGRAM,TargetKind.POST,code,url)
    private val source=Source(Platform.INSTAGRAM,"original",Platform.INSTAGRAM.origin,role=SourceRole.ORIGINAL)
    private val provenance=Provenance(source,1234,url)
    private val post=Post("original-id",Platform.INSTAGRAM,url,"fixture","A fictional post about public gardens.","Original title",media=listOf(Media("https://media.example/garden.jpg")),provenance=provenance)
    private fun quote(value: String)=com.google.gson.Gson().toJson(value)
    private fun media(label: String?="AI info",code: String=this.code,pk: String=this.pk,extra: String="",field: String="")="""{"__typename":"XIGPolarisImageMedia","pk":"$pk","code":"$code","id":"POLARIS_$pk","caption":{"text":"A fictional post about public gardens."},"ai_label_info":{"attribution_label_text":${label?.let(::quote) ?: "null"}}${if(extra.isBlank())""else ",$extra"}${if(field.isBlank())""else ",$field"}}"""
    private fun envelope(media: String=media(),code: String=this.code,pk: String=this.pk)="""{"data":{"xig_polaris_media":{"pk":"$pk","code":"$code","id":"POLARIS_$pk","if_not_gated_logged_out":$media}}}"""
    private fun relay(value: String)="""{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"require":[["RelayPrefetchedStreamCache","next",[],["fixture",{"__bbox":{"result":$value}}]]]}}]]]}"""
    private fun html(json: String,canonical: String=url,type: String="application/json")="""<html><head><link rel="canonical" href="$canonical"><meta property="og:url" content="$canonical"><meta property="og:type" content="instapp:photo"><meta property="og:title" content="A fictional garden"><meta property="og:description" content="A fictional post about public gardens."><meta property="og:image" content="https://media.example/garden.jpg"></head><body><script type="$type">$json</script></body></html>"""
    private fun apply(text: String,posts: List<Post> = listOf(post),target: Target=this.target,provenance: Provenance=this.provenance,finalUrl: String=url): List<Post> =
        PlatformAiLabels.attach(posts,Payload(text,finalUrl,if(text.startsWith("<"))"text/html"else "application/json",200),target,provenance)

    @Test fun observedRelayEnvelopeBindsKnownLabelToSelectedRootOnly() {
        val comment=post.copy(id="comment",parentId=post.id)
        val foreign=post.copy(id="another",canonicalUrl="https://www.instagram.com/p/$otherCode/")
        val selected=apply(html(relay(envelope())),listOf(post,comment,foreign))
        val disclosure=selected[0].aiDisclosures!!.single()
        assertEquals(AiEvidence.PLATFORM_LABEL,disclosure.evidence)
        assertEquals(AiUse.UNSPECIFIED,disclosure.use)
        assertEquals("AI info",disclosure.label)
        assertNull(disclosure.tool)
        assertNull(disclosure.assetUrl)
        assertFalse(disclosure.attachment)
        assertEquals(post,selected[0].copy(aiDisclosures=null))
        assertEquals(comment,selected[1])
        assertEquals(foreign,selected[2])
    }

    @Test fun legacyMadeWithAiDoesNotClaimThatWholeImageWasGenerated() {
        assertEquals(AiUse.UNSPECIFIED,apply(envelope(media("Made with AI"))).single().aiDisclosures!!.single().use)
        assertEquals(AiUse.GENERATED,apply(envelope(media("Imagined with AI"))).single().aiDisclosures!!.single().use)
    }

    @Test fun literalNullAndAbsentLabelDoNotClaimAiOrMetadataRemoval() {
        val nullRecord=apply(html(relay(envelope(media(null))))).single()
        assertEquals(post,nullRecord)
        assertNull(nullRecord.aiDisclosures)
        for(replacement in listOf("null","{}","{\"attribution_label_text\":null}")) {
            val input=envelope().replace("{\"attribution_label_text\":\"AI info\"}",replacement)
            assertEquals(post,apply(input).single())
        }
    }

    @Test fun captionKeywordsFilenamesAndGenericHtmlLabelsAreNeverEvidence() {
        val input=html(envelope(media(null,extra="\"recommendations\":[${media("Imagined with AI")}],\"comments\":[${media("AI info")}],\"description\":\"AI generated\"")))+
            "<span>AI info</span><img src=\"https://media.example/ai-generated.jpg\">"
        assertEquals(post,apply(input).single())
        assertEquals(post,apply(html(envelope(),type="text/javascript")).single())
        assertEquals(post,apply(html(envelope(),type="application/ld+json")).single())
    }

    @Test fun foreignAndAnonymousRecordsCannotBorrowRequestedPostIdentity() {
        assertEquals(post,apply(envelope(media("AI info",otherCode,otherPk),otherCode,otherPk)).single())
        assertEquals(post,apply(envelope(media("AI info",otherCode,otherPk))).single())
        assertEquals(post,apply(envelope(media()).replace("\"code\":\"$code\",","")).single())
        assertEquals(post,apply(envelope().replace("\"pk\":\"$pk\",","")).single())
    }

    @Test fun mismatchedNumericIdAndCanonicalAreRejected() {
        assertEquals(post,apply(envelope(media(pk=otherPk))).single())
        assertEquals(post,apply(envelope().replace("POLARIS_$pk","POLARIS_$otherPk")).single())
        assertEquals(post,apply(envelope(media(extra="\"seo_canonical_url\":\"https://www.instagram.com/p/$otherCode/\""))).single())
        assertEquals(post,apply(html(envelope(),canonical="https://www.instagram.com/p/$otherCode/")).single())
    }

    @Test fun alternativeReadersForeignOriginsAndNonPostTargetsCannotSupplyPlatformAuthority() {
        val alternate=provenance.copy(source=source.copy(frontend="kittygram",root="https://reader.example",role=SourceRole.ALTERNATE))
        assertEquals(post,apply(envelope(),provenance=alternate).single())
        for(final in listOf("https://reader.example/p/$code/","https://instagram.com.evil.test/p/$code/","https://www.instagram.com/accounts/login/","http://www.instagram.com/p/$code/","https://user@www.instagram.com/p/$code/","https://www.instagram.com:444/p/$code/"))
            assertEquals(post,apply(envelope(),finalUrl=final).single())
        for(kind in listOf(TargetKind.PROFILE,TargetKind.SEARCH,TargetKind.TAG))assertEquals(post,apply(envelope(),target=target.copy(kind=kind)).single())
        assertEquals(post,apply(envelope(),target=target.copy(platform=Platform.X)).single())
    }

    @Test fun transportTraversalDoesNotDescendIntoRecommendationsCommentsOrUserObjects() {
        for(key in listOf("recommendations","comments","related_posts","user","caption","edges","mentions")) {
            val input="""{"data":{"$key":${envelope()}}}"""
            assertEquals(post,apply(input).single())
        }
    }

    @Test fun conflictingOrNullDuplicateResponsesRemainUnclaimedWhileIdenticalCopiesDeduplicate() {
        val first=envelope()
        val different=envelope(media("Imagined with AI"))
        val absent=envelope(media(null))
        assertEquals(post,apply("[$first,$different]").single())
        assertEquals(post,apply("[$first,$absent]").single())
        assertEquals(1,apply("[$first,$first]").single().aiDisclosures!!.size)
        val captured=apply(first).single()
        assertEquals(captured,apply(first,posts=listOf(captured)).single())
    }

    @Test fun unknownLongMalformedAndNonStringLabelsMakeNoClaim() {
        for(label in listOf("AI content from somewhere","Not made with AI","Learn about AI info","Artificial intelligence","AI info – maybe","AI info"+" ".repeat(100),"Informations sur l’IA"))
            assertEquals(post,apply(envelope(media(label))).single())
        for(label in listOf("true","42","{}","[]"))assertEquals(post,apply(envelope().replace("\"AI info\"",label)).single())
        assertEquals(post,apply(envelope().dropLast(1)).single())
        assertEquals("AI info",apply(envelope(media("  AI   INFO  "))).single().aiDisclosures!!.single().label)
    }

    @Test fun pageScriptArrayAndDepthLimitsFailWithoutPartialEvidence() {
        assertEquals(post,apply(html(envelope())+" ".repeat(PlatformAiLabels.MAX_PAGE_CHARS)).single())
        val huge=envelope(media(extra="\"padding\":\"${"x".repeat(PlatformAiLabels.MAX_JSON_CHARS)}\""))
        assertEquals(post,apply(html(huge)).single())
        val deep=(1..30).fold(envelope()) {value,_->"""{"data":$value}"""}
        assertEquals(post,apply("[${envelope()},$deep]").single())
        val array="["+List(257){"null"}.joinToString(",")+"]"
        assertEquals(post,apply("""{"data":[$array,${envelope()}]}""").single())
        val tooMany=(1..97).joinToString(""){"""<script type="application/json">${envelope()}</script>"""}
        assertEquals(post,apply(tooMany).single())
    }

    @Test fun extractionHookPreservesExistingCaptionAndOriginalPayload() {
        val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        val content=html(relay(envelope()))
        val payload=Payload(content,url,"text/html",200)
        val extracted=Extraction.parse(payload,target,source,routes).posts.single {it.canonicalUrl.trimEnd('/')==url.trimEnd('/')}
        assertEquals("A fictional post about public gardens.",extracted.body)
        assertEquals(AiUse.UNSPECIFIED,extracted.aiDisclosures!!.single().use)
        assertEquals(AiEvidence.PLATFORM_LABEL,extracted.aiDisclosures!!.single().evidence)
        assertEquals(content,payload.text)
    }
}
