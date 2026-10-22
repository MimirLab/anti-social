/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.os.Build
import androidx.annotation.RequiresApi
import app.antisocial.core.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.contentauth.c2pa.C2PAContextBuilder
import org.contentauth.c2pa.C2PASettings
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.HttpResolver
import org.contentauth.c2pa.HttpResponse
import org.contentauth.c2pa.Reader
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Inspects exact, already available bytes. It never fetches assets, manifests, trust lists,
 * revocation information or identities. Reader defaults deliberately are not used: upstream's
 * default reader is allowed to fetch remote manifests. The denial resolver is defense in depth.
 *
 * Android 8/8.1 retain the rest of AntiSocial, including unsigned IPTC/XMP declarations; the optional
 * upstream native validator is only loaded on Android 9+. Call from a background dispatcher.
 */
internal object C2paInspector {
    const val MAX_BYTES=32*1024*1024
    private const val MAX_JSON=8*1024*1024
    private val mimeTypes=setOf("image/jpeg","image/png","image/webp","image/avif","image/heic","image/heif",
        "image/tiff","image/gif","image/svg+xml","video/mp4","video/quicktime","audio/mp4","audio/mpeg",
        "audio/wav","audio/x-wav","audio/flac","application/pdf")

    @Synchronized fun inspect(bytes: ByteArray,mime: String): AiMetadata? {
        val hint=credentialHint(bytes)
        if(bytes.size>MAX_BYTES)return if(hint)AiMetadata(credentialState=AiCredentialState.UNAVAILABLE,
            limitations=listOf(AiMetadataLimit.SIZE_LIMIT),inspectedAt=System.currentTimeMillis()) else null
        if(bytes.isEmpty() || mime.substringBefore(';').lowercase() !in mimeTypes)return if(hint)
            metadata(bytes,AiCredentialState.UNAVAILABLE,listOf(AiMetadataLimit.PARTIAL_CONTENT)) else null
        if(Build.VERSION.SDK_INT<28)return if(hint)
            metadata(bytes,AiCredentialState.UNAVAILABLE,listOf(AiMetadataLimit.UNSUPPORTED_DEVICE)) else null
        return try {
            NativeReader.inspect(bytes,mime.substringBefore(';').lowercase())?.let {json->
                if(json.length>MAX_JSON)metadata(bytes,AiCredentialState.UNAVAILABLE,listOf(AiMetadataLimit.SIZE_LIMIT))
                else decode(json)?.copy(inspectedAt=System.currentTimeMillis(),sha256=sha256(bytes))
            }
        }catch(e: LinkageError) {
            if(hint)metadata(bytes,AiCredentialState.UNAVAILABLE,listOf(AiMetadataLimit.UNSUPPORTED_DEVICE)) else null
        }catch(e: Exception) {
            // The SDK has positively identified a remote credential reference even when its
            // arbitrary hosting URL contains no "c2pa" text. It still must not be fetched.
            val remote=e.message.orEmpty().let {it.contains("must fetch remote manifests",true) || it.contains("RemoteManifestUrl",true)}
            if(!hint && !remote)null else {
                metadata(bytes,AiCredentialState.UNCHECKED,listOf(if(remote)AiMetadataLimit.REMOTE_NOT_FETCHED else AiMetadataLimit.MALFORMED))
            }
        }
    }

    private fun metadata(bytes: ByteArray,state: AiCredentialState,limits: List<AiMetadataLimit>)=
        AiMetadata(credentialState=state,limitations=limits,inspectedAt=System.currentTimeMillis(),sha256=sha256(bytes))

    private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}

    /** A structural credential hint, never a scan of prose, pixels or arbitrary strings. */
    private fun credentialHint(bytes: ByteArray): Boolean {
        val end=minOf(bytes.size,MAX_BYTES)
        fun u(at: Int)=bytes[at].toInt() and 255
        fun text(at: Int,value: String)=at>=0&&at+value.length<=end&&value.indices.all {u(at+it)==value[it].code}
        fun be32(at: Int)=(0..3).fold(0L){v,i->(v shl 8)+u(at+i)}
        fun le32(at: Int)=(0..3).fold(0L){v,i->v or (u(at+i).toLong() shl (i*8))}
        val c2paType=byteArrayOf(0x63,0x32,0x70,0x61,0,0x11,0,0x10,0x80.toByte(),0,0,0xaa.toByte(),0,0x38,0x9b.toByte(),0x71)
        fun jumbf(at: Int,limit: Int,split: Boolean=false): Boolean {
            if(at<0||at+38>limit||!text(at+4,"jumb")||!text(at+12,"jumd"))return false
            val outer=be32(at);val desc=be32(at+8)
            return outer>=38&&(split||outer<=limit-at)&&desc>=30&&desc<=limit-at-8&&
                c2paType.indices.all {bytes[at+16+it]==c2paType[it]}
        }
        return runCatching {when {
            end>=8&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))-> {
                var at=8;var found=false;var count=0
                while(at+12<=end&&count++<8192) {
                    val size=be32(at);if(size>Int.MAX_VALUE||at.toLong()+12+size>end)break
                    val n=size.toInt();val start=at+8
                    if(text(at+4,"caBX")&&jumbf(start,start+n)) {
                        val crc=CRC32().apply {update(bytes,at+4,n+4)}.value
                        if(crc==be32(start+n)){found=true;break}
                    }
                    at+=n+12
                };found
            }
            end>=2&&u(0)==255&&u(1)==216 -> {
                var at=2;var found=false;var count=0
                while(at+4<=end&&count++<8192) {
                    if(u(at++)!=255)break
                    while(at<end&&u(at)==255)at++
                    if(at>=end)break
                    val marker=u(at++);if(marker==0xda||marker==0xd9)break
                    if(marker in 0xd0..0xd8||marker==1||marker==0)break
                    if(at+2>end)break
                    val n=(u(at) shl 8)+u(at+1);if(n<2||at+n>end)break
                    val start=at+2
                    if(marker==0xeb&&text(start,"JP")&&start+8<=at+n&&jumbf(start+8,at+n,true)){found=true;break}
                    at+=n
                };found
            }
            end>=12&&text(0,"RIFF") -> {
                var at=12;var found=false;var count=0
                while(at+8<=end&&count++<8192) {
                    val n=le32(at+4);if(n>Int.MAX_VALUE||at.toLong()+8+n>end)break
                    if(text(at,"C2PA")&&jumbf(at+8,at+8+n.toInt())){found=true;break}
                    at+=8+n.toInt()+(n%2).toInt()
                };found
            }
            end>=12&&(text(4,"ftyp")||text(4,"styp"))-> {
                val c2paUuid=byteArrayOf(0xd8.toByte(),0xfe.toByte(),0xc3.toByte(),0xd6.toByte(),0x1b,0x0e,0x48,0x3c,0x92.toByte(),0x97.toByte(),0x58,0x28,0x87.toByte(),0x7e,0xc4.toByte(),0x81.toByte())
                var at=0;var found=false;var count=0
                while(at+8<=end&&count++<8192) {
                    var n=be32(at);var header=8
                    if(n==1L) {
                        if(at+16>end)break
                        val hi=be32(at+8);if(hi!=0L)break
                        n=be32(at+12);header=16
                    }else if(n==0L)n=(end-at).toLong()
                    if(n<header||n>Int.MAX_VALUE||at.toLong()+n>end)break
                    if(text(at+4,"uuid")&&n>=header+20&&c2paUuid.indices.all {bytes[at+header+it]==c2paUuid[it]}) {
                        val purpose=at+header+20;val limit=at+n.toInt()
                        val data=when {
                            text(purpose,"manifest\u0000")->purpose+9+8
                            text(purpose,"original\u0000")->purpose+9+8
                            text(purpose,"update\u0000")->purpose+7
                            else->null
                        }
                        if(data!=null&&jumbf(data,limit)){found=true;break}
                    }
                    at+=n.toInt()
                };found
            }
            else->false
        }}.getOrDefault(false)
    }

    /** Parses exclusively JSON returned by our locally configured SDK, not a publisher's JSON. */
    internal fun decode(json: String): AiMetadata? {
        if(json.length>MAX_JSON)return AiMetadata(credentialState=AiCredentialState.UNAVAILABLE,limitations=listOf(AiMetadataLimit.SIZE_LIMIT))
        val store=JsonParser.parseString(json).takeIf {it.isJsonObject}?.asJsonObject ?: return null
        val active=store.string("active_manifest") ?: return null
        val manifests=store.obj("manifests") ?: return null
        if(manifests.size()>64)return AiMetadata(credentialState=AiCredentialState.UNCHECKED,limitations=listOf(AiMetadataLimit.SIZE_LIMIT))
        val primary=manifests.obj(active) ?: return null
        val (state,trust)=when(store.string("validation_state")) {
            "Trusted"->AiCredentialState.VALID to AiSignerTrust.TRUSTED
            "Valid"->AiCredentialState.VALID to AiSignerTrust.UNTRUSTED
            "Invalid"->AiCredentialState.INVALID to AiSignerTrust.NOT_EVALUATED
            else->AiCredentialState.UNCHECKED to AiSignerTrust.NOT_EVALUATED
        }
        val visited=linkedSetOf<String>()
        val pending=ArrayDeque<String>().apply {add(active)}
        val disclosures=mutableListOf<AiDisclosure>()
        val redactedFields=linkedSetOf<String>()
        while(pending.isNotEmpty() && visited.size<64 && disclosures.size<64) {
            val id=pending.removeFirst()
            if(!visited.add(id))continue
            val manifest=manifests.obj(id) ?: continue
            val ingredient=id!=active
            manifest.array("redactions").take(64).forEach {uri->
                if(uri.isJsonPrimitive && uri.asJsonPrimitive.isString)metadataRedaction(uri.asString)?.let(redactedFields::add)
            }
            // Follow only declared ingredients. Unrelated manifests in the store are not the post's history.
            manifest.array("ingredients").take(64).forEach {node->
                val value=node.takeIf {it.isJsonObject}?.asJsonObject ?: return@forEach
                value.string("active_manifest")?.takeIf {manifests.has(it)}?.let(pending::addLast)
                addDisclosure(disclosures,value.string("digital_source_type"),ingredient=true)
            }
            manifest.array("assertions").take(128).forEach assertion@{node->
                val assertion=node.takeIf {it.isJsonObject}?.asJsonObject ?: return@assertion
                val label=assertion.string("label")?.replace(Regex("__[0-9]+$"),"") ?: return@assertion
                val data=assertion.obj("data") ?: return@assertion
                when {
                    label=="c2pa.actions" || label.matches(Regex("c2pa\\.actions\\.v[1-9][0-9]*")) ->
                        data.array("actions").take(128).forEach action@{entry->
                            val raw=entry.takeIf {it.isJsonObject}?.asJsonObject ?: return@action
                            val action=effectiveAction(data,raw)
                            val software=action.get("softwareAgent") ?: action.get("softwareAgentIndex")
                                ?.takeIf {it.isJsonPrimitive && it.asJsonPrimitive.isNumber}
                                ?.asString?.toIntOrNull()?.takeIf {it in 0..63}
                                ?.let {index->data.array("softwareAgents").take(64).elementAtOrNull(index)}
                            val softwareObject=software?.takeIf {it.isJsonObject}?.asJsonObject
                            val tool=softwareObject?.string("name") ?: software?.takeIf {it.isJsonPrimitive && it.asJsonPrimitive.isString}?.asString
                            addDisclosure(disclosures,action.string("digitalSourceType"),tool,softwareObject?.string("version"),action.string("action"),ingredient)
                            if(action.string("action")=="c2pa.redacted")
                                metadataRedaction(action.obj("parameters")?.string("redacted"))?.let(redactedFields::add)
                        }
                    label=="c2pa.metadata" -> addDisclosure(disclosures,metadataSourceType(data),ingredient=ingredient)
                    label=="stds.iptc" || label=="stds.iptc.photometadata" -> addDisclosure(disclosures,
                        data.string("Iptc4xmpExt:DigitalSourceType"),ingredient=ingredient)
                    label.matches(Regex("c2pa\\.ingredient(?:\\.v[1-9][0-9]*)?")) -> {
                        data.string("activeManifest")?.takeIf {manifests.has(it)}?.let(pending::addLast)
                        addDisclosure(disclosures,data.string("digitalSourceType"),ingredient=true)
                    }
                }
            }
        }
        return AiMetadata(disclosures.distinct().take(64),state,
            AiMetadataValues.text(primary.obj("signature_info")?.string("common_name") ?: primary.obj("signature_info")?.string("issuer")),trust,
            removals=redactedFields.take(32).takeIf {it.isNotEmpty()}?.let {
                listOf(MetadataRemoval(MetadataRemovalEvidence.CREDENTIAL_REDACTION,it))
            }.orEmpty())
    }

    /** Resolve only the standard local IPTC namespace; no JSON-LD context is fetched. */
    private fun metadataSourceType(data: JsonObject): String? {
        val context=data.obj("@context") ?: return null
        return context.entrySet().asSequence().take(64).firstOrNull {(_,value)->
            value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString=="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
        }?.key?.let {prefix->data.string("$prefix:DigitalSourceType")}
    }

    /** Standard action templates apply only to matching recorded actions, then explicit fields win. */
    private fun effectiveAction(data: JsonObject,action: JsonObject): JsonObject {
        val name=action.string("action") ?: return action
        val templates=data.array("templates").take(64).filter {it.isJsonObject}.map {it.asJsonObject}.toList()
        val merged=JsonObject()
        templates.filter {it.string("action")=="*"}.forEach {t->t.entrySet().forEach {(key,value)->merged.add(key,value)}}
        templates.filter {it.string("action")==name}.forEach {t->t.entrySet().forEach {(key,value)->merged.add(key,value)}}
        action.entrySet().forEach {(key,value)->merged.add(key,value)}
        return merged
    }

    /** A visual redaction is not evidence that metadata was removed. Require a known assertion URI. */
    private fun metadataRedaction(uri: String?): String? {
        if(uri==null || uri.length>2048 || !uri.startsWith("self#jumbf=/c2pa/"))return null
        val marker="/c2pa.assertions/"
        if(!uri.contains(marker))return null
        val label=uri.substringAfter(marker)
        if(!label.matches(Regex("(?:c2pa\\.metadata(?:\\.v[1-9][0-9]*)?|stds\\.(?:exif|iptc(?:\\.photometadata)?|schema-org\\.[A-Za-z0-9._-]+))(?:__[0-9]+)?")))return null
        return AiMetadataValues.text(label,160)
    }

    private fun addDisclosure(out: MutableList<AiDisclosure>,sourceType: String?,tool: String?=null,
                              version: String?=null,action: String?=null,ingredient: Boolean=false) {
        val use=AiMetadataValues.sourceType(sourceType) ?: return
        if(out.size<64)out+=AiDisclosure(use,AiEvidence.CONTENT_CREDENTIALS,sourceType,
            AiMetadataValues.text(tool),AiMetadataValues.text(version,80),AiMetadataValues.text(action,100),ingredient)
    }

    private fun JsonObject.obj(name: String)=get(name)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.string(name: String)=get(name)?.takeIf {it.isJsonPrimitive && it.asJsonPrimitive.isString}?.asString
    private fun JsonObject.array(name: String): Sequence<JsonElement> =get(name)?.takeIf {it.isJsonArray}?.asJsonArray?.asSequence() ?: emptySequence()

    /** This nested class must not initialize on API 26/27. */
    @RequiresApi(28)
    private object NativeReader {
        private val cancellation=Executors.newSingleThreadScheduledExecutor {work->Thread(work,"anti social credential timeout").apply {isDaemon=true}}
        // Inputs are capped at 32 MiB; keep ordinary inspection backing data in memory instead
        // of asking the native library to spill into an unspecified process temporary directory.
        private const val SETTINGS="""{"version":1,"core":{"allowed_network_hosts":[],"decode_identity_assertions":false,"backing_store_memory_threshold_in_mb":64},"verify":{"verify_after_reading":true,"verify_trust":true,"verify_timestamp_trust":true,"remote_manifest_fetch":false,"ocsp_fetch":false}}"""

        fun inspect(bytes: ByteArray,mime: String): String? =C2PASettings.create().use {settings->
            settings.updateFromString(SETTINGS,"json")
            C2PAContextBuilder.create().use {builder->
                builder.setSettings(settings).setHttpResolver(HttpResolver {HttpResponse(503,null)}).build().use {context->
                    val cancelLock=Any();var finished=false
                    val cancel=cancellation.schedule({synchronized(cancelLock){if(!finished)runCatching {context.cancel()}}},8,TimeUnit.SECONDS)
                    try {
                        DataStream(bytes).use {stream->
                            Reader.fromContext(context).withStream(mime,stream).use {reader->reader.json()}
                        }
                    }finally {
                        // A timeout callback may already be running. Do not free its native context
                        // until it has left cancel(), and never cancel a context after it is closed.
                        synchronized(cancelLock){finished=true}
                        cancel.cancel(false)
                    }
                }
            }
        }
    }
}
