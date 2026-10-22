package app.antisocial.android

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All mutation is restricted to the disposable fixture device. No root used. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeDeliveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun fixture()=assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
    @Test fun engineInstallsLoadsInWorkerRemovesAndReinstallsWithoutTouchingModels()=runBlocking {
        fixture();val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady();val manager=repo.ai;val enabled=manager.preferences.enabled
        val sentinel=File(context.filesDir,"local-ai/runtime-fixture-sentinel").apply {parentFile!!.mkdirs();writeText("keep model data")}
        val client=LocalAiClient(context)
        try {
            manager.removeEngine();assertFalse(manager.runtimeInstaller.installed())
            val failure=runCatching {client.request(AiIpc.CAPABILITIES)}.exceptionOrNull()
            assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("Install the local text engine"))
            client.terminate();manager.enabled(true);manager.installEngine()
            assertTrue(manager.state.value.engineInstalled)
            val first=client.request(AiIpc.CAPABILITIES);val pid=first.getInt("workerPid")
            val capabilities=JSONObject(first.getString("diagnostics")!!)
            assertTrue(capabilities.getString("revision").endsWith("+antisocial-kv-v3"))
            assertNotEquals(android.os.Process.myPid(),pid)
            coroutineScope {val firstRemoval=async {manager.removeEngine()};val secondRemoval=async {manager.removeEngine()};firstRemoval.await();secondRemoval.await()}
            assertFalse(manager.runtimeInstaller.installed());assertEquals(0L,manager.state.value.engineBytes)
            assertEquals("keep model data",sentinel.readText())
            assertFalse(context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses.orEmpty().any {it.pid==pid})
            val interrupted=launch(start=CoroutineStart.UNDISPATCHED){manager.removeEngine()}
            interrupted.cancelAndJoin();assertFalse(manager.state.value.working)
            manager.installEngine();val second=client.request(AiIpc.CAPABILITIES)
            assertNotEquals(pid,second.getInt("workerPid"))
            File(context.filesDir,"runtime-delivery-native.json").writeText(JSONObject().put("passed",true).put("firstPid",pid).put("secondPid",second.getInt("workerPid")).put("capabilities",capabilities).toString(2))
        }finally {client.terminate();manager.removeEngine();sentinel.delete();manager.enabled(enabled)}
    }
    @Test fun realArmInstallerReconstructsExactPhoneBinaryOnAndroidWithBoundedScratch()=runBlocking {
        fixture();assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialRuntimeDonor")=="true")
        val pack=NativeRuntimePack.read(context.assets.open("native-runtime/catalog.json").use {it.readBytes()},"arm64-v8a")!!
        val root=File(context.noBackupFilesDir,"arm-runtime-fixture")
        val live=InstrumentationRegistry.getArguments().getString("antisocialRuntimeDownload")=="true"
        val staged=File(context.filesDir,"runtime-fixtures/upstream.tar.gz")
        val archive=File(root,"staging/upstream.tar.gz")
        if(!live){assertEquals(pack.donorArchiveBytes,staged.length());archive.parentFile!!.mkdirs();staged.copyTo(archive,overwrite=true)}
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady()
        val policy=repo.gateway.currentPolicy()
        if(live)repo.gateway.configure(NetworkPolicy())
        val installer=NativeRuntimeInstaller(root,pack,{context.assets.open("native-runtime/${pack.payload}")},repo.gateway)
        try {
            val before=SystemClock.elapsedRealtime();val library=withTimeout(180_000){installer.install()}
            assertEquals(pack.bytes,library.length());assertEquals(library,installer.verifiedLibrary())
            assertFalse(archive.exists());assertFalse(File(root,"staging").exists());assertEquals(pack.bytes,installer.localBytes())
            File(context.filesDir,if(live)"runtime-delivery-arm-live.json"else "runtime-delivery-arm.json").writeText(JSONObject().put("passed",true).put("liveGatewayDownload",live).put("elapsedMs",SystemClock.elapsedRealtime()-before).put("postimageSha256",pack.sha256).put("patchBytes",pack.payloadBytes).put("runtimeBytes",pack.bytes).toString(2))
            // ARM data is deliberately never dlopened in the x86 emulator.
        }finally {installer.remove();staged.delete();if(live)repo.gateway.configure(policy)}
    }
}
