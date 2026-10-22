/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** Deterministic prose from explicit declarations and local inspection observations;
 * no guesses from tool names, pixels or writing.
 */
object AiMetadataNarrative {
    /** Neutral status for a real local inspection, including a retained report.
     * An empty record is not an inspection. Existing declarations and credential
     * history keep their specific prose; notes() supplies any inspection limits.
     */
    fun inspectionParagraphs(value: AiMetadata,retained: Boolean=false): List<String> {
        if(value.disclosures.orEmpty().isNotEmpty()||value.removals.orEmpty().isNotEmpty()||value.credentialState!=AiCredentialState.NOT_PRESENT)
            return paragraphs(value)
        if(value.inspectedAt<=0)return emptyList()
        val limited=value.limitations.orEmpty().isNotEmpty()
        val observation=when {
            limited&&retained->"A saved inspection of this media copy was incomplete. No readable AI-use declaration was found in the part anti social could inspect."
            limited->"anti social could not fully inspect this delivered media copy. No readable AI-use declaration was found in the part it could inspect."
            retained->"A saved inspection of this media copy found no readable AI-use declaration."
            else->"anti social inspected this delivered media copy and found no readable AI-use declaration."
        }
        return listOf("$observation AI use remains unknown. Missing declarations do not establish whether the media was made with AI. anti social does not classify image appearance.")
    }

    fun paragraphs(value: AiMetadata): List<String> {
        val declarations=value.disclosures.orEmpty().take(64)
        val removals=removalParagraphs(value)
        if(declarations.isEmpty())return if(removals.isNotEmpty())removals else when(value.credentialState) {
            AiCredentialState.NOT_PRESENT->emptyList()
            AiCredentialState.UNAVAILABLE->listOf("Content Credentials could not be inspected on this device.")
            AiCredentialState.INVALID->listOf("A Content Credentials record was supplied, but its validation failed. No readable AI-use declaration was found.")
            else->listOf("A Content Credentials record is supplied with this media. It contains no explicit AI-use declaration that anti social could read.")
        }
        val ai=declarations.groupBy {Triple(it.ingredient,it.use,it.attachment)}.map { (kind,items)->
            val (ingredient,use,attachment)=kind
            val evidence=items.map {it.evidence}.toSet()
            val prefix=when {
                ingredient&&attachment->"An earlier ingredient in an attached media item's recorded history is described as"
                ingredient->"An earlier ingredient in the recorded history is described as"
                attachment&&evidence==setOf(AiEvidence.PLATFORM_LABEL)->"The platform labels an attached media item as"
                attachment&&evidence==setOf(AiEvidence.STRUCTURED_DATA)->"The page metadata describes an attached media item as"
                attachment&&AiEvidence.PLATFORM_LABEL in evidence->"The supplied metadata and platform label describe an attached media item as"
                attachment->"The supplied metadata describes an attached media item as"
                evidence==setOf(AiEvidence.PLATFORM_LABEL)->"The platform labels this post as"
                evidence==setOf(AiEvidence.STRUCTURED_DATA)->"The page metadata describes this media as"
                AiEvidence.PLATFORM_LABEL in evidence->"The supplied metadata and platform label describe this content as"
                else->"The supplied metadata describes this media as"
            }
            val description=when(use) {
                AiUse.GENERATED->"created using generative AI."
                AiUse.EDITED->"edited using generative AI. The declaration concerns an edit to existing content."
                AiUse.COMPOSITE->"a composite that includes generative AI elements. The declaration does not identify every generated element."
                AiUse.ASSISTED->"AI assisted. The declaration does not specify the amount or type of assistance."
                AiUse.UNSPECIFIED->"involving AI, without specifying whether it was generated or edited."
            }
            val scope=when {
                ingredient->" This concerns a component in the recorded history; it does not locate the affected parts or establish how much is visible here."
                attachment->" This declaration is scoped to the attached media item."
                else->""
            }
            "$prefix $description$scope"
        }.take(10)
        return ai+removals
    }

    fun evidence(value: AiMetadata): String = (value.disclosures.orEmpty().map {it.evidence}.distinct().map {when(it) {
        AiEvidence.IPTC_XMP->"Embedded IPTC/XMP metadata"
        AiEvidence.CONTENT_CREDENTIALS->"Embedded Content Credentials"
        AiEvidence.PLATFORM_LABEL->"Platform disclosure"
        AiEvidence.STRUCTURED_DATA->"Page metadata"
        AiEvidence.EMBEDDED_DECLARATION->"Embedded AI-use label"
        AiEvidence.GENERATION_RECIPE->"Embedded generation recipe"
        AiEvidence.NOVELAI_SIGNATURE->"NovelAI signed image and generation record"
    }}+value.removals.orEmpty().map {it.evidence}.distinct().map {when(it) {
        MetadataRemovalEvidence.CREDENTIAL_REDACTION->"Content Credentials redaction record"
        MetadataRemovalEvidence.SAME_IMAGE_COPY->"Local comparison of matching encoded pictures"
    }}).ifEmpty {if(value.credentialState==AiCredentialState.NOT_PRESENT)emptyList()else listOf("Content Credentials")}.joinToString(" · ")

    /** Integrity and the signer's trust are separate facts, both scoped to Content Credentials. */
    fun verification(value: AiMetadata): String? {
        val status=when(value.credentialState) {
            AiCredentialState.NOT_PRESENT->when {
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.IPTC_XMP}->"The IPTC/XMP declaration is unsigned."
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.EMBEDDED_DECLARATION}->"The embedded AI-use label is unsigned."
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.NOVELAI_SIGNATURE}->"NovelAI's signature verified the decoded image and its signed generation record."
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.GENERATION_RECIPE}->"The embedded generation recipe is supplied by the file; it is not a verified credential."
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.STRUCTURED_DATA}->"The page supplies this declaration; no verified file credential accompanies it."
                value.disclosures.orEmpty().any {it.evidence==AiEvidence.PLATFORM_LABEL}->"This is a platform-supplied declaration. No verified file credential accompanies it."
                value.removals.orEmpty().any {it.evidence==MetadataRemovalEvidence.CREDENTIAL_REDACTION}->"The supplied redaction claim has no verified file credential."
                else->return null
            }
            AiCredentialState.UNCHECKED->"The Content Credentials record has not been checked against this file."
            AiCredentialState.INVALID->"Content Credentials validation failed. The recorded declarations are unverified."
            AiCredentialState.UNAVAILABLE->"Content Credentials validation is unavailable here."
            AiCredentialState.VALID->"Content Credentials signature and file integrity checks passed. "+when(value.trust) {
                AiSignerTrust.NOT_EVALUATED->"The signer's trust has not been evaluated."
                AiSignerTrust.UNTRUSTED->"The signer is not trusted by the configured trust list."
                AiSignerTrust.TRUSTED->"The signer is trusted by the configured trust list."
            }
        }
        val signer=AiMetadataValues.text(value.signer)?.let {" Recorded signer: ${it.trimEnd('.')}."}.orEmpty()
        return status+signer
    }

    /** Tool/model names are displayed only when attached to an explicit AI declaration. */
    fun tools(value: AiMetadata): List<String> = value.disclosures.orEmpty().take(64).mapNotNull {
        val tool=AiMetadataValues.text(it.tool,96) ?: return@mapNotNull null
        val version=AiMetadataValues.text(it.version,48)
        if(version==null||tool.endsWith(version))tool else "$tool ($version)"
    }.distinct().take(8)

    fun notes(value: AiMetadata): List<String> = buildList {
        val evidence=value.disclosures.orEmpty().map {it.evidence}.toSet()
        if(AiEvidence.IPTC_XMP in evidence&&value.credentialState!=AiCredentialState.NOT_PRESENT)
            add("IPTC/XMP fields are ordinary metadata. The credential status applies to the Content Credentials record.")
        if(AiEvidence.STRUCTURED_DATA in evidence&&value.credentialState!=AiCredentialState.NOT_PRESENT)
            add("The page declaration is separate from the credential attached to the media file.")
        if(AiEvidence.EMBEDDED_DECLARATION in evidence&&value.credentialState!=AiCredentialState.NOT_PRESENT)
            add("The embedded AI-use label is separate from the Content Credentials record described by the credential status.")
        value.limitations.orEmpty().distinct().take(8).forEach {limit->add(when(limit) {
            AiMetadataLimit.REMOTE_NOT_FETCHED->"A separate online credential was referenced. anti social did not contact its host."
            AiMetadataLimit.UNSUPPORTED_DEVICE->"This device cannot run the available Content Credentials reader."
            AiMetadataLimit.SIZE_LIMIT->"The inspection reached its size limit, so some metadata may be unread."
            AiMetadataLimit.MALFORMED->"Some supplied metadata could not be read."
            AiMetadataLimit.PARTIAL_CONTENT->"Only part of the media file was available for inspection."
            AiMetadataLimit.UNSUPPORTED_FORMAT->"This media format could not be inspected by the available metadata readers."
        })}
    }

    private fun removalParagraphs(value: AiMetadata): List<String> = value.removals.orEmpty().take(32).groupBy {it.evidence}.map {(kind,records)->
        val fields=records.flatMap {it.fields.orEmpty().take(32)}.mapNotNull(::metadataField).distinct().take(6)
        val names=when(fields.size) {0->"some recorded metadata";1->fields.single();2->fields.joinToString(" and ");else->fields.dropLast(1).joinToString(", ")+", and "+fields.last()}
        when(kind) {
            MetadataRemovalEvidence.CREDENTIAL_REDACTION->"The supplied Content Credentials record declares the removal of $names from its history. "+
                (if(value.credentialState==AiCredentialState.VALID)"The signature and file integrity checks passed for that record; the redaction remains a recorded declaration."else "This redaction claim has not been confirmed against a valid file credential.")+" A redaction declaration alone does not indicate AI use."
            MetadataRemovalEvidence.SAME_IMAGE_COPY->"Another copy of the same encoded picture contains $names that this copy does not. The comparison establishes a metadata difference. It cannot tell whether information was removed here or added to the other copy, or identify who changed it. A metadata gap does not indicate AI use."
        }
    }

    /** Assertion identifiers and JUMBF URLs are translated to families, never printed as a blob. */
    private fun metadataField(value: String): String? {
        val field=AiMetadataValues.text(value,512)?.lowercase(java.util.Locale.ROOT) ?: return null
        val segment=field.substringAfterLast('/').substringBefore('[').substringBefore('?').substringBefore('#')
        return when {
            segment=="exif"||segment.startsWith("stds.exif")->"camera details (EXIF)"
            segment=="xmp"||segment.startsWith("stds.xmp")->"XMP information"
            segment=="iptc"||segment.startsWith("stds.iptc")->"IPTC description metadata"
            segment=="comments"->"embedded comments"
            segment=="content_credentials"||segment=="c2pa"->"Content Credentials"
            segment.startsWith("c2pa.actions")->"editing history"
            segment.startsWith("c2pa.ingredient")->"source ingredient information"
            segment.startsWith("c2pa.capture")||segment.startsWith("c2pa.camera")->"capture details"
            segment.startsWith("stds.schema-org")->"authorship and description information"
            segment.startsWith("c2pa.metadata")->"embedded metadata"
            else->null
        }
    }
}
