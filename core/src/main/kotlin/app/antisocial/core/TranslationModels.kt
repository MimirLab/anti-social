/* AntiSocial, GPL-3.0-or-later. Verified offline translation data, never executable downloads. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

data class TranslationAsset(val role: String, val filename: String, val url: String, val bytes: Long, val sha256: String)
data class TranslationPack(val language: String, val version: String, val revision: String, val assets: List<TranslationAsset>) {
    val id: String get() = "$language-en"
    val bytes: Long get() = assets.sumOf { it.bytes }
}

class TranslationCatalog private constructor(val packs: List<TranslationPack>) {
    fun pack(language: String): TranslationPack? = packs.singleOrNull { it.language == language }
    companion object {
        fun verify(bytes: ByteArray, signature: ByteArray, key: PublicKey): TranslationCatalog {
            require(bytes.size in 1..256*1024 && signature.size in 64..2048)
            require(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(signature) }) { "Language catalog signature is invalid" }
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(json["schema"].asString == "antisocial-translation-v1")
            val packs = json.getAsJsonArray("packs").map { entry -> entry.asJsonObject.let { p ->
                TranslationPack(p["language"].asString, p["version"].asString, p["revision"].asString,
                    p.getAsJsonArray("assets").map { row -> row.asJsonObject.let { a -> TranslationAsset(a["role"].asString, a["filename"].asString, a["url"].asString, a["bytes"].asLong, a["sha256"].asString) } })
            } }
            require(packs.size in 1..128 && packs.map { it.language }.distinct().size == packs.size)
            for (pack in packs) {
                require(pack.language.matches(Regex("[a-z]{2,3}(-[A-Z][a-z]{3})?")) && pack.language != "en")
                require(pack.version.matches(Regex("[0-9]+\\.[0-9]+")) && pack.revision.matches(Regex("[a-f0-9]{64}")))
                require(pack.assets.size in 3..4 && pack.assets.map { it.filename }.distinct().size == pack.assets.size)
                require(pack.assets.map { it.role }.toSet() in listOf(setOf("model","lex","vocab"), setOf("model","lex","srcvocab","trgvocab")))
                for (asset in pack.assets) {
                    require(asset.filename.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && asset.filename !in setOf(".",".."))
                    require(asset.bytes in 1..96L*1024*1024 && asset.sha256.matches(Regex("[a-f0-9]{64}")))
                    val uri = java.net.URI(asset.url)
                    require(uri.scheme == "https" && uri.host == "firefox-settings-attachments.cdn.mozilla.net" && uri.userInfo == null && uri.fragment == null)
                }
            }
            return TranslationCatalog(packs)
        }
    }
}

/** Explicit language downloads are protected. Automatically acquired packs
 * share an LRU byte budget and expire. A completed pack appears atomically. */
class TranslationPackStore(private val root: File, private val gateway: NetworkGateway, private val catalog: TranslationCatalog) {
    private val mutation = Mutex()
    fun directory(pack: TranslationPack) = File(root, "packs/${pack.id}-${pack.revision}")
    private fun marker(pack: TranslationPack) = pack.id+":"+pack.revision+":"+pack.assets.joinToString(",") { it.sha256 }
    private fun footprint(pack:TranslationPack)=pack.bytes+marker(pack).toByteArray(Charsets.UTF_8).size
    fun installed(pack: TranslationPack): File? = directory(pack).takeIf { dir ->
        File(dir,".complete").takeIf { it.isFile && it.length() <= 1024 }?.readText() == marker(pack) &&
            pack.assets.all { a -> File(dir,a.filename).let { it.isFile && it.length() == a.bytes } }
    }
    fun protected(pack: TranslationPack) = File(directory(pack),".keep").isFile
    fun hasDownload(pack:TranslationPack)=listOf("packs","staging").any {area->File(root,area).listFiles().orEmpty().any {it.name.startsWith(pack.id+"-")}}
    fun touch(pack: TranslationPack, now: Long = System.currentTimeMillis()) { File(directory(pack),".complete").setLastModified(now) }
    suspend fun verify(pack: TranslationPack): File = withContext(Dispatchers.IO) {
        val dir = installed(pack) ?: error("This language needs a download")
        for (asset in pack.assets) if (sha(File(dir,asset.filename)) != asset.sha256) { File(dir,".complete").delete(); error("This language download could not be verified") }
        touch(pack); dir
    }
    suspend fun install(pack: TranslationPack, keep: Boolean, budget: Long, progress: (Long,Long)->Unit = {_,_->}): File = mutation.withLock { withContext(Dispatchers.IO) {
        require(pack in catalog.packs)
        installed(pack)?.let { dir -> if (keep) File(dir,".keep").writeText(""); return@withContext verify(pack) }
        root.mkdirs()
        if (!keep) {
            val name="${pack.id}-${pack.revision}"
            pruneUnlocked(budget - footprint(pack), setOf(name))
            check(automaticDirectories().filter {it.name!=name}.sumOf(::size) + footprint(pack) <= budget) { "Automatic language storage is full; download this language from settings" }
        }
        check(root.usableSpace > pack.bytes + 256L*1024*1024) { "Free some storage before downloading a language" }
        val stage = File(root,"staging/${pack.id}-${pack.revision}").apply { mkdirs() }
        if(keep)File(stage,".keep").writeText("")
        var completed = 0L
        for (asset in pack.assets) {
            currentCoroutineContext().ensureActive()
            val file = File(stage,asset.filename)
            if (file.length() > asset.bytes) file.delete()
            gateway.downloadAiArtifact(asset.url,file,asset.bytes) { progress(completed+it,pack.bytes) }
            if (sha(file) != asset.sha256) { file.delete(); error("A language file failed its integrity check") }
            completed += asset.bytes
        }
        if (keep) File(stage,".keep").writeText("")
        File(stage,".complete").writeText(marker(pack))
        currentCoroutineContext().ensureActive()
        val destination = directory(pack); destination.parentFile!!.mkdirs()
        if (destination.exists()) destination.deleteRecursively()
        check(stage.renameTo(destination)) { "The completed language could not be activated" }
        touch(pack); destination
    } }
    suspend fun remove(pack: TranslationPack) = mutation.withLock { withContext(Dispatchers.IO) {
        for (area in listOf("packs","staging")) File(root,area).listFiles().orEmpty()
            .filter { it.name.startsWith(pack.id+"-") && it.name.substringAfterLast('-').matches(Regex("[a-f0-9]{64}")) }.forEach { it.deleteRecursively() }
    } }
    fun localBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun size(directory:File)=directory.walkTopDown().filter {it.isFile}.sumOf {it.length()}
    private fun automaticDirectories()=listOf("packs","staging").flatMap {File(root,it).listFiles().orEmpty().toList()}.filter {!File(it,".keep").isFile}
    fun automaticBytes(): Long = automaticDirectories().sumOf(::size)
    suspend fun prune(budget: Long, clear: Boolean = false) = mutation.withLock { withContext(Dispatchers.IO) { pruneUnlocked(if (clear) 0 else budget, emptySet()) } }
    private fun pruneUnlocked(budget: Long, exclude: Set<String>) {
        val now = System.currentTimeMillis()
        val candidates = automaticDirectories().filter {dir->dir.name !in exclude}
            .sortedBy {File(it,".complete").takeIf(File::isFile)?.lastModified() ?: it.lastModified()}
        var bytes = candidates.sumOf(::size)
        for (dir in candidates) if (bytes > budget.coerceAtLeast(0) || now - (File(dir,".complete").takeIf(File::isFile)?.lastModified() ?: dir.lastModified()) > (if(dir.parentFile.name=="staging")7L else 90L)*24*60*60*1000) {
            val removed = size(dir)
            if (dir.deleteRecursively()) bytes -= removed
        }
        // Interrupted automatic transfers are temporary, never immortal data.
        File(root,"staging").listFiles().orEmpty().filter { !File(it,".keep").isFile && it.name !in exclude && now-it.lastModified() > 7L*24*60*60*1000 }.forEach { it.deleteRecursively() }
    }
    private suspend fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(128*1024); while(true) { currentCoroutineContext().ensureActive(); val n=input.read(bytes); if(n<0)break; digest.update(bytes,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
