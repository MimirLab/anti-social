/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AiMetadataNarrativeTest {
    @org.junit.Test fun copiedInspectionDoesNotClaimThatAnotherDeviceLacksTheValidator() {
        val copied=AiMetadataValues.captured(AiMetadata(credentialState=AiCredentialState.UNAVAILABLE,
            limitations=listOf(AiMetadataLimit.UNSUPPORTED_DEVICE)))!!
        org.junit.Assert.assertEquals(AiCredentialState.UNCHECKED,copied.credentialState)
        org.junit.Assert.assertFalse(copied.limitations.contains(AiMetadataLimit.UNSUPPORTED_DEVICE))
        org.junit.Assert.assertFalse(AiMetadataNarrative.notes(copied).any {it.contains("This device")})
    }
    private fun metadata(use: AiUse=AiUse.GENERATED,evidence: AiEvidence=AiEvidence.IPTC_XMP,ingredient: Boolean=false)=AiMetadata(disclosures=listOf(AiDisclosure(use,evidence,ingredient=ingredient)))

    @Test fun generationEditingCompositingAssistanceAndUnspecifiedUseKeepDifferentMeanings() {
        val expected=mapOf(AiUse.GENERATED to "created using generative AI",AiUse.EDITED to "edited using generative AI",AiUse.COMPOSITE to "a composite that includes generative AI elements",AiUse.ASSISTED to "AI assisted",AiUse.UNSPECIFIED to "without specifying whether it was generated or edited")
        expected.forEach {(use,meaning)->assertTrue(AiMetadataNarrative.paragraphs(metadata(use)).single().contains(meaning))}
        assertTrue(AiMetadataNarrative.paragraphs(metadata(AiUse.EDITED)).single().contains("existing content"))
        assertFalse(AiMetadataNarrative.paragraphs(metadata(AiUse.EDITED)).single().contains("entirely generated"))
    }

    @Test fun matchingDeclarationsAcrossMetadataFormatsBecomeOneReadableParagraph() {
        val value=metadata().copy(disclosures=listOf(AiDisclosure(AiUse.GENERATED,AiEvidence.IPTC_XMP),AiDisclosure(AiUse.GENERATED,AiEvidence.CONTENT_CREDENTIALS)))
        assertEquals(1,AiMetadataNarrative.paragraphs(value).size)
        assertEquals("Embedded IPTC/XMP metadata · Embedded Content Credentials",AiMetadataNarrative.evidence(value))
    }

    @Test fun earlierIngredientNeverBecomesAClaimAboutTheWholeCurrentAsset() {
        val paragraph=AiMetadataNarrative.paragraphs(metadata(ingredient=true)).single()
        assertTrue(paragraph.startsWith("An earlier ingredient"))
        assertTrue(paragraph.contains("does not locate the affected parts"))
        assertFalse(paragraph.contains("this media as created"))
        val value=metadata().copy(disclosures=metadata().disclosures+metadata(AiUse.EDITED,ingredient=true).disclosures)
        assertEquals(2,AiMetadataNarrative.paragraphs(value).size)
    }

    @Test fun platformAndStructuredPageDeclarationsKeepTheirActualSource() {
        val platform=metadata(evidence=AiEvidence.PLATFORM_LABEL)
        assertTrue(AiMetadataNarrative.paragraphs(platform).single().startsWith("The platform labels this post"))
        assertEquals("Platform disclosure",AiMetadataNarrative.evidence(platform))
        val page=metadata(evidence=AiEvidence.STRUCTURED_DATA)
        assertTrue(AiMetadataNarrative.paragraphs(page).single().startsWith("The page metadata describes"))
        assertEquals("Page metadata",AiMetadataNarrative.evidence(page))
        assertTrue(AiMetadataNarrative.verification(page)!!.contains("no verified file credential"))
    }

    @Test fun noMetadataProducesNoAuthorshipVerdictAndOrdinaryCredentialsRemainNeutral() {
        assertTrue(AiMetadataNarrative.paragraphs(AiMetadata()).isEmpty())
        assertEquals("",AiMetadataNarrative.evidence(AiMetadata()))
        assertNull(AiMetadataNarrative.verification(AiMetadata()))
        val ordinary=AiMetadata(credentialState=AiCredentialState.VALID)
        val paragraph=AiMetadataNarrative.paragraphs(ordinary).single()
        assertTrue(paragraph.contains("no explicit AI-use declaration that anti social could read"))
        assertFalse(paragraph.contains("human-made"));assertFalse(paragraph.contains("authentic"))
    }

    @Test fun anUnobservedEmptyRecordCannotClaimThatAnInspectionTookPlace() {
        for(value in listOf(AiMetadata(),AiMetadata(limitations=listOf(AiMetadataLimit.UNSUPPORTED_FORMAT)))) {
            assertTrue(AiMetadataNarrative.inspectionParagraphs(value).isEmpty())
            assertTrue(AiMetadataNarrative.inspectionParagraphs(value,retained=true).isEmpty())
        }
    }

    @Test fun observedMissingDeclarationsStayUnknownAndSavedInspectionIsNotDescribedAsFresh() {
        val observation=AiMetadata(inspectedAt=1234,sha256="a".repeat(64))
        val original=observation.copy()
        val current=AiMetadataNarrative.inspectionParagraphs(observation).single()
        assertTrue(current.contains("anti social inspected this delivered media copy"))
        assertTrue(current.contains("no readable AI-use declaration"))
        assertTrue(current.contains("AI use remains unknown"))
        assertTrue(current.contains("does not classify image appearance"))
        for(unsupportedClaim in listOf("human-made","authentic","metadata was removed","AI DETECTED"))assertFalse(current.contains(unsupportedClaim))
        val saved=AiMetadataValues.captured(observation)!!
        val retained=AiMetadataNarrative.inspectionParagraphs(saved,retained=true).single()
        assertTrue(retained.startsWith("A saved inspection"))
        assertFalse(retained.contains("anti social inspected this delivered"))
        assertEquals(original,observation)
        assertTrue(AiMetadataNarrative.evidence(observation).isEmpty())
        assertTrue(AiMetadataNarrative.paragraphs(observation).isEmpty())
    }

    @Test fun partialMalformedOversizeAndUnsupportedInspectionsNeverReadAsComplete() {
        for(limit in listOf(AiMetadataLimit.PARTIAL_CONTENT,AiMetadataLimit.MALFORMED,AiMetadataLimit.SIZE_LIMIT,AiMetadataLimit.UNSUPPORTED_FORMAT)) {
            val value=AiMetadata(inspectedAt=1234,limitations=listOf(limit))
            val current=AiMetadataNarrative.inspectionParagraphs(value).single()
            assertTrue(current.contains("could not fully inspect"))
            assertTrue(current.contains("part it could inspect"))
            assertTrue(current.contains("AI use remains unknown"))
            assertFalse(current.contains("anti social inspected this delivered"))
            assertFalse(AiMetadataNarrative.notes(value).isEmpty())
            assertTrue(AiMetadataNarrative.inspectionParagraphs(value,retained=true).single().startsWith("A saved inspection of this media copy was incomplete"))
        }
        assertTrue(AiMetadataNarrative.notes(AiMetadata(limitations=listOf(AiMetadataLimit.UNSUPPORTED_FORMAT))).single().contains("format could not be inspected"))
    }

    @Test fun inspectionStatusDoesNotReplaceSpecificDeclarationsCredentialsOrMetadataGaps() {
        val examples=listOf(metadata(),AiMetadata(credentialState=AiCredentialState.INVALID),AiMetadata(credentialState=AiCredentialState.VALID),
            AiMetadata(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.SAME_IMAGE_COPY,listOf("xmp")))))
        for(value in examples) {
            assertEquals(AiMetadataNarrative.paragraphs(value),AiMetadataNarrative.inspectionParagraphs(value))
            assertEquals(AiMetadataNarrative.paragraphs(value),AiMetadataNarrative.inspectionParagraphs(value,retained=true))
        }
    }

    @Test fun validCredentialsDescribeIntegrityAndSignerTrustSeparately() {
        for(trust in AiSignerTrust.entries) {
            val value=metadata().copy(credentialState=AiCredentialState.VALID,trust=trust)
            val note=AiMetadataNarrative.verification(value)!!
            assertTrue(note.contains("signature and file integrity checks passed"))
            assertTrue(note.contains(when(trust){AiSignerTrust.NOT_EVALUATED->"trust has not been evaluated";AiSignerTrust.UNTRUSTED->"not trusted by the configured trust list";AiSignerTrust.TRUSTED->"trusted by the configured trust list"}))
            assertFalse(note.contains("content is true"));assertFalse(note.contains("verified AI"))
        }
    }

    @Test fun invalidCredentialCannotBorrowAStoredTrustedSignerStatus() {
        val value=metadata().copy(credentialState=AiCredentialState.INVALID,trust=AiSignerTrust.TRUSTED)
        val note=AiMetadataNarrative.verification(value)!!
        assertTrue(note.contains("validation failed"));assertTrue(note.contains("unverified"))
        assertFalse(note.contains("trusted by"));assertFalse(note.contains("checks passed"))
    }

    @Test fun uncheckedAndUnavailableStatesNeverImplyVerification() {
        assertTrue(AiMetadataNarrative.verification(metadata().copy(credentialState=AiCredentialState.UNCHECKED))!!.contains("has not been checked against this file"))
        assertTrue(AiMetadataNarrative.verification(metadata().copy(credentialState=AiCredentialState.UNAVAILABLE))!!.contains("unavailable"))
        assertEquals(listOf("Content Credentials could not be inspected on this device."),AiMetadataNarrative.paragraphs(AiMetadata(credentialState=AiCredentialState.UNAVAILABLE)))
        assertTrue(AiMetadataNarrative.verification(metadata())!!.contains("unsigned"))
    }

    @Test fun toolsAreOptionalBoundedAndSanitizedWithoutInferringAnyUse() {
        assertTrue(AiMetadataNarrative.tools(AiMetadata()).isEmpty())
        val value=metadata().copy(disclosures=listOf(AiDisclosure(AiUse.EDITED,AiEvidence.CONTENT_CREDENTIALS,tool="Studio\u202e\n  Pro",version="4.2"),AiDisclosure(AiUse.EDITED,AiEvidence.CONTENT_CREDENTIALS,tool="Studio Pro",version="4.2"),AiDisclosure(AiUse.EDITED,AiEvidence.CONTENT_CREDENTIALS,tool="x".repeat(500))))
        val tools=AiMetadataNarrative.tools(value)
        assertEquals("Studio Pro (4.2)",tools.first());assertEquals(2,tools.size)
        assertEquals(96,tools.last().length)
        assertFalse(tools.joinToString().contains('\u202e'))
    }

    @Test fun suppliedSignerIsPlainBoundedCopy() {
        val value=metadata().copy(credentialState=AiCredentialState.UNCHECKED,signer="Example\u202e\n  Publisher.")
        assertTrue(AiMetadataNarrative.verification(value)!!.endsWith("Recorded signer: Example Publisher."))
        assertFalse(AiMetadataNarrative.verification(value)!!.contains('\u202e'))
    }

    @Test fun LimitsExplainWhatWasNotInspectedWithoutRequestingRemoteResources() {
        val value=metadata().copy(limitations=AiMetadataLimit.entries+AiMetadataLimit.REMOTE_NOT_FETCHED)
        val notes=AiMetadataNarrative.notes(value)
        assertEquals(AiMetadataLimit.entries.size,notes.size)
        assertTrue(notes.any {it.contains("did not contact its host")})
        assertTrue(notes.any {it.contains("Only part of the media file")})
        assertTrue(notes.any {it.contains("size limit")})
        assertTrue(notes.any {it.contains("could not be read")})
    }

    @Test fun xmpAndPageDeclarationsAreNotLaunderedThroughUnrelatedCredentialValidity() {
        val value=metadata().copy(credentialState=AiCredentialState.VALID,disclosures=metadata().disclosures+metadata(evidence=AiEvidence.STRUCTURED_DATA).disclosures)
        val notes=AiMetadataNarrative.notes(value)
        assertEquals(2,notes.size)
        assertTrue(notes.any {it.contains("ordinary metadata")})
        assertTrue(notes.any {it.contains("page declaration is separate")})
    }

    @Test fun explicitRedactionRemainsADeclarationAndNeverBecomesAnAIUseClaim() {
        val base=AiMetadata(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.CREDENTIAL_REDACTION,listOf("stds.exif"))))
        for(state in listOf(AiCredentialState.NOT_PRESENT,AiCredentialState.UNCHECKED,AiCredentialState.INVALID,AiCredentialState.UNAVAILABLE)) {
            val value=base.copy(credentialState=state)
            val paragraph=AiMetadataNarrative.paragraphs(value).single()
            assertTrue(paragraph.contains("declares the removal"));assertTrue(paragraph.contains("camera details (EXIF)"))
            assertTrue(paragraph.contains("has not been confirmed against a valid file credential"))
            assertTrue(paragraph.contains("does not indicate AI use"));assertFalse(paragraph.contains("created using generative AI"))
        }
        val valid=AiMetadataNarrative.paragraphs(base.copy(credentialState=AiCredentialState.VALID)).single()
        assertTrue(valid.contains("signature and file integrity checks passed"))
        assertTrue(valid.contains("remains a recorded declaration"))
    }

    @Test fun matchingEncodedPictureComparisonExplainsTheGapWithoutInventingDirectionOrIntent() {
        val value=AiMetadata(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.SAME_IMAGE_COPY,listOf("xmp","exif","content_credentials"))))
        val paragraph=AiMetadataNarrative.paragraphs(value).single()
        assertTrue(paragraph.startsWith("Another copy of the same encoded picture"))
        assertTrue(paragraph.contains("XMP information"));assertTrue(paragraph.contains("Content Credentials"))
        assertTrue(paragraph.contains("removed here or added to the other copy"))
        assertTrue(paragraph.contains("or identify who changed it"))
        assertTrue(paragraph.contains("does not indicate AI use"))
        assertEquals("Local comparison of matching encoded pictures",AiMetadataNarrative.evidence(value))
        assertNull(AiMetadataNarrative.verification(value))
    }

    @Test fun noMetadataAndNoComparisonIsNotEvidenceOfScrubbing() {
        val empty=AiMetadata()
        assertTrue(AiMetadataNarrative.paragraphs(empty).isEmpty())
        assertTrue(AiMetadataNarrative.evidence(empty).isEmpty())
        assertTrue(AiMetadataNarrative.notes(empty).isEmpty())
    }

    @Test fun rawRedactionAssertionUrisBecomeFriendlyFamilies() {
        val value=AiMetadata(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.CREDENTIAL_REDACTION,listOf("self#jumbf=/c2pa/urn:uuid:example/c2pa.assertions/c2pa.actions.v2","self#jumbf=/c2pa/private/c2pa.assertions/stds.exif[2]","https://secret.invalid/private/assertion"))))
        val paragraph=AiMetadataNarrative.paragraphs(value).single()
        assertTrue(paragraph.contains("editing history"));assertTrue(paragraph.contains("camera details (EXIF)"))
        assertFalse(paragraph.contains("self#"));assertFalse(paragraph.contains("urn:"));assertFalse(paragraph.contains("secret.invalid"))
    }

    @Test fun unknownRemovedFieldsRemainReadableWithoutShowingUntrustedIdentifiers() {
        val value=AiMetadata(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.CREDENTIAL_REDACTION,listOf("https://secret.invalid/very-private-field"))))
        val paragraph=AiMetadataNarrative.paragraphs(value).single()
        assertTrue(paragraph.contains("some recorded metadata"));assertFalse(paragraph.contains("secret.invalid"))
        assertTrue(AiMetadataNarrative.verification(value)!!.contains("no verified file credential"))
    }

    @Test fun aiDeclarationsAndMetadataGapsAreBothPreservedWithoutConflatingTheirEvidence() {
        val value=metadata().copy(removals=listOf(MetadataRemoval(MetadataRemovalEvidence.SAME_IMAGE_COPY,listOf("comments"))))
        val paragraphs=AiMetadataNarrative.paragraphs(value)
        assertEquals(2,paragraphs.size)
        assertTrue(paragraphs.first().contains("created using generative AI"))
        assertTrue(paragraphs.last().contains("embedded comments"))
        assertEquals("Embedded IPTC/XMP metadata · Local comparison of matching encoded pictures",AiMetadataNarrative.evidence(value))
    }

    @Test fun embeddedOfficialAiLabelsAreDescribedAsUnsignedDeclarations() {
        val value=metadata(AiUse.UNSPECIFIED,AiEvidence.EMBEDDED_DECLARATION)
        assertEquals("Embedded AI-use label",AiMetadataNarrative.evidence(value))
        assertTrue(AiMetadataNarrative.verification(value)!!.contains("unsigned"))
        assertTrue(AiMetadataNarrative.paragraphs(value).single().contains("without specifying whether"))
        assertTrue(AiMetadataNarrative.notes(value.copy(credentialState=AiCredentialState.VALID)).single().contains("separate from the Content Credentials record"))
    }

    @Test fun attachedMediaDeclarationsKeepTheirScopeWhenThePostHasAMatchingRootDeclaration() {
        val root=AiDisclosure(AiUse.GENERATED,AiEvidence.STRUCTURED_DATA)
        val attached=root.copy(attachment=true,assetUrl="https://images.example.invalid/one-of-several.png")
        val paragraphs=AiMetadataNarrative.paragraphs(AiMetadata(disclosures=listOf(root,attached)))
        assertEquals("An attachment and the whole content must retain separate declarations",2,paragraphs.size)
        assertTrue(paragraphs.first().startsWith("The page metadata describes this media as"))
        assertTrue(paragraphs.last().startsWith("The page metadata describes an attached media item as"))
        assertTrue(paragraphs.last().contains("scoped to the attached media item"))
        assertFalse(paragraphs.last().contains("this post"));assertFalse(paragraphs.last().contains("images.example.invalid"))
        val ingredient=AiMetadataNarrative.paragraphs(AiMetadata(disclosures=listOf(attached.copy(ingredient=true)))).single()
        assertTrue(ingredient.startsWith("An earlier ingredient in an attached media item's recorded history"))
    }
}
