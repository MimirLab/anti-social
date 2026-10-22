/* anti social, GPL-3.0-or-later. Deterministic, bounded native-engine delivery. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** This manifest comes from the signed APK, never from a downloaded document. */
data class NativeRuntimePack(val abi: String, val bytes: Long, val sha256: String,
    val payload: String, val payloadBytes: Long, val payloadSha256: String,
    val donorUrl: String?=null, val donorArchiveBytes: Long=0, val donorArchiveSha256: String?=null,
    val donorMember: String?=null, val donorBytes: Long=0, val donorSha256: String?=null) {
    val delta get()=donorUrl!=null
    companion object {
        fun read(bytes: ByteArray, abi: String): NativeRuntimePack? {
            require(bytes.size in 1..16384)
            val json=JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(json["schema"].asString=="antisocial-native-runtime-v1")
            return json.getAsJsonArray("packs").map {com.google.gson.Gson().fromJson(it,NativeRuntimePack::class.java)}.also {packs->
                require(packs.size in 1..2 && packs.map {it.abi}.distinct().size==packs.size)
                packs.forEach { p->
                    require(p.abi in setOf("arm64-v8a","x86_64") && p.bytes in 64..256L*1024*1024)
                    require(p.payload=="${p.abi}."+if(p.delta)"bsdiff" else "packed")
                    require(p.payloadBytes in 1..64L*1024*1024 && p.sha256.matches(Regex("[a-f0-9]{64}")) && p.payloadSha256.matches(Regex("[a-f0-9]{64}")))
                    if(p.delta) {
                        val url=p.donorUrl!!.toHttpUrlOrNull()
                        require(url!=null&&url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null)
                        require(p.donorArchiveBytes in 1..128L*1024*1024 && p.donorBytes in 64..128L*1024*1024)
                        require(p.donorArchiveSha256?.matches(Regex("[a-f0-9]{64}"))==true && p.donorSha256?.matches(Regex("[a-f0-9]{64}"))==true)
                        require(p.donorMember!=null && p.donorMember.length in 1..128 && !p.donorMember.contains(".."))
                    }
                }
            }.singleOrNull {it.abi==abi}
        }
    }
}

/** Root is private, excluded from backup, and distinct from model/saved storage.
 * The donor is inert data. Only the exact APK-pinned postimage can be loaded. */
class NativeRuntimeInstaller(private val root: File, val pack: NativeRuntimePack,
    private val payload: ()->InputStream, private val gateway: NetworkGateway?=null,
    private val availableBytes: ()->Long={root.usableSpace}) {
    private val mutex=Mutex()
    private val destination get()=File(root,"${pack.sha256}/libantisocial_local_ai.so")
    fun installed(): Boolean=destination.isFile && destination.length()==pack.bytes
    fun localBytes(): Long=if(root.isDirectory)root.walkTopDown().filter {it.isFile}.sumOf {it.length()} else 0
    /** Hash immediately before dlopen, also on every fresh worker after restart. */
    fun verifiedLibrary(): File {
        check(installed()) {"Install the local text engine in AI settings."}
        verify(destination,pack.bytes,pack.sha256)
        check(!java.nio.file.Files.isSymbolicLink(destination.toPath())) {"Invalid local engine location"}
        return destination
    }
    suspend fun install(progress: (AiInstallProgress)->Unit={}): File=mutex.withLock {withContext(Dispatchers.IO) {
        root.mkdirs()
        if(installed() && runCatching {verifiedLibrary()}.isSuccess) {
            // A process can die after atomic publication but before finally.
            File(root,"staging").deleteRecursively()
            return@withContext destination
        }
        val stage=File(root,"staging").apply {mkdirs()}
        val archive=File(stage,"upstream.tar.gz");val donor=File(stage,"donor.bin");val patch=File(stage,"payload.bin");val output=File(stage,"engine.part")
        donor.delete();patch.delete();output.delete()
        val required=pack.bytes+pack.payloadBytes+(if(pack.delta)pack.donorArchiveBytes+pack.donorBytes else 0)+32L*1024*1024
        check(availableBytes()+archive.length()>=required) {"Free more space before installing the local text engine."}
        try {
            if(pack.delta) {
                requireNotNull(gateway) {"Runtime download is unavailable"}.downloadAiArtifact(pack.donorUrl!!,archive,pack.donorArchiveBytes) {
                    progress(AiInstallProgress("Local text engine",it,pack.donorArchiveBytes))
                }
                currentCoroutineContext().ensureActive()
                try {verify(archive,pack.donorArchiveBytes,pack.donorArchiveSha256!!)}catch(e: Exception){archive.delete();throw e}
                extractTarMember(archive,pack.donorMember!!,donor,pack.donorBytes)
                verify(donor,pack.donorBytes,pack.donorSha256!!)
            }
            payload().use {input->FileOutputStream(patch).use {copyExact(input,it,pack.payloadBytes)}}
            verify(patch,pack.payloadBytes,pack.payloadSha256)
            currentCoroutineContext().ensureActive()
            progress(AiInstallProgress("Local text engine",pack.donorArchiveBytes,pack.donorArchiveBytes,verifying=true))
            // Open first, then make the inode read-only before writing code.
            // The open FD remains writable; no writable executable is published.
            output.delete()
            FileOutputStream(output).use {out->
                check(output.setReadOnly()) {"Cannot protect the local text engine"}
                if(pack.delta)BinaryRuntimePatch.apply(donor,patch,out,pack.bytes)
                else GZIPInputStream(patch.inputStream()).use {copyExact(it,out,pack.bytes)}
                out.fd.sync()
            }
            verify(output,pack.bytes,pack.sha256)
            RandomAccessFile(output,"r").use {elf->
                check(elf.readInt()==0x7f454c46 && elf.readUnsignedByte()==2 && elf.readUnsignedByte()==1) {"Invalid native engine"}
                elf.seek(18);val machine=elf.readUnsignedByte() or (elf.readUnsignedByte() shl 8)
                check(machine==if(pack.abi=="arm64-v8a")183 else 62) {"Engine architecture does not match this device"}
            }
            currentCoroutineContext().ensureActive()
            destination.parentFile!!.mkdirs()
            if(destination.exists())check(destination.delete())
            check(output.renameTo(destination)) {"Could not activate the local text engine"}
            root.listFiles().orEmpty().filter {it.isDirectory&&it.name!=pack.sha256&&it.name!="staging"}.forEach {it.deleteRecursively()}
            archive.delete()
            destination
        }finally {
            // Keep only the single bounded, resumable network partial on pause.
            donor.delete();patch.delete();output.delete()
            if(archive.length()==pack.donorArchiveBytes)archive.delete()
            if(stage.listFiles().isNullOrEmpty())stage.delete()
        }
    }}
    /** Controller has already cancelled work and stopped the owning worker. */
    suspend fun remove()=mutex.withLock {withContext(Dispatchers.IO) {check(!root.exists()||root.deleteRecursively()) {"Could not remove the local text engine"}}}
    companion object {
        private fun verify(file: File,bytes: Long,sha: String) {
            check(file.isFile&&file.length()==bytes) {"Local engine download is incomplete"}
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use {input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
            check(hash.digest().joinToString(""){"%02x".format(it)}==sha) {"Local engine integrity check failed; retry setup."}
        }
        internal suspend fun copyExact(input: InputStream,output: OutputStream,size: Long) {
            val b=ByteArray(65536);var left=size
            while(left>0){currentCoroutineContext().ensureActive();val n=input.read(b,0,minOf(b.size.toLong(),left).toInt());check(n>0){"Truncated engine data"};output.write(b,0,n);left-=n}
            check(input.read()==-1) {"Engine data exceeds its declared size"}
        }
        /** Only a named regular file is copied. Never creates archive paths,
         * links, directories, executables, or interprets archive instructions. */
        private suspend fun extractTarMember(archive: File,member: String,out: File,size: Long) {
            GZIPInputStream(archive.inputStream().buffered()).use {input->
                val header=ByteArray(512);val buffer=ByteArray(65536);var expanded=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    DataInputStream(input).readFully(header);expanded+=512
                    check(header.any {it!=0.toByte()}) {"Upstream engine component was not found"}
                    fun field(start: Int,length: Int)=header.copyOfRange(start,start+length).takeWhile {it!=0.toByte()}.toByteArray().toString(Charsets.US_ASCII).trim()
                    val length=field(124,12).toLongOrNull(8) ?: error("Invalid upstream archive")
                    check(length>=0&&expanded+length<=512L*1024*1024) {"Upstream archive is too large"}
                    val name=field(0,100);val selected=name==member
                    if(selected)check(header[156].toInt() in listOf(0,48)&&length==size) {"Invalid upstream engine component"}
                    var remaining=length
                    val output=if(selected)FileOutputStream(out)else null
                    try {while(remaining>0){currentCoroutineContext().ensureActive();val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt());check(n>0){"Truncated upstream archive"};output?.write(buffer,0,n);remaining-=n}}finally {output?.close()}
                    if(selected)return
                    val padding=(512-length%512)%512
                    DataInputStream(input).readFully(header,0,padding.toInt());expanded+=length+padding
                }
            }
        }
    }
}

/** BSDIFF40 reconstruction, not an executable patch language. Three bounded
 * bzip2 streams and fixed 64 KiB buffers; no whole-engine allocation. */
internal object BinaryRuntimePatch {
    private fun number(bytes: ByteArray): Long {
        var n=(bytes[7].toInt() and 0x7f).toLong()
        for(i in 6 downTo 0)n=n*256+(bytes[i].toInt() and 255)
        return if(bytes[7].toInt() and 128!=0)-n else n
    }
    private fun readNumber(input: InputStream): Long=number(ByteArray(8).also {DataInputStream(input).readFully(it)})
    private fun section(file: File,start: Long,length: Long): InputStream {
        val input=RandomAccessFile(file,"r");input.seek(start)
        return object: InputStream() {
            var left=length
            override fun read(): Int {if(left==0L)return -1;val v=input.read();if(v>=0)left--;return v}
            override fun read(b: ByteArray,off: Int,len: Int): Int {if(len==0)return 0;if(left==0L)return -1;val n=input.read(b,off,minOf(left,len.toLong()).toInt());if(n>0)left-=n;return n}
            override fun close()=input.close()
        }.buffered()
    }
    suspend fun apply(base: File,patch: File,output: OutputStream,expected: Long) {
        val header=DataInputStream(patch.inputStream()).use {input->val magic=ByteArray(8).also(input::readFully);check(magic.contentEquals("BSDIFF40".toByteArray()));longArrayOf(readNumber(input),readNumber(input),readNumber(input))}
        val c=header[0];val d=header[1]
        check(c>0&&d>=0&&header[2]==expected&&c<=patch.length()-32&&d<=patch.length()-32-c) {"Invalid engine patch bounds"}
        BZip2CompressorInputStream(section(patch,32,c)).use {control->BZip2CompressorInputStream(section(patch,32+c,d)).use {diff->BZip2CompressorInputStream(section(patch,32+c+d,patch.length()-32-c-d)).use {extra->
            RandomAccessFile(base,"r").use {source->
                val b=ByteArray(65536);val original=ByteArray(65536);var old=0L;var written=0L;var operations=0
                while(written<expected) {
                    currentCoroutineContext().ensureActive();check(++operations<=2_000_000) {"Engine patch is too complex"}
                    val x=readNumber(control);val y=readNumber(control);val z=readNumber(control)
                    check(x>=0&&y>=0&&x<=expected-written&&y<=expected-written-x) {"Invalid engine patch instruction"}
                    check(old in -512L*1024*1024..512L*1024*1024 && z in -512L*1024*1024..512L*1024*1024)
                    var left=x
                    while(left>0) {
                        currentCoroutineContext().ensureActive();val n=minOf(left,b.size.toLong()).toInt();DataInputStream(diff).readFully(b,0,n);original.fill(0,0,n)
                        val first=maxOf(old,0L);val end=minOf(old+n,source.length())
                        if(end>first){source.seek(first);source.readFully(original,(first-old).toInt(),(end-first).toInt())}
                        for(i in 0 until n)b[i]=(b[i]+original[i]).toByte()
                        output.write(b,0,n);old+=n;written+=n;left-=n
                    }
                    left=y
                    while(left>0){currentCoroutineContext().ensureActive();val n=minOf(left,b.size.toLong()).toInt();DataInputStream(extra).readFully(b,0,n);output.write(b,0,n);written+=n;left-=n}
                    old+=z
                }
                check(control.read()==-1&&diff.read()==-1&&extra.read()==-1) {"Trailing engine patch data"}
            }
        }}}
    }
}
