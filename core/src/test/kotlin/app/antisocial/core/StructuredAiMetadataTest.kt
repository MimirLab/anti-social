/* AntiSocial, GPL-3.0-or-later. Synthetic protocol fixtures; no external requests. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class StructuredAiMetadataTest {
    private val vocabulary="http://cv.iptc.org/newscodes/digitalsourcetype/"
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val target=routes.recognize("https://x.com/fixture/status/123")!!
    private val source=Source(Platform.X,"nitter","https://nitter.example")
    private fun json(body: String)=JsonParser.parseString(body).asJsonObject
    private fun dst(term: String)="\"digitalSourceType\":\"$vocabulary$term\""
    private fun declarations(fields: String)=StructuredAiMetadata.declarations(json("""{"@type":"SocialMediaPosting",$fields}"""))
    private fun page(body: String)=Extraction.parse(Payload(body,"https://nitter.example/fixture/status/123","text/html",200),target,source,routes)
    private fun record(body: String="A factual caption",fields: String="",url: String=target.canonicalUrl)="""{"@type":"SocialMediaPosting","url":"$url","description":"$body"${if(fields.isBlank())""else ",$fields"}}"""
    private fun script(value: String)="""<script type="application/ld+json">$value</script>"""

    @Test fun exactVocabularyTermsYieldDistinctReadableKindsAndPageEvidence() {
        val values=declarations(""""digitalSourceType":["${vocabulary}trainedAlgorithmicMedia",{"@id":"${vocabulary}compositeWithTrainedAlgorithmicMedia"},"${vocabulary}compositeSynthetic"]""")
        assertEquals(listOf(AiUse.GENERATED,AiUse.EDITED,AiUse.COMPOSITE),values.map {it.use})
        assertTrue(values.all {it.evidence==AiEvidence.STRUCTURED_DATA&&it.label=="Page metadata"})
        assertEquals(vocabulary+"trainedAlgorithmicMedia",values.first().sourceType)
        assertNull(values.first().tool)
        assertTrue(values.none {it.attachment})
        assertTrue(values.all {it.assetUrl==null})
    }

    @Test fun onlyExplicitAiTermsAreRecognizedWithoutGuessingFromSoftwareOrProse() {
        for(term in listOf("algorithmicallyEnhanced","computationalCapture","softwareImage","softwareDrawing","digitalCapture","compositeCapture")) {
            assertTrue(declarations(dst(term)+",\"software\":\"Adobe Photoshop AI\",\"description\":\"AI-generated #aigenerated\"").isEmpty())
        }
        for(value in listOf("trainedAlgorithmicMedia","${vocabulary}trainedAlgorithmicMedia?fake=1","https://cv.iptc.org.evil.test/newscodes/digitalsourcetype/trainedAlgorithmicMedia","https://example.org/trainedAlgorithmicMedia")) {
            assertTrue(declarations("\"digitalSourceType\":\"$value\"").isEmpty())
        }
    }

    @Test fun schemaEnumerationUrisKeepGeneratedEditedAndCompositeMeaningsAndAttachmentScope() {
        val terms=listOf("TrainedAlgorithmicMediaDigitalSource","CompositeWithTrainedAlgorithmicMediaDigitalSource","CompositeSyntheticDigitalSource")
        for(scheme in listOf("https","http")) {
            val values=terms.map {"$scheme://schema.org/$it"}
            val parsed=declarations(""""image":[${values.joinToString(",") {uri->"""{"@type":"ImageObject","contentUrl":"https://media.example/declared.jpg","digitalSourceType":{"@id":"$uri"}}"""}}]""")
            assertEquals(listOf(AiUse.GENERATED,AiUse.EDITED,AiUse.COMPOSITE),parsed.map {it.use})
            assertTrue(parsed.all {it.attachment&&it.assetUrl=="https://media.example/declared.jpg"&&it.evidence==AiEvidence.STRUCTURED_DATA})
            assertEquals(values,parsed.map {it.sourceType})
        }
    }

    @Test fun schemaEnumerationLookalikesAndCompactNamesCannotBecomeDeclarationsInAnyMetadataContext() {
        val member="TrainedAlgorithmicMediaDigitalSource"
        for(value in listOf(member,"schema:$member","https://schema.org/$member?claim=1","https://schema.org/$member#label","https://schema.org.evil.test/$member","https://example.org/$member","https://schema.org/${member.lowercase()}")) {
            assertTrue(value,declarations("\"digitalSourceType\":\"$value\"").isEmpty())
        }
        // The Schema.org mapping belongs to structured page metadata. It must
        // not broaden the accepted identities of IPTC or credential properties.
        assertNull(AiMetadataValues.sourceType("https://schema.org/$member"))
    }

    @Test fun schemaEnumerationOnAnotherPostDoesNotLabelTheSelectedNativeCard() {
        // This fake source is not a registered Darwin alias. Give its native
        // card the authentic identity being tested instead of an unknown host.
        val native="""<div class="timeline-item"><a class="tweet-link" href="${target.canonicalUrl}">Post</a><div class="tweet-content">Original native caption</div></div>"""
        val fields="\"digitalSourceType\":\"https://schema.org/TrainedAlgorithmicMediaDigitalSource\""
        val posts=page(native+script("""[${record("Current")},${record("Other",fields,"https://x.com/another/status/456")}]""")).posts
        assertEquals(2,posts.size)
        val current=posts.first {it.canonicalUrl==target.canonicalUrl}
        assertEquals("Original native caption",current.body)
        assertTrue(current.aiDisclosures.isNullOrEmpty())
        assertEquals(AiUse.GENERATED,posts.first {it.canonicalUrl.endsWith("456")}.aiDisclosures!!.single().use)
    }

    @Test fun directlyAttachedTypedMediaCanDeclareAiButOtherRecordsCannot() {
        val declaration="""{"@type":"ImageObject","contentUrl":"https://media.example/photo.jpg","url":"https://example.org/containing-page","digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"}"""
        for(field in listOf("image","video","audio","associatedMedia")) {
            val parsed=declarations(""""$field":$declaration""").single()
            assertEquals(AiUse.GENERATED,parsed.use)
            assertTrue(parsed.attachment)
            assertEquals("https://media.example/photo.jpg",parsed.assetUrl)
        }
        for(field in listOf("author","logo","isBasedOn","mentions","comment","hasPart","itemListElement","thumbnail"))assertTrue(declarations(""""$field":$declaration""").isEmpty())
        assertTrue(declarations(""""image":{"digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"}""").isEmpty())
        assertTrue(StructuredAiMetadata.declarations(json("""{"@type":"Organization","digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"}""")).isEmpty())
    }

    @Test fun schemaArraysAndFullyQualifiedNamesAreAcceptedWithinBounds() {
        val node=json("""{"@type":["Thing","https://schema.org/SocialMediaPosting"],"https://schema.org/digitalSourceType":"https://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"}""")
        assertEquals(AiUse.GENERATED,StructuredAiMetadata.declarations(node).single().use)
        node.addProperty("unrelated","AI generated")
        repeat(100) {node.addProperty("unrelated$it","x".repeat(200))}
        assertEquals(1,StructuredAiMetadata.declarations(node).size)
        val cyclicShape=JsonObject().apply {addProperty("@type","ImageObject");addProperty("digitalSourceType",vocabulary+"trainedAlgorithmicMedia")}
        // A deep JSON-shaped attachment chain is bounded; untrusted page input
        // cannot ask the helper to traverse the entire structured document.
        var child=cyclicShape
        repeat(20) {child=JsonObject().apply {addProperty("@type","ImageObject");add("image",child)}}
        assertTrue(StructuredAiMetadata.declarations(child).isEmpty())
    }

    @Test fun galleryDeclarationsKeepTheirIndividualAttachmentInsteadOfLabelingEveryImage() {
        val first="https://media.example/first.jpg"
        val ordinary="https://media.example/ordinary.jpg"
        val edited="https://media.example/edited.jpg"
        val record=json("""{"@type":"SocialMediaPosting","url":"${target.canonicalUrl}","image":[
            {"@type":"ImageObject","contentUrl":"$first","digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"},
            {"@type":"ImageObject","contentUrl":"$ordinary"},
            {"@type":"ImageObject","contentUrl":"$edited","digitalSourceType":"${vocabulary}compositeWithTrainedAlgorithmicMedia"}
        ]}""")
        val parsed=StructuredAiMetadata.declarations(record)
        assertEquals(listOf(first,edited),parsed.map {it.assetUrl})
        assertEquals(listOf(AiUse.GENERATED,AiUse.EDITED),parsed.map {it.use})
        assertTrue(parsed.all {it.attachment})
        assertTrue(parsed.none {it.assetUrl==ordinary||it.assetUrl==target.canonicalUrl})
    }

    @Test fun mediaRootAndAttachmentUrlFallbacksRemainScopedAndRequireSafeAbsoluteHttps() {
        for(key in listOf("contentUrl","url","@id")) {
            val record=json("""{"@type":"ImageObject","$key":"https://media.example/declared.jpg",${dst("trainedAlgorithmicMedia")}}""")
            val d=StructuredAiMetadata.declarations(record).single()
            assertTrue(d.attachment)
            assertEquals("https://media.example/declared.jpg",d.assetUrl)
        }
        for(unsafe in listOf("javascript:alert(1)","http://media.example/photo.jpg","https://user:password@media.example/photo.jpg","/relative.jpg")) {
            val record=json("""{"@type":"ImageObject","contentUrl":"$unsafe",${dst("trainedAlgorithmicMedia")}}""")
            val d=StructuredAiMetadata.declarations(record).single()
            assertTrue(d.attachment)
            assertNull(d.assetUrl)
        }
    }

    @Test fun nativeCardRetainsItsContentWhileExactJsonLdAddsOnlyItsDeclaration() {
        val native="""<div class="timeline-item"><a class="tweet-link" href="/fixture/status/123">Post</a><div class="tweet-content">Original native caption</div><span class="username">fixture</span></div>"""
        val post=page(native+script(record("Secondary page description",dst("trainedAlgorithmicMedia")))).posts.single()
        assertEquals("Original native caption",post.body)
        assertEquals("fixture",post.author)
        assertEquals(AiUse.GENERATED,post.aiDisclosures!!.single().use)
    }

    @Test fun matchingJsonLdDuplicatesMergeMetadataWithoutChangingFirstCaption() {
        val records="""[${record("First caption")},${record("Second caption",dst("compositeSynthetic"))}]"""
        val post=page(script(records)).posts.single()
        assertEquals("First caption",post.body)
        assertEquals(AiUse.COMPOSITE,post.aiDisclosures!!.single().use)
    }

    @Test fun anonymousSchemaObjectAndOtherPostCannotLabelTheRequestedPost() {
        val unknown="""{"@type":"ImageObject","description":"Anonymous artwork","contentUrl":"https://media.example/image.jpg","digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"}"""
        val unknownPost=page(script(unknown)).posts.single()
        assertTrue(unknownPost.aiDisclosures.isNullOrEmpty())
        val posts=page(script("""[${record("Requested")},${record("Other",dst("trainedAlgorithmicMedia"),"https://x.com/another/status/456")}]""")).posts
        assertTrue(posts.first {it.canonicalUrl==target.canonicalUrl}.aiDisclosures.isNullOrEmpty())
        assertEquals(1,posts.first {it.canonicalUrl.endsWith("456")}.aiDisclosures!!.size)
    }

    @Test fun schemaMainEntityOfPageObjectMustStillNameThePost() {
        val body="""{"@type":"SocialMediaPosting","mainEntityOfPage":{"@id":"${target.canonicalUrl}"},"description":"Explicit page identity","digitalSourceType":"${vocabulary}trainedAlgorithmicMedia"}"""
        assertEquals(AiUse.GENERATED,page(script(body)).posts.single().aiDisclosures!!.single().use)
    }

    @Test fun tiktokSeoAiFieldsAndUndocumentedNumericEnumsAreNotContentEvidence() {
        val t=routes.recognize("https://www.tiktok.com/@fixture/video/123")!!
        // Public TikTok pages use ShowAIGC and AIGCDescription for generated SEO
        // copy, distinct from the creator/moderation content label enums.
        val fields="""{"id":"123","desc":"A public post #aigenerated","author":{"uniqueId":"fixture"},"video":{"playAddr":"https://media.example/video.mp4"},"IsAigc":false,"aigcLabelType":"2","moderationAigcLabelType":"1","AIGCDescription":"An AI-written SEO description","ShowAIGC":true,"software":"AI editor"}"""
        val post=Extraction.parse(Payload(fields,t.canonicalUrl,"application/json",200),t,Source(Platform.TIKTOK,"original",Platform.TIKTOK.origin,role=SourceRole.ORIGINAL),routes).posts.single()
        assertTrue(post.aiDisclosures.isNullOrEmpty())
        assertEquals("A public post #aigenerated",post.body)
    }

    @Test fun linkedInOnlyMatchingActivityCarriesItsOwnDeclaration() {
        val t=Target(Platform.LINKEDIN,TargetKind.POST,"12345","https://www.linkedin.com/posts/example_topic-activity-12345-slug")
        fun item(id: String,ai: Boolean)="""{"@type":"SocialMediaPosting","url":"https://www.linkedin.com/posts/example_topic-activity-$id-slug","text":"Update $id","datePublished":"2026-01-01T00:00:00Z"${if(ai)","+dst("trainedAlgorithmicMedia")else ""}}"""
        val payload=Payload(script("""[${item("12345",false)},${item("99999",true)}]"""),t.canonicalUrl,"text/html",200)
        val s=Source(Platform.LINKEDIN,"original",Platform.LINKEDIN.origin,role=SourceRole.ORIGINAL)
        assertTrue(AdditionalSocialParsing.linkedIn(payload,t,s).posts.single().aiDisclosures.isNullOrEmpty())
        val labeled=payload.copy(text=script(item("12345",true)))
        assertEquals(AiUse.GENERATED,AdditionalSocialParsing.linkedIn(labeled,t,s).posts.single().aiDisclosures!!.single().use)
    }

    @Test fun archivedSchemaDisclosureStaysWithTheArchivedEdition() {
        val current=Post("post:123",Platform.X,target.canonicalUrl,"fixture","The current post")
        val provider=ArchiveProvider.defaults.first()
        val capture=ArchiveCapture(provider,"20260101000000",target.canonicalUrl,1_767_225_600_000L)
        val schema="""{"@type":"SocialMediaPosting","url":"${target.canonicalUrl}","articleBody":"Original archived body","digitalSourceType":"${vocabulary}compositeWithTrainedAlgorithmicMedia"}"""
        val old=ArchiveParsing.replay(Payload(script(schema),capture.replayUrl,"text/html",200),capture,current,routes,1_792_146_400_000L)!!
        assertEquals(AiUse.EDITED,old.snapshot.aiDisclosures!!.single().use)
        assertEquals(old.snapshot.aiDisclosures,old.snapshot.asPost(current).aiDisclosures)
        assertTrue(current.aiDisclosures.isNullOrEmpty())
    }
}
