/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap

/** Validate saved-record JSON before Gson can coerce scalars or supply nulls for
 * missing Kotlin fields. Version 1 exports every nonnullable default field;
 * nullable references alone may be absent or explicitly null. Semantic URL,
 * identity, capture and asset checks remain in ReaderPortability/LocalLibrary. */
internal fun strictSavedModel(value: JsonObject, type: Class<*>) = PortableModelValidation.check(value, type)

/** A copied report records an earlier inspection, not verification by this
 * installation. Exact local bytes may establish credentials again later. */
internal fun capturedPortablePost(post: Post): Post = post.copy(
    media = post.media.map { it.copy(aiMetadata = AiMetadataValues.captured(it.aiMetadata)) },
    archivedVersion = post.archivedVersion?.let { archive -> archive.copy(snapshot = archive.snapshot.copy(
        media = archive.snapshot.media.map { it.copy(aiMetadata = AiMetadataValues.captured(it.aiMetadata)) }
    )) }
)

internal fun capturedPortableDetails(details: SavedCopyDetails): SavedCopyDetails = details.copy(
    fusionRoots = details.fusionRoots?.map(::capturedPortablePost),
    threads = details.threads.map { thread -> thread.copy(root = capturedPortablePost(thread.root), replies = thread.replies.map(::capturedPortablePost)) }
)

private object PortableModelValidation {
    private val nullableFields: Map<Class<*>, Set<String>> = mapOf(
        LocalBusiness::class.java to setOf("point","emails","related","menus"),
        LocalMenu::class.java to emptySet(),
        LocalMenuItem::class.java to emptySet(),
        LocalMenuPrice::class.java to emptySet(),
        LocalRelatedEntity::class.java to emptySet(),
        LocalPoint::class.java to emptySet(),
        LocalEventTime::class.java to setOf("zoneId"),
        LocalEvent::class.java to setOf("end","point","organizerUrl","registrationUrl","modifiedAt","occurrence","image"),
        LocalFieldEvidence::class.java to setOf("license","region"),
        LocalImageRegion::class.java to emptySet(),
        Post::class.java to setOf("publishedAt", "avatar", "replyCount", "score", "parentId", "provenance", "links", "archivedVersion", "recoveredArchive", "aiDisclosures", "extractionCoverage", "conversationId", "relationId", "sourceParentId", "contextNotes"),
        PublicContextNote::class.java to setOf("status"),
        ExtractionCoverage::class.java to setOf("note", "replyParent"),
        PostSnapshot::class.java to setOf("publishedAt", "avatar", "aiDisclosures", "relationship", "contextNotes"),
        PostRelationship::class.java to setOf("parentId", "conversationId", "relationId", "sourceParentId", "parentCoverage"),
        Media::class.java to setOf("preview", "mimeType", "aiMetadata", "durationMs", "audioUrl", "audioMimeType", "visualFingerprint"),
        Provenance::class.java to emptySet(),
        Source::class.java to emptySet(),
        ArchivedVersion::class.java to emptySet(),
        ArchiveRecovery::class.java to emptySet(),
        SavedCopyDetails::class.java to setOf("fusionRoots"),
        CapturedThread::class.java to setOf("commentAnywhere"),
        SelectedAsset::class.java to setOf("preview"),
        AiMetadata::class.java to setOf("signer", "sha256"),
        AiDisclosure::class.java to setOf("sourceType", "tool", "version", "action", "label", "assetUrl"),
        MetadataRemoval::class.java to emptySet(),
        AnywherePage::class.java to emptySet(),
        AnywhereComment::class.java to setOf("timestamp")
    )
    private val fields = ConcurrentHashMap<Class<*>, List<Field>>()
    fun check(value: JsonObject, type: Class<*>) {
        require(type in nullableFields) { "Unsupported saved-record model." }
        var nodes = 0
        fun visit(element: JsonElement, expected: Type, depth: Int) {
            require(depth <= 32 && ++nodes <= 500_000) { "Saved-record JSON is too complex." }
            when (expected) {
                is WildcardType -> {
                    require(expected.lowerBounds.isEmpty() && expected.upperBounds.size == 1) { "Unsupported saved-record type." }
                    visit(element, expected.upperBounds.single(), depth + 1)
                }
                is ParameterizedType -> {
                    val raw = expected.rawType as? Class<*> ?: throw IllegalArgumentException("Unsupported saved-record type.")
                    require(Collection::class.java.isAssignableFrom(raw) && expected.actualTypeArguments.size == 1) { "Unsupported saved-record collection." }
                    require(element.isJsonArray && element.asJsonArray.size() <= 4096) { "Invalid or oversized saved-record list." }
                    element.asJsonArray.forEach { item ->
                        require(!item.isJsonNull) { "Saved-record lists cannot contain null entries." }
                        visit(item, expected.actualTypeArguments.single(), depth + 1)
                    }
                }
                is Class<*> -> when {
                    expected == String::class.java -> require(element.isJsonPrimitive && element.asJsonPrimitive.isString && element.asString.length <= 1024 * 1024) { "Saved-record text must be a string." }
                    expected == java.lang.Boolean.TYPE || expected == java.lang.Boolean::class.java -> require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { "Saved-record flags must be booleans." }
                    expected == java.lang.Long.TYPE || expected == java.lang.Long::class.java || expected == java.lang.Integer.TYPE || expected == java.lang.Integer::class.java -> {
                        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "Saved-record numbers must be numeric integers." }
                        // longValueExact rejects fractional, overflowing and
                        // nonfinite values instead of Gson's truncation/coercion.
                        val number = runCatching { element.asBigDecimal.longValueExact() }.getOrNull()
                        require(number != null && (expected != java.lang.Integer.TYPE && expected != java.lang.Integer::class.java || number in Int.MIN_VALUE..Int.MAX_VALUE)) { "Saved-record number is out of range or fractional." }
                    }
                    expected == java.lang.Double.TYPE || expected == java.lang.Double::class.java -> require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber && element.asDouble.isFinite()) { "Saved-record coordinates must be finite numbers." }
                    expected.isEnum -> {
                        require(element.isJsonPrimitive && element.asJsonPrimitive.isString && expected.enumConstants.orEmpty().any { (it as Enum<*>).name == element.asString }) { "Unsupported saved-record enum value." }
                    }
                    else -> {
                        val nullable = nullableFields[expected] ?: throw IllegalArgumentException("Unsupported saved-record object.")
                        require(element.isJsonObject) { "Saved-record object expected." }
                        val objectValue = element.asJsonObject
                        val members = fields.getOrPut(expected) { expected.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || Modifier.isTransient(it.modifiers) } }
                        val names = members.map { it.name }.toSet()
                        require(objectValue.keySet().all { it in names }) { "Unsupported saved-record field." }
                        members.forEach { field ->
                            val fieldValue = objectValue[field.name]
                            if (fieldValue == null || fieldValue.isJsonNull) require(field.name in nullable) { "Missing required saved-record field: ${field.name}." }
                            else visit(fieldValue, field.genericType, depth + 1)
                        }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported saved-record field type.")
            }
        }
        visit(value, type, 0)
    }
}
