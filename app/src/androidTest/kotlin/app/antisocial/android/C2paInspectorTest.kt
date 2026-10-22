/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.zip.CRC32

/** Real local cryptographic validation; these fixtures contain no public post or personal data. */
@RunWith(AndroidJUnit4::class)
class C2paInspectorTest {
    private fun fixture(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open("provenance/$name").use {it.readBytes()}

    @Test fun signedGenerationHasVerifiedAssetIntegrityButAnUntrustedTestSigner() {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        val bytes=fixture("generated.png")
        val result=requireNotNull(C2paInspector.inspect(bytes,"image/png"))
        assertEquals(AiCredentialState.VALID,result.credentialState)
        assertEquals(AiSignerTrust.UNTRUSTED,result.trust)
        assertEquals("anti social Fixture Studio",result.signer)
        assertEquals(AiUse.GENERATED,result.disclosures.single().use)
        assertEquals(AiEvidence.CONTENT_CREDENTIALS,result.disclosures.single().evidence)
        assertEquals("Fixture Studio",result.disclosures.single().tool)
        assertEquals("4.2",result.disclosures.single().version)
        assertEquals("c2pa.created",result.disclosures.single().action)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},result.sha256)
        assertTrue(result.inspectedAt>0)
        assertTrue(result.removals.isEmpty())
    }

    @Test fun changedPixelsInvalidateTheCredentialEvenThoughTheSignedAiDeclarationStillExists() {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        val result=requireNotNull(C2paInspector.inspect(fixture("tampered.png"),"image/png"))
        assertEquals(AiCredentialState.INVALID,result.credentialState)
        assertNotEquals(AiSignerTrust.TRUSTED,result.trust)
        assertEquals(AiUse.GENERATED,result.disclosures.single().use)
    }

    @Test fun anOrdinaryPictureHasNeitherAnAiVerdictNorAnAccusationOfScrubbing() {
        assertNull(C2paInspector.inspect(fixture("plain.png"),"image/png"))
        val bytes=insertPngChunk(fixture("plain.png"),"tEXt","Comment\u0000A normal caption says c2pa but has no credential.".toByteArray())
        assertNull("Words in PNG text are not a credential container",C2paInspector.inspect(bytes,"image/png"))
    }

    @Test fun remoteOnlyCredentialsNeverContactTheirDeclaredServer() {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        ServerSocket(0).use {server->
            server.soTimeout=350
            val bytes=replacePngText(fixture("remote.png"),"https://127.0.0.1:9/never-contact.c2pa","http://127.0.0.1:${server.localPort}/never-contact.c2pa")
            val result=requireNotNull(C2paInspector.inspect(bytes,"image/png"))
            assertEquals(AiCredentialState.UNCHECKED,result.credentialState)
            assertTrue(result.limitations.contains(AiMetadataLimit.REMOTE_NOT_FETCHED))
            assertTrue(result.disclosures.isEmpty())
            assertTrue(result.removals.isEmpty())
            try {server.accept().use {fail("Offline credential inspection contacted the manifest host")}}
            catch(expected: SocketTimeoutException) { /* No socket reached the canary. */ }
        }
    }

    @Test fun recordedMetadataRedactionRetainsTheReachableOriginalAiHistory() {
        assumeTrue(Build.VERSION.SDK_INT>=28)
        val result=requireNotNull(C2paInspector.inspect(fixture("redacted.png"),"image/png"))
        assertEquals(AiCredentialState.VALID,result.credentialState)
        assertEquals(AiSignerTrust.UNTRUSTED,result.trust)
        assertEquals(AiUse.GENERATED,result.disclosures.single().use)
        assertTrue(result.disclosures.single().ingredient)
        assertEquals(MetadataRemovalEvidence.CREDENTIAL_REDACTION,result.removals.single().evidence)
        assertEquals(listOf("c2pa.metadata"),result.removals.single().fields)
    }

    @Test fun genericEditsToolNamesUnrelatedManifestsAndVisualRedactionsAreNotAiOrMetadataScrubbing() {
        val json="""{
          "active_manifest":"current","validation_state":"Valid",
          "manifests":{
            "current":{"claim_generator_info":[{"name":"Generative AI Mega Studio"}],
              "assertions":[{"label":"c2pa.actions.v2","data":{"actions":[
                {"action":"c2pa.created","softwareAgent":{"name":"AI Image Creator"}},
                {"action":"c2pa.edited","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicallyEnhanced"},
                {"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicMedia"},
                {"action":"c2pa.redacted","parameters":{"region":"Face"}}
              ]}}]},
            "unrelated":{"assertions":[{"label":"c2pa.actions","data":{"actions":[
              {"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"}
            ]}}],"redactions":["self#jumbf=/c2pa/old/c2pa.assertions/stds.exif"]}
          }}"""
        val result=requireNotNull(C2paInspector.decode(json))
        assertTrue(result.disclosures.isEmpty())
        assertTrue(result.removals.isEmpty())
    }

    @Test fun aValidatedSignatureOrAnEmptyLegacyErrorArrayCannotSubstituteForSdkValidationState() {
        val json="""{"active_manifest":"one","validation_status":[],"manifests":{"one":{
          "signature_info":{"common_name":"Fixture"},"assertions":[{"label":"c2pa.actions","data":{"actions":[
            {"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"}
          ]}}]}}}"""
        val result=requireNotNull(C2paInspector.decode(json))
        assertEquals(AiCredentialState.UNCHECKED,result.credentialState)
        assertEquals(AiSignerTrust.NOT_EVALUATED,result.trust)
        assertEquals(AiUse.GENERATED,result.disclosures.single().use)
    }

    @Test fun standardMetadataNamespacesAndUsedActionTemplatesSupplyExplicitDeclarations() {
        val json="""{"active_manifest":"one","validation_state":"Valid","manifests":{"one":{"assertions":[
          {"label":"c2pa.metadata","data":{"@context":{"iptc":"http://iptc.org/std/Iptc4xmpExt/2008-02-29/"},
            "iptc:DigitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"}},
          {"label":"c2pa.actions.v2","data":{"softwareAgents":[{"name":"Local Editor","version":"2.1"}],
            "templates":[{"action":"c2pa.edited","softwareAgentIndex":0,
              "digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia"},
              {"action":"unused.private.action","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/compositeSynthetic"}],
            "actions":[{"action":"c2pa.edited"}]}}
        ]}}}"""
        val result=requireNotNull(C2paInspector.decode(json))
        assertEquals(setOf(AiUse.GENERATED,AiUse.EDITED),result.disclosures.map {it.use}.toSet())
        val edit=result.disclosures.single {it.use==AiUse.EDITED}
        assertEquals("Local Editor",edit.tool)
        assertEquals("2.1",edit.version)
        assertTrue(result.removals.isEmpty())
    }

    @Test fun androidEightKeepsDeclarationsAvailableWithoutLoadingTheNewerNativeSdk() {
        assumeTrue(Build.VERSION.SDK_INT<28)
        val result=requireNotNull(C2paInspector.inspect(fixture("generated.png"),"image/png"))
        assertEquals(AiCredentialState.UNAVAILABLE,result.credentialState)
        assertEquals(listOf(AiMetadataLimit.UNSUPPORTED_DEVICE),result.limitations)
        assertTrue(result.disclosures.isEmpty())
    }

    /** Rewrite only the remote XMP reference and keep the PNG well formed, without any server. */
    private fun replacePngText(source: ByteArray,old: String,replacement: String): ByteArray =ByteArrayOutputStream().use {buffer->
        DataOutputStream(buffer).use {out->
            out.write(source,0,8)
            var offset=8;var replaced=false
            while(offset+12<=source.size) {
                val size=((source[offset].toInt() and 255) shl 24) or ((source[offset+1].toInt() and 255) shl 16) or
                    ((source[offset+2].toInt() and 255) shl 8) or (source[offset+3].toInt() and 255)
                val type=source.copyOfRange(offset+4,offset+8)
                val data=source.copyOfRange(offset+8,offset+8+size)
                val text=data.toString(Charsets.ISO_8859_1)
                val changed=if(text.contains(old)){replaced=true;text.replace(old,replacement).toByteArray(Charsets.ISO_8859_1)} else data
                out.writeInt(changed.size);out.write(type);out.write(changed)
                out.writeInt(CRC32().apply {update(type);update(changed)}.value.toInt())
                offset+=size+12
            }
            check(replaced)
        }
        buffer.toByteArray()
    }

    private fun insertPngChunk(source: ByteArray,type: String,data: ByteArray): ByteArray =ByteArrayOutputStream().use {buffer->
        DataOutputStream(buffer).use {out->
            out.write(source,0,33)
            val tag=type.toByteArray(Charsets.US_ASCII)
            out.writeInt(data.size);out.write(tag);out.write(data)
            out.writeInt(CRC32().apply {update(tag);update(data)}.value.toInt())
            out.write(source,33,source.size-33)
        };buffer.toByteArray()
    }
}
