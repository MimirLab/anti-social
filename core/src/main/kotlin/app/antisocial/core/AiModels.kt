package app.antisocial.core

import com.google.gson.JsonParser
import java.io.File
import java.security.PublicKey
import java.security.Signature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AiKind { TEXT, EMBEDDING }

data class AiModelAsset(val filename: String, val url: String, val bytes: Long, val sha256: String)

/** Declarative weights only. Native executable code is delivered with the app. */
data class AiModelPack(
    val id: String,
    val label: String,
    val revision: String,
    val kind: AiKind,
    val assets: List<AiModelAsset>,
    val minimumAppCode: Int,
    val abis: List<String>,
    val tensorSchema: String,
    val runtimeProfile: String,
    val tokenizerIdentity: String,
    val headIdentity: String,
    val templateIdentity: String,
    val language: String,
    val default: Boolean = false
) {
    val downloadBytes: Long get() = assets.sumOf { it.bytes }
    internal val directoryName: String get() = "$id-$revision"
}

/** Constructed only after validating the application's release signature. */
class AiCatalog private constructor(
    val packs: List<AiModelPack>,
    val release: String,
    private val verifiedBytes: ByteArray,
    private val verifiedSignature: ByteArray,
    private val releaseKey: PublicKey
) {
    fun default(kind: AiKind): AiModelPack = packs.single { it.kind == kind && it.default }
    fun pack(id: String): AiModelPack? = packs.firstOrNull { it.id == id }
    internal fun signedBytes(): ByteArray = verifiedBytes.copyOf()
    internal fun signatureBytes(): ByteArray = verifiedSignature.copyOf()
    /** A retained model remains trusted through an app update only when its
     * original catalog verifies under this installed app's release signer. */
    internal fun verifyRetained(bytes: ByteArray, signature: ByteArray): AiCatalog = verify(bytes, signature, releaseKey)

    companion object {
        fun verify(bytes: ByteArray, signature: ByteArray, releaseKey: PublicKey): AiCatalog {
            require(bytes.size in 1..256 * 1024) { "Model catalog is too large" }
            require(signature.size in 64..2048) { "Invalid catalog signature" }
            require(Signature.getInstance("SHA256withRSA").run { initVerify(releaseKey); update(bytes); verify(signature) }) { "Model catalog signature is invalid" }
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(json["schema"].asString == "antisocial-ai-catalog-v1") { "Unsupported model catalog" }
            val packs = json.getAsJsonArray("packs").map { entry ->
                val value = entry.asJsonObject
                val pack = AiModelPack(
                    id = value["id"].asString, label = value["label"].asString,
                    revision = value["revision"].asString, kind = AiKind.valueOf(value["kind"].asString),
                    assets = value.getAsJsonArray("assets").map { asset -> asset.asJsonObject.let { AiModelAsset(it["filename"].asString, it["url"].asString, it["bytes"].asLong, it["sha256"].asString) } },
                    minimumAppCode = value["minimumAppCode"].asInt,
                    abis = value.getAsJsonArray("abis").map { it.asString },
                    tensorSchema = value["tensorSchema"].asString, runtimeProfile = value["runtimeProfile"].asString,
                    tokenizerIdentity = value["tokenizerIdentity"].asString, headIdentity = value["headIdentity"].asString,
                    templateIdentity = value["templateIdentity"].asString, language = value["language"].asString,
                    default = value["default"]?.asBoolean ?: false
                )
                validate(pack)
                pack
            }
            require(packs.size in 2..32 && packs.map { it.id }.distinct().size == packs.size) { "Invalid model catalog entries" }
            AiKind.entries.forEach { kind -> require(packs.count { it.kind == kind && it.default } == 1) { "Missing default model" } }
            return AiCatalog(packs, json["release"].asString.take(80), bytes.copyOf(), signature.copyOf(), releaseKey)
        }

        private fun validate(pack: AiModelPack) {
            require(pack.id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")))
            require(pack.revision.matches(Regex("[a-f0-9]{40,64}")))
            require(pack.minimumAppCode > 0 && pack.abis.isNotEmpty() && pack.abis.all { it in setOf("arm64-v8a", "x86_64") })
            require(pack.assets.size in 1..16 && pack.assets.map { it.filename }.distinct().size == pack.assets.size)
            require(pack.label.length in 1..100 && pack.runtimeProfile.length in 1..256)
            require(listOf(pack.tensorSchema, pack.tokenizerIdentity, pack.headIdentity, pack.templateIdentity, pack.language).all { it.length in 1..256 })
            pack.assets.forEach { asset ->
                require(asset.filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && asset.filename !in setOf(".", ".."))
                require(asset.bytes in 1..4L * 1024 * 1024 * 1024 && asset.sha256.matches(Regex("[a-f0-9]{64}")))
                val url = asset.url.toHttpUrlOrNull()
                require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Model source must use HTTPS" }
            }
        }
    }
}

data class AiInstalledPack(val pack: AiModelPack, val directory: File)
data class AiInstallProgress(val packId: String, val downloadedBytes: Long, val totalBytes: Long, val verifying: Boolean = false)
