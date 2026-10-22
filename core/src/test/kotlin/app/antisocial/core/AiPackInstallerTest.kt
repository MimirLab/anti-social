package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiPackInstallerTest {
    @Test fun catalogRejectsChangedBytesAndWrongReleaseKey() {
        val bytes = AiTestFixtures.catalogBytes()
        val sig = AiTestFixtures.signature(bytes)
        assertEquals(3, AiCatalog.verify(bytes, sig, AiTestFixtures.keys.public).packs.size)
        assertThrows(IllegalArgumentException::class.java) { AiCatalog.verify(bytes + byteArrayOf(32), sig, AiTestFixtures.keys.public) }
        val other = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { AiCatalog.verify(bytes, sig, other.public) }
    }

    @Test fun signatureDoesNotAuthorizeUnsafePathsOrInsecurePayloads() {
        for (mutation in listOf("path", "http", "oldSchema")) {
            val json = JsonParser.parseString(AiTestFixtures.catalogBytes().toString(Charsets.UTF_8)).asJsonObject
            val asset = json.getAsJsonArray("packs")[0].asJsonObject.getAsJsonArray("assets")[0].asJsonObject
            when (mutation) { "path" -> asset.addProperty("filename", "../outside.bin"); "http" -> asset.addProperty("url", "http://models.example/plain.bin"); else -> json.addProperty("schema", "unknown") }
            val bytes = json.toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { AiCatalog.verify(bytes, AiTestFixtures.signature(bytes), AiTestFixtures.keys.public) }
        }
    }

    @Test fun exactPacketAssetsHaveMatchingDefaultsWithoutDownloadingAlternatives() {
        val source = JsonParser.parseReader(javaClass.getResourceAsStream("/ai-source-acquisition.json")!!.reader()).asJsonObject
        val artifacts = source.getAsJsonObject("source_artifacts")
        val generator = artifacts.getAsJsonArray("generator").single { it.asJsonObject["selection"].asString == "default" }.asJsonObject
        val embedding = artifacts.getAsJsonObject("embedding").getAsJsonArray("files").filter { it.asJsonObject["selection"].asString == "default" }
        assertEquals(6, artifacts.getAsJsonArray("generator").size())
        assertEquals(5, embedding.size)
        assertEquals(source["default_payload_bytes"].asLong, generator["bytes"].asLong + embedding.sumOf { it.asJsonObject["size"].asLong })
    }

    @Test fun completePackagesActivateAndEmbeddingLifecycleIsIndependent(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); installer.install(catalog.default(AiKind.TEXT))
            server.enqueue(MockResponse().setBody("embedding")); installer.install(catalog.default(AiKind.EMBEDDING))
            assertEquals("text-a", File(installer.activePack(AiKind.TEXT)!!.directory, "weights.bin").readText())
            assertEquals("embedding", File(installer.activePack(AiKind.EMBEDDING)!!.directory, "weights.bin").readText())
            val restored = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            assertNotNull(restored.activePack(AiKind.TEXT)); assertNotNull(restored.activePack(AiKind.EMBEDDING))
            installer.remove(AiKind.TEXT)
            assertNull(installer.activePack(AiKind.TEXT)); assertNotNull(installer.activePack(AiKind.EMBEDDING)); assertEquals(0L, installer.localBytes(AiKind.TEXT))
            assertEquals(2, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun storageAbiAndAppCompatibilityFailBeforeNetwork(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val pack = catalog.default(AiKind.TEXT); val gateway = AiTestFixtures.gateway(server)
            for (installer in listOf(AiPackInstaller(root, gateway, catalog, 42, "x86_64"), AiPackInstaller(root, gateway, catalog, 43, "armeabi-v7a"))) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { installer.install(pack) } }
            }
            assertThrows(IOException::class.java) { runBlocking { AiPackInstaller(root, gateway, catalog, 43, "x86_64", { 0L }).install(pack) } }
            assertNull(AiPackInstaller(root, gateway, catalog, 43, "x86_64").activePack(AiKind.TEXT)); assertEquals(0, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun badReplacementNeverDisplacesWorkingEditionAndCanRetry(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); installer.install(catalog.packs[0])
            server.enqueue(MockResponse().setBody("wrong!"))
            assertThrows(IOException::class.java) { runBlocking { installer.install(catalog.packs[1]) } }
            assertEquals(catalog.packs[0], installer.activePack(AiKind.TEXT)!!.pack)
            server.enqueue(MockResponse().setBody("text-b")); installer.install(catalog.packs[1])
            assertEquals(catalog.packs[1], installer.activePack(AiKind.TEXT)!!.pack)
            assertEquals(catalog.packs[0], installer.rollback(AiKind.TEXT)!!.pack)
            installer.install(catalog.packs[1]); assertEquals(3, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun failedRuntimeValidationKeepsPreviousActivePackage(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val gateway = AiTestFixtures.gateway(server)
            val installer = AiPackInstaller(root, gateway, catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); installer.install(catalog.packs[0])
            val guarded = AiPackInstaller(root, gateway, catalog, 43, "x86_64", structureValidator = { pack, _ -> if (pack.id == catalog.packs[1].id) throw IOException("Unsupported tensor schema") })
            server.enqueue(MockResponse().setBody("text-b")); assertThrows(IOException::class.java) { runBlocking { guarded.install(catalog.packs[1]) } }
            assertEquals(catalog.packs[0], guarded.activePack(AiKind.TEXT)!!.pack)
        }
        root.deleteRecursively()
    }

    @Test fun interruptedPayloadResumesAfterInstallerRecreation(): Unit = runBlocking {
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }; val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(listOf(payload, "embedding".toByteArray())); val gateway = AiTestFixtures.gateway(server)
            val first = AiPackInstaller(root, gateway, catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertThrows(IOException::class.java) { runBlocking { first.install(catalog.default(AiKind.TEXT)) } }
            val offset = first.stagedBytes(catalog.default(AiKind.TEXT)); assertTrue(offset in 1 until payload.size.toLong()); assertNull(first.activePack(AiKind.TEXT))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}").setBody(okio.Buffer().write(payload, offset.toInt(), payload.size - offset.toInt())))
            val restarted = AiPackInstaller(root, gateway, catalog, 43, "x86_64"); restarted.install(catalog.default(AiKind.TEXT))
            server.takeRequest(); assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
            assertArrayEquals(payload, File(restarted.activePack(AiKind.TEXT)!!.directory, "weights.bin").readBytes())
        }
        root.deleteRecursively()
    }

    @Test fun cancellationRetainsPartialButDoesNotActivateAndWriterStopsBeforeRetry(): Unit = runBlocking {
        val payload = ByteArray(128 * 1024) { 7 }; val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(listOf(payload, "embedding".toByteArray())); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)).throttleBody(1024, 40, TimeUnit.MILLISECONDS))
            val job = launch(Dispatchers.Default) { installer.install(catalog.default(AiKind.TEXT)) }
            withTimeout(5000) { while (installer.stagedBytes(catalog.default(AiKind.TEXT)) == 0L) delay(10) }
            job.cancelAndJoin(); val offset = installer.stagedBytes(catalog.default(AiKind.TEXT)); assertTrue(offset in 1 until payload.size.toLong()); assertNull(installer.activePack(AiKind.TEXT))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload))) // A server may ignore Range: replace, never append.
            installer.install(catalog.default(AiKind.TEXT)); assertEquals(payload.size.toLong(), File(installer.activePack(AiKind.TEXT)!!.directory, "weights.bin").length())
        }
        root.deleteRecursively()
    }

    @Test fun wrongRangeIsRejectedAndTorDoesNotUseDirectDownloader(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val target = File(root, "partial.bin").apply { writeBytes(byteArrayOf(1, 2)) }
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/4").setBody("abcd"))
            assertThrows(IOException::class.java) { runBlocking { gateway.downloadAiArtifact("https://models.example/data", target, 4) } }
            assertEquals(2L, target.length()); gateway.configure(NetworkPolicy(tor = true, ready = false))
            assertThrows(IOException::class.java) { runBlocking { gateway.downloadAiArtifact("https://models.example/data", target, 4) } }
            assertEquals(1, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun priorReleaseCatalogIsReverifiedAndRollbackSurvivesAnAppCatalogUpdate(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val oldCatalog = AiTestFixtures.catalog()
            val first = AiPackInstaller(root, gateway, oldCatalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); val old = first.install(oldCatalog.default(AiKind.TEXT))
            server.enqueue(MockResponse().setBody("embedding")); first.install(oldCatalog.default(AiKind.EMBEDDING))
            val newerCatalog = changedCatalog("text-new".toByteArray())
            val updated = AiPackInstaller(root, gateway, newerCatalog, 44, "x86_64")
            assertEquals(old.pack.revision, updated.activePack(AiKind.TEXT)!!.pack.revision)
            server.enqueue(MockResponse().setBody("text-new")); val newer = updated.install(newerCatalog.default(AiKind.TEXT))
            assertNotEquals(old.pack.revision, newer.pack.revision)
            val restarted = AiPackInstaller(root, gateway, newerCatalog, 44, "x86_64")
            assertEquals(old.pack, restarted.rollback(AiKind.TEXT)!!.pack)
            assertEquals("text-a", File(restarted.activePack(AiKind.TEXT)!!.directory, "weights.bin").readText())
            assertTrue(old.directory.exists()); assertTrue(newer.directory.exists())
            restarted.remove(AiKind.TEXT)
            assertFalse(old.directory.exists()); assertFalse(newer.directory.exists()); assertEquals(0L, restarted.localBytes(AiKind.TEXT))
            assertNotNull(restarted.activePack(AiKind.EMBEDDING)); assertEquals(3, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun changedRetainedSignatureCannotAuthorizeRollbackOrAnActivePackage(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val oldCatalog = AiTestFixtures.catalog()
            server.enqueue(MockResponse().setBody("text-a")); val old = AiPackInstaller(root, gateway, oldCatalog, 43, "x86_64").install(oldCatalog.default(AiKind.TEXT))
            val newer = changedCatalog("new-text".toByteArray()); val installer = AiPackInstaller(root, gateway, newer, 44, "x86_64")
            server.enqueue(MockResponse().setBody("new-text")); installer.install(newer.default(AiKind.TEXT))
            File(old.directory, ".catalog.sig").apply { val bytes = readBytes(); bytes[0] = (bytes[0].toInt() xor 1).toByte(); writeBytes(bytes) }
            assertNull(installer.rollback(AiKind.TEXT)); assertEquals(newer.default(AiKind.TEXT), installer.activePack(AiKind.TEXT)!!.pack)
            File(root, "text-active.json").writeText("""{"id":"${old.pack.id}","revision":"${old.pack.revision}"}""")
            assertNull(installer.activePack(AiKind.TEXT))
        }
        root.deleteRecursively()
    }

    @Test fun retainedManifestCannotCrossEngineKindsOrAnUnsupportedRuntime(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val catalog = AiTestFixtures.catalog()
            val installer = AiPackInstaller(root, gateway, catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); val installed = installer.install(catalog.default(AiKind.TEXT))
            val wrongKind = File(root, "packs/embedding/${installed.pack.directoryName}"); installed.directory.copyRecursively(wrongKind)
            File(root, "text-active.json").copyTo(File(root, "embedding-active.json"))
            assertNull(installer.activePack(AiKind.EMBEDDING)); assertNotNull(installer.activePack(AiKind.TEXT))
            val differentRuntime = changedCatalog("next".toByteArray(), runtimeProfile = "incompatible-runtime-v2")
            val updated = AiPackInstaller(root, gateway, differentRuntime, 44, "x86_64")
            assertNull(updated.activePack(AiKind.TEXT))
            // Explicit removal owns stale revisions even when they cannot load.
            updated.remove(AiKind.TEXT); assertFalse(installed.directory.exists())
        }
        root.deleteRecursively()
    }

    @Test fun rejectedFirstActivationClearsSelectionKeepsFilesAndCanRetry(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); val installed = installer.install(catalog.default(AiKind.TEXT))
            assertNull(installer.rejectActivation(AiKind.TEXT, installed.pack)); assertNull(installer.activePack(AiKind.TEXT))
            assertTrue(installed.directory.exists()); assertTrue(installer.localBytes(AiKind.TEXT) > 0)
            assertEquals(installed.pack, installer.install(installed.pack).pack); assertNotNull(installer.activePack(AiKind.TEXT)); assertEquals(1, server.requestCount)
        }
        root.deleteRecursively()
    }

    @Test fun rejectedReplacementRestoresOldPackWithoutPromotingFailedEditionToFallback(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val catalog = AiTestFixtures.catalog(); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
            server.enqueue(MockResponse().setBody("text-a")); val old = installer.install(catalog.packs[0])
            server.enqueue(MockResponse().setBody("text-b")); val failed = installer.install(catalog.packs[1])
            assertEquals(old.pack, installer.rejectActivation(AiKind.TEXT, failed.pack)!!.pack)
            assertEquals(old.pack, installer.activePack(AiKind.TEXT)!!.pack); assertTrue(failed.directory.exists()); assertNull(installer.rollback(AiKind.TEXT))
        }
        root.deleteRecursively()
    }

    @Test fun staleActivationRejectionCannotUndoLaterSelectionEvenWithSameModelId(): Unit = runBlocking {
        val root = AiTestFixtures.directory()
        MockWebServer().use { server ->
            server.start(); val gateway = AiTestFixtures.gateway(server); val oldCatalog = AiTestFixtures.catalog()
            server.enqueue(MockResponse().setBody("text-a")); val old = AiPackInstaller(root, gateway, oldCatalog, 43, "x86_64").install(oldCatalog.default(AiKind.TEXT))
            val newer = changedCatalog("current".toByteArray()); val installer = AiPackInstaller(root, gateway, newer, 44, "x86_64")
            server.enqueue(MockResponse().setBody("current")); val current = installer.install(newer.default(AiKind.TEXT))
            assertEquals(old.pack.id, current.pack.id); assertNotEquals(old.pack.revision, current.pack.revision)
            assertEquals(current.pack, installer.rejectActivation(AiKind.TEXT, old.pack)!!.pack); assertEquals(current.pack, installer.activePack(AiKind.TEXT)!!.pack)
        }
        root.deleteRecursively()
    }

    @Test fun rejectedActivationClearsSelectionWhenPreviousSignatureOrPayloadIsInvalid(): Unit = runBlocking {
        for (damage in listOf("signature", "payload")) {
            val root = AiTestFixtures.directory()
            MockWebServer().use { server ->
                server.start(); val catalog = AiTestFixtures.catalog(); val installer = AiPackInstaller(root, AiTestFixtures.gateway(server), catalog, 43, "x86_64")
                server.enqueue(MockResponse().setBody("text-a")); val old = installer.install(catalog.packs[0])
                server.enqueue(MockResponse().setBody("text-b")); val failed = installer.install(catalog.packs[1])
                if (damage == "signature") File(old.directory, ".catalog.sig").writeBytes(ByteArray(256)) else File(old.directory, "weights.bin").writeText("wrong!")
                assertNull(installer.rejectActivation(AiKind.TEXT, failed.pack)); assertNull(installer.activePack(AiKind.TEXT)); assertTrue(failed.directory.exists())
                assertFalse(File(root, "text-previous.json").exists())
            }
            root.deleteRecursively()
        }
    }

    private fun changedCatalog(payload: ByteArray, runtimeProfile: String? = null): AiCatalog {
        val json = JsonParser.parseString(AiTestFixtures.catalogBytes().toString(Charsets.UTF_8)).asJsonObject
        json.addProperty("release", "newer-test")
        val pack = json.getAsJsonArray("packs")[0].asJsonObject
        pack.addProperty("revision", "d".repeat(40))
        pack.getAsJsonArray("assets")[0].asJsonObject.apply { addProperty("bytes", payload.size); addProperty("sha256", AiTestFixtures.sha(payload)) }
        if (runtimeProfile != null) json.getAsJsonArray("packs").map { it.asJsonObject }.filter { it["kind"].asString == "TEXT" }.forEach { it.addProperty("runtimeProfile", runtimeProfile) }
        val bytes = json.toString().toByteArray()
        return AiCatalog.verify(bytes, AiTestFixtures.signature(bytes), AiTestFixtures.keys.public)
    }
}
