package app.antisocial.core

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.GZIPOutputStream

class NativeRuntimeInstallerTest {
    private fun hash(bytes: ByteArray)=AiTestFixtures.sha(bytes)
    private fun elf()=ByteArray(192){(it*7).toByte()}.apply {"\u007fELF".toByteArray().copyInto(this);this[4]=2;this[5]=1;this[18]=62;this[19]=0}
    private fun gzip(bytes: ByteArray)=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(bytes)}}.toByteArray()
    private fun bzip(bytes: ByteArray)=ByteArrayOutputStream().also {out->BZip2CompressorOutputStream(out).use {it.write(bytes)}}.toByteArray()
    private fun number(n: Long): ByteArray=ByteArray(8).also {bytes->var v=kotlin.math.abs(n);for(i in 0..7){bytes[i]=(v and 255).toByte();v=v ushr 8};if(n<0)bytes[7]=(bytes[7].toInt() or 128).toByte()}
    private fun patch(base: ByteArray,target: ByteArray): ByteArray {
        val control=bzip(number(base.size.toLong())+number((target.size-base.size).toLong())+number(0))
        val diff=bzip(ByteArray(base.size){(target[it]-base[it]).toByte()});val extra=bzip(target.copyOfRange(base.size,target.size))
        return "BSDIFF40".toByteArray()+number(control.size.toLong())+number(diff.size.toLong())+number(target.size.toLong())+control+diff+extra
    }
    private fun pack(raw: ByteArray,payload: ByteArray)=NativeRuntimePack("x86_64",raw.size.toLong(),hash(raw),"x86_64.packed",payload.size.toLong(),hash(payload))
    private fun tar(name: String,bytes: ByteArray,type: Int=48): ByteArray {
        val header=ByteArray(512);name.toByteArray().copyInto(header);bytes.size.toString(8).padStart(11,'0').toByteArray().copyInto(header,124);header[156]=type.toByte()
        return gzip(header+bytes+ByteArray((512-bytes.size%512)%512)+ByteArray(1024))
    }
    @Test fun signedApkManifestRejectsUnsafePayloadPathsAndWrongDonorProtocols() {
        val raw=elf();val zip=gzip(raw);val p=pack(raw,zip)
        fun parse(p: NativeRuntimePack)=NativeRuntimePack.read("""{"schema":"antisocial-native-runtime-v1","packs":[${Gson().toJson(p)}]}""".toByteArray(),"x86_64")
        assertEquals(p,parse(p));assertNull(NativeRuntimePack.read("""{"schema":"antisocial-native-runtime-v1","packs":[${Gson().toJson(p)}]}""".toByteArray(),"arm64-v8a"))
        for(bad in listOf(p.copy(payload="../engine.so"),p.copy(bytes=Long.MAX_VALUE),p.copy(sha256="x"),p.copy(donorUrl="http://models.example/donor")))
            assertThrows(IllegalArgumentException::class.java){parse(bad)}
    }
    @Test fun compressedInstallerPublishesExactReadOnlyEngineAndRemovalKeepsSiblingData()=runBlocking {
        val parent=AiTestFixtures.directory();val root=File(parent,"runtime");val saved=File(parent,"saved").apply {writeText("protected")};val raw=elf();val zip=gzip(raw)
        var reads=0;val installer=NativeRuntimeInstaller(root,pack(raw,zip),{reads++;zip.inputStream()})
        try {
            assertFalse(installer.installed());assertEquals(0,reads)
            val library=installer.install();assertArrayEquals(raw,library.readBytes());assertEquals(library,installer.verifiedLibrary())
            installer.install();assertEquals(1,reads);assertEquals(raw.size.toLong(),installer.localBytes())
            installer.remove();assertEquals(0L,installer.localBytes());assertEquals("protected",saved.readText())
            installer.install();assertEquals(2,reads)
        }finally {parent.deleteRecursively()}
    }
    @Test fun truncatedChangedOversizedAndWrongArchitecturePayloadsNeverActivate()=runBlocking {
        val raw=elf();val zip=gzip(raw)
        for((p,bytes) in listOf(pack(raw,zip) to zip.dropLast(3).toByteArray(),pack(raw,zip) to (zip+1),pack(raw,zip).copy(sha256="0".repeat(64)) to zip,
            pack(raw.apply {this[18]=1},gzip(raw)) to gzip(raw))) {
            val root=AiTestFixtures.directory();val i=NativeRuntimeInstaller(root,p,{bytes.inputStream()})
            try {assertThrows(Exception::class.java){runBlocking {i.install()}};assertFalse(i.installed());assertEquals(0L,i.localBytes())}finally {root.deleteRecursively()}
        }
    }
    @Test fun lowStorageFailsBeforeReadingOrDownloading()=runBlocking {
        val root=AiTestFixtures.directory();val raw=elf();val zip=gzip(raw);var reads=0
        try {assertThrows(IllegalStateException::class.java){runBlocking {NativeRuntimeInstaller(root,pack(raw,zip),{reads++;zip.inputStream()},availableBytes={0}).install()}};assertEquals(0,reads)}finally {root.deleteRecursively()}
    }
    @Test fun interruptedPublicationScratchIsRemovedAndCorruptInstalledCodeCanBeRepaired(): Unit=runBlocking {
        val root=AiTestFixtures.directory();val raw=elf();val zip=gzip(raw);val i=NativeRuntimeInstaller(root,pack(raw,zip),{zip.inputStream()})
        try {
            val library=i.install();val orphan=File(root,"staging/donor.bin").apply {parentFile!!.mkdirs();writeBytes(ByteArray(1000))}
            i.install();assertFalse(orphan.exists());assertEquals(raw.size.toLong(),i.localBytes())
            library.setWritable(true);library.writeBytes(raw.copyOf().apply {this[80]=0})
            assertThrows(IllegalStateException::class.java){i.verifiedLibrary()}
            i.install();assertArrayEquals(raw,i.verifiedLibrary().readBytes())
        }finally {root.deleteRecursively()}
    }
    @Test fun binaryDeltaCombinesChangedBytesLiteralTailAndRejectsCorruption(): Unit=runBlocking {
        val root=AiTestFixtures.directory();val base=ByteArray(100){it.toByte()};val target=ByteArray(192){(it*13).toByte()}
        val donor=File(root,"donor").apply {writeBytes(base)};val delta=File(root,"patch").apply {writeBytes(patch(base,target))}
        try {
            val result=ByteArrayOutputStream();BinaryRuntimePatch.apply(donor,delta,result,target.size.toLong());assertArrayEquals(target,result.toByteArray())
            assertThrows(IllegalStateException::class.java){runBlocking {BinaryRuntimePatch.apply(donor,delta,ByteArrayOutputStream(),target.size.toLong()+1)}}
            delta.writeBytes(delta.readBytes().apply {this[0]=0});assertThrows(IllegalStateException::class.java){runBlocking {BinaryRuntimePatch.apply(donor,delta,ByteArrayOutputStream(),target.size.toLong())}}
        }finally {root.deleteRecursively()}
    }
    @Test fun donorUsesGatewayAndOnlyRequestedRegularMemberThenDeletesAllScratch()=runBlocking {
        val raw=elf();val base=raw.take(128).toByteArray();val delta=patch(base,raw);val archive=tar("./bank.so",base)
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(okio.Buffer().write(archive)))
            val root=AiTestFixtures.directory();val p=pack(raw,delta).copy(payload="x86_64.bsdiff",donorUrl="https://models.example/donor",donorArchiveBytes=archive.size.toLong(),donorArchiveSha256=hash(archive),donorMember="./bank.so",donorBytes=base.size.toLong(),donorSha256=hash(base))
            val i=NativeRuntimeInstaller(root,p,{delta.inputStream()},AiTestFixtures.gateway(server))
            try {assertArrayEquals(raw,i.install().readBytes());assertEquals(1,server.requestCount);assertFalse(File(root,"staging").exists());assertEquals(raw.size.toLong(),i.localBytes())}finally {root.deleteRecursively()}
        }
    }
    @Test fun corruptAndSymbolicDonorsNeverPublishOrExecute()=runBlocking {
        val raw=elf();val delta=patch(raw,raw)
        for(type in listOf(48,50))MockWebServer().use {server->
            server.start();val archive=tar("./bank.so",raw,type);server.enqueue(MockResponse().setBody(okio.Buffer().write(archive)))
            val root=AiTestFixtures.directory();val p=pack(raw,delta).copy(payload="x86_64.bsdiff",donorUrl="https://models.example/donor",donorArchiveBytes=archive.size.toLong(),donorArchiveSha256=hash(archive),donorMember="./bank.so",donorBytes=raw.size.toLong(),donorSha256=if(type==48)"0".repeat(64)else hash(raw))
            val i=NativeRuntimeInstaller(root,p,{delta.inputStream()},AiTestFixtures.gateway(server))
            try {assertThrows(IllegalStateException::class.java){runBlocking {i.install()}};assertFalse(i.installed());assertEquals(0L,i.localBytes())}finally {root.deleteRecursively()}
        }
    }
    @Test fun selectedTorFailsClosedWithoutRequestingTheDonor()=runBlocking {
        val raw=elf();val zip=gzip(raw);MockWebServer().use {server->
            server.start();val root=AiTestFixtures.directory();val gateway=AiTestFixtures.gateway(server).apply {configure(NetworkPolicy(tor=true,ready=false))}
            val p=pack(raw,zip).copy(donorUrl="https://models.example/donor",donorArchiveBytes=100)
            try {assertThrows(Exception::class.java){runBlocking {NativeRuntimeInstaller(root,p,{zip.inputStream()},gateway).install()}};assertEquals(0,server.requestCount)}finally {root.deleteRecursively()}
        }
    }
    @Test fun cancellationDuringInflationCannotPublishExecutableOrKeepScratch()=runBlocking {
        val root=AiTestFixtures.directory();val raw=elf();val zip=gzip(raw);val ready=CompletableDeferred<Unit>();val wait=CompletableDeferred<Unit>()
        val i=NativeRuntimeInstaller(root,pack(raw,zip),{zip.inputStream()})
        try {
            val job=launch {i.install {ready.complete(Unit);runBlocking {wait.await()}}}
            ready.await();job.cancel();wait.complete(Unit);job.join();assertFalse(i.installed());assertEquals(0L,i.localBytes())
        }finally {root.deleteRecursively()}
    }
}
