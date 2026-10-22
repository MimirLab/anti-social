package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Explicit model downloads are protected data, outside ordinary reader cache.
 * A complete immutable directory is published before an atomic active pointer.
 * Disabling inference belongs to the controller and does not erase this data. */
class AiPackInstaller(
    private val root: File,
    private val gateway: NetworkGateway,
    private val catalog: AiCatalog,
    private val appCode: Int,
    private val abi: String,
    private val availableBytes: () -> Long = { root.usableSpace },
    private val structureValidator: (AiModelPack, File) -> Unit = { _, _ -> }
) {
    private val mutation = Mutex()
    private val packs = File(root, "packs")
    private val staging = File(root, "staging")
    private val storageReserve = 32L * 1024 * 1024

    init { require(root.mkdirs() || root.isDirectory) { "Model storage is unavailable" } }

    fun activePack(kind: AiKind): AiInstalledPack? = readPointer(pointer(kind), kind)

    fun stagedBytes(pack: AiModelPack): Long = pack.assets.sumOf { asset -> File(stageDirectory(pack), asset.filename).takeIf { it.isFile }?.length()?.coerceAtMost(asset.bytes) ?: 0L }

    suspend fun install(pack: AiModelPack, progress: (AiInstallProgress) -> Unit = {}): AiInstalledPack = mutation.withLock {
        withContext(Dispatchers.IO) {
            compatible(pack)
            val destination = packDirectory(pack)
            if (destination.isDirectory && validReceipt(pack, destination)) {
                verify(pack, destination)
                atomicWrite(File(destination, ".catalog.json"), catalog.signedBytes())
                atomicWrite(File(destination, ".catalog.sig"), catalog.signatureBytes())
                activate(pack)
                return@withContext AiInstalledPack(pack, destination)
            }
            val stage = stageDirectory(pack)
            require(stage.mkdirs() || stage.isDirectory) { "Cannot create model staging storage" }
            var existing = 0L
            pack.assets.forEach { asset ->
                val file = File(stage, asset.filename)
                if (file.length() > asset.bytes) require(file.delete()) { "Cannot reset invalid model staging" }
                existing += file.length()
            }
            val remaining = pack.downloadBytes - existing
            if (availableBytes() < remaining + storageReserve) throw IOException("Not enough free space for this model; ${remaining + storageReserve} bytes are needed")
            var completed = 0L
            pack.assets.forEach { asset ->
                currentCoroutineContext().ensureActive()
                val file = File(stage, asset.filename)
                gateway.downloadAiArtifact(asset.url, file, asset.bytes) { bytes ->
                    progress(AiInstallProgress(pack.id, completed + bytes, pack.downloadBytes))
                }
                completed += asset.bytes
                progress(AiInstallProgress(pack.id, completed, pack.downloadBytes))
            }
            progress(AiInstallProgress(pack.id, pack.downloadBytes, pack.downloadBytes, verifying = true))
            verify(pack, stage)
            // Keep the original release attestation with this exact immutable
            // package. A future catalog cannot bless an unsigned old receipt.
            atomicWrite(File(stage, ".catalog.json"), catalog.signedBytes())
            atomicWrite(File(stage, ".catalog.sig"), catalog.signatureBytes())
            atomicWrite(File(stage, ".verified"), receipt(pack))
            currentCoroutineContext().ensureActive()
            require(destination.parentFile.mkdirs() || destination.parentFile.isDirectory)
            if (destination.exists()) require(destination.deleteRecursively()) { "Cannot replace incomplete model storage" }
            if (!stage.renameTo(destination)) throw IOException("Cannot publish the verified model package")
            activate(pack)
            AiInstalledPack(pack, destination)
        }
    }

    /** Revalidates a retained compatible edition before restoring it. */
    suspend fun rollback(kind: AiKind): AiInstalledPack? = mutation.withLock {
        withContext(Dispatchers.IO) {
            val previous = readPointer(pointer(kind, previous = true), kind) ?: return@withContext null
            verify(previous.pack, previous.directory)
            val current = activePack(kind)
            atomicWrite(pointer(kind), pointerData(previous.pack))
            if (current != null) atomicWrite(pointer(kind, previous = true), pointerData(current.pack)) else pointer(kind, previous = true).delete()
            previous
        }
    }

    /** Native preparation can fail after the verified download is published.
     * Reject only that exact activation; a newer user selection must survive.
     * Keep the downloaded directory for retry, but never leave a failed first
     * install advertised as the selected usable engine. */
    suspend fun rejectActivation(kind: AiKind, failed: AiModelPack): AiInstalledPack? = mutation.withLock {
        withContext(Dispatchers.IO) {
            require(failed.kind == kind)
            if (!pointerMatches(pointer(kind), failed)) return@withContext activePack(kind)
            val previous = readPointer(pointer(kind, previous = true), kind)?.takeUnless {
                it.pack.id == failed.id && it.pack.revision == failed.revision
            }
            val restored = if (previous == null) null else try {
                verify(previous.pack, previous.directory)
                previous
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            if (restored != null) atomicWrite(pointer(kind), pointerData(restored.pack))
            else if (pointer(kind).exists() && !pointer(kind).delete()) throw IOException("Cannot clear the unavailable model activation")
            // Rejection consumes this fallback. It must not swap the failed
            // edition into the previous slot as a manual rollback would.
            pointer(kind, previous = true).delete()
            restored
        }
    }

    /** The caller first unloads/cancels this engine. Other engine packs survive. */
    suspend fun remove(kind: AiKind) = mutation.withLock {
        withContext(Dispatchers.IO) {
            pointer(kind).delete()
            pointer(kind, previous = true).delete()
            // Kind directories also contain older signed revisions which the
            // current catalog no longer lists. Explicit removal owns them all.
            for (directory in listOf(kindDirectory(packs, kind), kindDirectory(staging, kind))) {
                if (directory.exists() && !directory.deleteRecursively()) throw IOException("Some model files could not be removed")
            }
            Unit
        }
    }

    fun localBytes(kind: AiKind): Long = listOf(kindDirectory(packs, kind), kindDirectory(staging, kind)).sumOf { directory ->
        if (directory.isDirectory) directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private fun compatible(pack: AiModelPack, sourceCatalog: AiCatalog = catalog) {
        require(sourceCatalog.packs.any { it == pack }) { "Model is not in its signed application catalog" }
        require(appCode >= pack.minimumAppCode) { "This model needs a newer app version" }
        require(abi in pack.abis) { "This model is unavailable for this device architecture" }
        require(catalog.packs.any { it.kind == pack.kind && it.runtimeProfile == pack.runtimeProfile && it.tensorSchema == pack.tensorSchema }) { "This app no longer supports the retained model runtime" }
    }

    private suspend fun verify(pack: AiModelPack, directory: File) {
        pack.assets.forEach { asset ->
            currentCoroutineContext().ensureActive()
            val file = File(directory, asset.filename)
            if (!file.isFile || file.length() != asset.bytes) throw IOException("Model package is incomplete: ${asset.filename}")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != asset.sha256) {
                // A corrupt complete partial cannot be resumed into a good file.
                // The verified active edition, if any, remains untouched.
                if (directory.parentFile == kindDirectory(staging, pack.kind)) file.delete()
                throw IOException("Model integrity check failed: ${asset.filename}; retry the download")
            }
            validateStructure(file)
        }
        structureValidator(pack, directory)
    }

    private fun validateStructure(file: File) {
        when {
            file.name.endsWith(".gguf") -> RandomAccessFile(file, "r").use { input ->
                if (input.length() < 24 || input.readInt() != 0x47475546 || Integer.reverseBytes(input.readInt()) !in 2..3) throw IOException("Invalid generator model structure")
            }
            file.name.endsWith(".json") -> {
                if (file.length() > 4 * 1024 * 1024) throw IOException("Model configuration is too large")
                val json = JsonParser.parseString(file.readText())
                if (!json.isJsonObject && !json.isJsonArray) throw IOException("Invalid model configuration")
            }
            file.name.endsWith(".safetensors") -> RandomAccessFile(file, "r").use { input ->
                val size = java.lang.Long.reverseBytes(input.readLong())
                if (size !in 2..1024 * 1024 || size > input.length() - 8) throw IOException("Invalid model head header")
                val header = ByteArray(size.toInt()).also { input.readFully(it) }
                val tensors = JsonParser.parseString(header.toString(Charsets.UTF_8)).asJsonObject
                tensors.entrySet().filter { it.key != "__metadata__" }.forEach { (_, value) ->
                    val offsets = value.asJsonObject.getAsJsonArray("data_offsets")
                    val start = offsets[0].asLong; val end = offsets[1].asLong
                    if (start < 0 || end < start || end > input.length() - 8 - size) throw IOException("Invalid model head tensor bounds")
                }
            }
        }
    }

    private fun activate(pack: AiModelPack) {
        val current = activePack(pack.kind)
        if (current?.pack?.id == pack.id && current.pack.revision == pack.revision) return
        if (current != null) atomicWrite(pointer(pack.kind, previous = true), pointerData(current.pack))
        atomicWrite(pointer(pack.kind), pointerData(pack))
    }

    private fun pointer(kind: AiKind, previous: Boolean = false) = File(root, kind.name.lowercase() + if (previous) "-previous.json" else "-active.json")
    private fun kindDirectory(parent: File, kind: AiKind) = File(parent, kind.name.lowercase())
    private fun packDirectory(pack: AiModelPack) = File(kindDirectory(packs, pack.kind), pack.directoryName)
    private fun stageDirectory(pack: AiModelPack) = File(kindDirectory(staging, pack.kind), pack.directoryName)
    private fun pointerData(pack: AiModelPack) = JsonObject().apply { addProperty("id", pack.id); addProperty("revision", pack.revision) }.toString().toByteArray()
    private fun pointerMatches(file: File, pack: AiModelPack): Boolean = runCatching {
        if (!file.isFile || file.length() > 2048) return false
        val json = JsonParser.parseString(file.readText()).asJsonObject
        json["id"].asString == pack.id && json["revision"].asString == pack.revision
    }.getOrDefault(false)
    private fun readPointer(file: File, expectedKind: AiKind): AiInstalledPack? = runCatching {
        if (!file.isFile || file.length() > 2048) return null
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val id = json["id"].asString; val revision = json["revision"].asString
        if (!id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")) || !revision.matches(Regex("[a-f0-9]{40,64}"))) return null
        val directory = File(kindDirectory(packs, expectedKind), "$id-$revision")
        val signed = File(directory, ".catalog.json"); val signature = File(directory, ".catalog.sig")
        if (!signed.isFile || signed.length() !in 1..256 * 1024 || !signature.isFile || signature.length() !in 64..2048) return null
        val retainedCatalog = catalog.verifyRetained(signed.readBytes(), signature.readBytes())
        val pack = retainedCatalog.packs.singleOrNull { it.id == id && it.revision == revision && it.kind == expectedKind } ?: return null
        compatible(pack, retainedCatalog)
        if (!validReceipt(pack, directory) || pack.assets.any { File(directory, it.filename).length() != it.bytes }) return null
        AiInstalledPack(pack, directory)
    }.getOrNull()
    private fun receipt(pack: AiModelPack) = (pack.tensorSchema + "\n" + pack.assets.joinToString("\n") { "${it.filename}:${it.bytes}:${it.sha256}" }).toByteArray()
    private fun validReceipt(pack: AiModelPack, directory: File) = runCatching { File(directory, ".verified").let { it.isFile && it.length() <= 8192 && it.readBytes().contentEquals(receipt(pack)) } }.getOrDefault(false)
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
