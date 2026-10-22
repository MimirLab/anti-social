/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** Explicit declarations, never a statistical judgement about prose or pixels. */
enum class AiUse { GENERATED, EDITED, COMPOSITE, ASSISTED, UNSPECIFIED }
enum class AiEvidence { IPTC_XMP, CONTENT_CREDENTIALS, PLATFORM_LABEL, STRUCTURED_DATA, EMBEDDED_DECLARATION }
enum class AiCredentialState { NOT_PRESENT, UNCHECKED, VALID, INVALID, UNAVAILABLE }
enum class AiSignerTrust { NOT_EVALUATED, UNTRUSTED, TRUSTED }
enum class AiMetadataLimit { REMOTE_NOT_FETCHED, UNSUPPORTED_DEVICE, SIZE_LIMIT, MALFORMED, PARTIAL_CONTENT, UNSUPPORTED_FORMAT }
enum class MetadataRemovalEvidence { CREDENTIAL_REDACTION, SAME_IMAGE_COPY }
data class MetadataRemoval(val evidence: MetadataRemovalEvidence,val fields: List<String> = emptyList())
data class AiDisclosure(
    val use: AiUse,
    val evidence: AiEvidence,
    val sourceType: String?=null,
    val tool: String?=null,
    val version: String?=null,
    val action: String?=null,
    val ingredient: Boolean=false,
    val label: String?=null,
    val attachment: Boolean=false,
    val assetUrl: String?=null,
)
/** A local inspection of exact delivered bytes. A valid signature is not proof of a truthful image. */
data class AiMetadata(
    val disclosures: List<AiDisclosure> = emptyList(),
    val credentialState: AiCredentialState=AiCredentialState.NOT_PRESENT,
    val signer: String?=null,
    val trust: AiSignerTrust=AiSignerTrust.NOT_EVALUATED,
    val limitations: List<AiMetadataLimit> = emptyList(),
    val inspectedAt: Long=0,
    val sha256: String?=null,
    val removals: List<MetadataRemoval> = emptyList(),
)

object AiMetadataValues {
    private val controls=Regex("[\\p{Cc}\\p{Cf}]")
    fun text(value: String?,max: Int=160): String?=value?.replace(controls," ")?.replace(Regex("\\s+")," ")?.trim()?.take(max)?.takeIf {it.isNotBlank()}
    fun sourceType(value: String?): AiUse? {
        // Exact vocabulary identities only; similarly named private fields are not evidence.
        val raw=value?.trim() ?: return null
        val term=when {
            raw.startsWith("https://cv.iptc.org/newscodes/digitalsourcetype/")->raw.substringAfter("https://cv.iptc.org/newscodes/digitalsourcetype/")
            raw.startsWith("http://cv.iptc.org/newscodes/digitalsourcetype/")->raw.substringAfter("http://cv.iptc.org/newscodes/digitalsourcetype/")
            else->return null
        }
        return when(term) {
            "trainedAlgorithmicMedia"->AiUse.GENERATED
            "compositeWithTrainedAlgorithmicMedia"->AiUse.EDITED
            "compositeSynthetic"->AiUse.COMPOSITE
            else->null
        }
    }
    /** A saved declaration remains readable; its signature must be checked against available bytes again. */
    fun captured(value: AiMetadata?): AiMetadata?=value?.copy(
        disclosures=value.disclosures.orEmpty().filterNotNull().take(64).filter {it.use in AiUse.entries&&it.evidence in AiEvidence.entries}
            .map {it.copy(sourceType=text(it.sourceType,256),tool=text(it.tool),version=text(it.version,80),action=text(it.action,100),label=text(it.label,240),assetUrl=text(it.assetUrl,2048))},
        credentialState=if(value.credentialState==null||value.credentialState==AiCredentialState.NOT_PRESENT)AiCredentialState.NOT_PRESENT else AiCredentialState.UNCHECKED,
        trust=AiSignerTrust.NOT_EVALUATED,signer=text(value.signer),
        // A copied report describes an earlier inspection, not this device's
        // capabilities. Available local bytes establish those again.
        limitations=value.limitations.orEmpty().filterNotNull().filter {it!=AiMetadataLimit.UNSUPPORTED_DEVICE}.distinct().take(8),
        removals=value.removals.orEmpty().filterNotNull().take(16).filter {it.evidence in MetadataRemovalEvidence.entries}
            .map {it.copy(fields=it.fields.orEmpty().filterNotNull().take(32).mapNotNull {field->text(field,256)})},
    )
}
