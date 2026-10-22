package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

internal object AiTestFixtures {
    val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun directory(): File = File("/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/ai-core-unit").let { it.mkdirs(); Files.createTempDirectory(it.toPath(), "fixture-").toFile() }
    fun gateway(server: MockWebServer): NetworkGateway {
        val cleaner = UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
        return NetworkGateway(cleaner, object : RequestFilter {
            override fun check(url: String, sourceUrl: String, type: String) = FilterDecision(false, rewrittenUrl = if (url.startsWith("https://models.example/")) server.url(url.substringAfter("https://models.example")).toString() else null)
        }, true)
    }
    fun catalogBytes(payloads: List<ByteArray> = listOf("text-a".toByteArray(), "text-b".toByteArray(), "embedding".toByteArray())): ByteArray = JsonObject().apply {
        addProperty("schema", "antisocial-ai-catalog-v1"); addProperty("release", "test")
        add("packs", JsonArray().apply { payloads.forEachIndexed { index, bytes -> add(JsonObject().apply {
            addProperty("id", "pack-$index"); addProperty("label", "Model $index"); addProperty("revision", ('a' + index).toString().repeat(40))
            addProperty("kind", if (index == payloads.lastIndex) "EMBEDDING" else "TEXT"); addProperty("default", index == 0 || index == payloads.lastIndex)
            addProperty("minimumAppCode", 43); add("abis", JsonArray().apply { add("x86_64"); add("arm64-v8a") })
            for (key in listOf("tensorSchema", "runtimeProfile", "tokenizerIdentity", "headIdentity", "templateIdentity", "language")) addProperty(key, "fixture-v1")
            add("assets", JsonArray().apply { add(JsonObject().apply { addProperty("filename", "weights.bin"); addProperty("url", "https://models.example/model-$index.bin"); addProperty("bytes", bytes.size); addProperty("sha256", sha(bytes)) }) })
        }) } })
    }.toString().toByteArray()
    fun signature(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(bytes); sign() }
    fun catalog(payloads: List<ByteArray> = listOf("text-a".toByteArray(), "text-b".toByteArray(), "embedding".toByteArray())): AiCatalog = catalogBytes(payloads).let { AiCatalog.verify(it, signature(it), keys.public) }
}
