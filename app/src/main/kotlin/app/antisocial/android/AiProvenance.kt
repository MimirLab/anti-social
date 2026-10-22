/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.runtime.*
import app.antisocial.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun rememberAiProvenance(repo: Repository,posts: List<Post>,localOnly: Boolean=false,
    additional:Map<String,MediaInfoResult> = emptyMap()): List<AiDisclosureEntry> {
    val reports by repo.aiMetadata.reports.collectAsState()
    val assetsRevision by repo.library.revision.collectAsState()
    val protectedReports by produceState<Map<String,AiMetadata>>(emptyMap(),repo,posts,localOnly,assetsRevision) {
        value=if(!localOnly)emptyMap()else withContext(Dispatchers.IO) {buildMap {
            posts.inspectionUrls().forEach {url->
                repo.savedMedia(url)?.let {file->repo.aiMetadata.inspect(url,file)?.let {put(url,it)}}
            }
        }}
    }
    // Existing local bytes also cover videos and images not currently on screen.
    // These lookups never call Repository.media or a remote reader.
    LaunchedEffect(repo,posts,localOnly,assetsRevision) {withContext(Dispatchers.IO) {
        if(!localOnly)posts.inspectionUrls().forEach {url->
            val file=repo.availableMedia(url)
            file?.let {repo.aiMetadata.observe(url,it)}
        }
    }}
    return remember(posts,reports,protectedReports,localOnly,additional) {buildList {
        posts.take(CrossPosts.MAX_EDITIONS).forEach {post->
            val source=SourcePresentation.label(post)
            AiMetadataValues.captured(AiMetadata(disclosures=post.aiDisclosures.orEmpty()))?.disclosures.orEmpty()
                .groupBy {it.attachment to it.assetUrl}.forEach {(scope,declarations)->
                val index=post.media.indexOfFirst {it.url==scope.second}.takeIf {it>=0}
                val label=if(!scope.first)"Post disclosure"else if(index==null)"Attached media disclosure"else "${if(post.media[index].type=="image")"Image"else "Video"} ${index+1} · page disclosure"
                add(entry(source,label,AiMetadata(disclosures=declarations)))
            }
            post.media.take(16).forEachIndexed {index,media->
                fun addMedia(url: String,label: String,captured: AiMetadata?=null) {
                    val checked=if(!localOnly)reports[url]else protectedReports[url]?.let {local->
                        reports[url]?.takeIf {it.sha256==local.sha256} ?: local
                    }
                    val value=additional[url]?.metadata?.takeIf {checked==null||it.sha256==checked.sha256} ?: checked ?: AiMetadataValues.captured(captured) ?: return
                    if(value.showable())add(entry(source,label,value,retained=checked==null))
                }
                val label="${if(media.type=="image")"Image"else "Video"} ${index+1}"
                addMedia(media.url,label,media.aiMetadata)
                // A poster is its own image. Its declaration never establishes the video's history.
                media.preview?.takeIf {it!=media.url}?.let {addMedia(it,"$label preview image")}
            }
        }
    }.distinct().take(32)}
}
private fun List<Post>.inspectionUrls()=take(CrossPosts.MAX_EDITIONS).flatMap {it.media.take(16)}.flatMap {listOfNotNull(it.url,it.preview)}.distinct().take(64)
private fun AiMetadata.showable()=disclosures.orEmpty().isNotEmpty()||removals.orEmpty().isNotEmpty()||
    credentialState!=AiCredentialState.NOT_PRESENT||inspectedAt>0&&(sha256?.matches(Regex("[a-fA-F0-9]{64}"))==true||limitations.orEmpty().isNotEmpty())
private fun entry(source: String,asset: String,value: AiMetadata,retained: Boolean=false)=AiDisclosureEntry(
    source,asset,AiMetadataNarrative.inspectionParagraphs(value,retained)+AiMetadataNarrative.notes(value),
    AiMetadataNarrative.evidence(value),AiMetadataNarrative.verification(value),AiMetadataNarrative.tools(value),
    aiDeclared=value.disclosures.orEmpty().isNotEmpty(),
    metadataRemoved=value.removals.orEmpty().any {it.evidence==MetadataRemovalEvidence.CREDENTIAL_REDACTION},
    metadataGap=value.removals.orEmpty().any {it.evidence==MetadataRemovalEvidence.SAME_IMAGE_COPY},
    inspectionStatus=if(value.disclosures.orEmpty().isEmpty()&&value.removals.orEmpty().isEmpty()&&value.credentialState==AiCredentialState.NOT_PRESENT)
        if(value.limitations.orEmpty().isEmpty())AiInspectionStatus.NO_DECLARATION else AiInspectionStatus.INCOMPLETE
    else null,
    headline=when {
        value.disclosures.orEmpty().isNotEmpty()&&value.disclosures.orEmpty().all {it.ingredient}->"AI recorded in source material"
        value.disclosures.orEmpty().map {it.use}.distinct()==listOf(AiUse.GENERATED)->"Generative AI creation recorded"
        value.disclosures.orEmpty().map {it.use}.distinct()==listOf(AiUse.EDITED)->"Generative AI edit recorded"
        value.disclosures.orEmpty().isNotEmpty()->"AI use recorded"
        value.removals.orEmpty().any {it.evidence==MetadataRemovalEvidence.CREDENTIAL_REDACTION}->"Metadata removal recorded"
        value.removals.orEmpty().isNotEmpty()->"Copies contain different metadata"
        value.credentialState==AiCredentialState.VALID->"Content Credentials verified"
        value.credentialState==AiCredentialState.INVALID->"Credential validation failed"
        else->"Creation record available"
    },
    integrity=when(value.credentialState) {
        AiCredentialState.VALID->"Signature and file match · "+if(value.trust==AiSignerTrust.TRUSTED)"trusted signer"else "signer not on the trust list"
        AiCredentialState.INVALID->"The supplied credential did not validate for this copy."
        AiCredentialState.UNAVAILABLE->"Credential verification unavailable."
        AiCredentialState.UNCHECKED->"Credential not yet verified."
        else->null
    },
)

/** Preserve observations with durable copies. Verification is rerun when their bytes are available. */
internal suspend fun Repository.captureAiMetadata(post: Post): Post=withContext(Dispatchers.IO) {
    suspend fun media(items: List<Media>)=items.map {item->
        val observed=availableMedia(item.url)?.let {aiMetadata.inspect(item.url,it)} ?: aiMetadata.reports.value[item.url]
        val value=(mediaInfo.observed(observed?.sha256) ?: observed ?: item.aiMetadata)?.takeIf {it.showable()}
        item.copy(aiMetadata=AiMetadataValues.captured(value))
    }
    val archive=post.archivedVersion?.let {it.copy(snapshot=it.snapshot.copy(media=media(it.snapshot.media)))}
    post.copy(media=media(post.media),archivedVersion=archive)
}
