/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AnywhereConnection(val title: String="Not checked yet",val detail: String="The shared discussion service is checked when you open comments.",val checkedAt: Long?=null,val retryAt: Long?=null,val checking: Boolean=false)
private val unknownAnywhereConnection=MutableStateFlow(AnywhereConnection())
interface AnywhereAccess {
    val enabled: Boolean get()=true
    val connection: StateFlow<AnywhereConnection> get()=unknownAnywhereConnection
    suspend fun read(url: String,order: CommentOrder,force: Boolean=false,limit: Int=30,parentId: String?=null): AnywherePage
    suspend fun mutate(url: String,order: CommentOrder,action: AnywhereAction,text: String="",id: String?=null,value: Int?=null)
    suspend fun owner(): String?
    suspend fun forget()
    fun clear()
    suspend fun check(){read("https://commentanywhere.app/",CommentOrder.NEWEST,force=true,limit=1)}
}

/** Device-only credentials. Android backup is disabled; neither this directory
 * nor its keystore key is part of AntiSocial's portable settings/library export. */
internal class AnywhereVault(context: Context): AnywhereIdentityStore {
    private val file=File(context.noBackupFilesDir,"commentanywhere.identity")
    private val alias="antisocial.commentanywhere.identity.v1"
    private val mutex=Mutex()
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun load(): AnywhereIdentity?=withContext(Dispatchers.IO){mutex.withLock {
        if(!file.exists())return@withLock null
        try {
            require(file.length() in 30..32_768)
            val bytes=file.readBytes();require(bytes[0]==1.toByte())
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))}
            com.google.gson.Gson().fromJson(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8),AnywhereIdentity::class.java)
                .also {require(it.uid.isNotBlank()&&it.token.isNotBlank()&&it.refreshToken.isNotBlank())}
        }catch(e: Exception){throw IllegalStateException("Your CommentAnywhere identity could not be unlocked. Reset it only if you no longer need ownership of earlier comments.")}
    }}
    override suspend fun save(identity: AnywhereIdentity)=withContext(Dispatchers.IO){mutex.withLock {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val encoded=cipher.doFinal(com.google.gson.Gson().toJson(identity).toByteArray())
        val atomic=android.util.AtomicFile(file);val out=atomic.startWrite()
        try {out.write(byteArrayOf(1)+cipher.iv+encoded);atomic.finishWrite(out)}catch(e: Exception){atomic.failWrite(out);throw e}
    }}
    suspend fun forget()=withContext(Dispatchers.IO){mutex.withLock {android.util.AtomicFile(file).delete();KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)}}
}

/** No background polling, page-view registration, diagnostics or notification
 * traffic. A server failure backs off globally, even when switching posts. */
class AnywhereStore(private val repo: Repository): AnywhereAccess {
    override val enabled get()=repo.prefs.communityComments
    override val connection=MutableStateFlow(AnywhereConnection())
    private val vault=AnywhereVault(repo.context)
    private val client=CommentAnywhereClient(repo.gateway,vault)
    private val reads=Mutex()
    private val writes=reads // One service operation at a time, including cooldown updates.
    private data class Key(val url: String,val order: CommentOrder,val tor: Boolean,val limit: Int,val parent: String?)
    private val cache=linkedMapOf<Key,AnywherePage>()
    private var blockedUntil=0L
    private var failures=0
    override suspend fun owner()=client.identity()?.uid
    override suspend fun forget(){writes.withLock {vault.forget();clear()}}
    @Synchronized override fun clear(){cache.clear()}
    @Synchronized private fun cached(key: Key): AnywherePage? {
        val now=System.currentTimeMillis();cache.entries.removeAll {now-it.value.fetchedAt>5*60_000}
        return cache[key]
    }
    @Synchronized private fun remember(key: Key,page: AnywherePage) {
        cache.remove(key);cache[key]=page
        while(cache.size>12||cache.values.sumOf {it.flattened().sumOf {entry->entry.first.text.length*2L+1024}}>2*1024*1024)cache.remove(cache.keys.first())
    }
    @Synchronized private fun invalidate(url: String){cache.keys.removeAll {it.url==url}}
    override suspend fun read(url: String,order: CommentOrder,force: Boolean,limit: Int,parentId: String?): AnywherePage=reads.withLock {
        require(enabled){"Shared community comments are off. You can enable them in Settings → Comments."}
        repo.awaitReady()
        val key=Key(url,order,repo.gateway.currentPolicy().tor,limit,parentId)
        if(!force)cached(key)?.let {return@withLock it}
        val now=System.currentTimeMillis()
        if(now<blockedUntil)throw IllegalStateException("The community service is resting after an error. Try again in ${((blockedUntil-now+999)/1000).coerceAtLeast(1)} seconds. Other comment streams remain available.")
        connection.value=connection.value.copy(checking=true)
        try {
            withTimeout(45_000){client.read(url,order,limit,parentId)}.also {failures=0;blockedUntil=0;remember(key,it);connection.value=AnywhereConnection("Connected","The service supplied a valid public discussion.",System.currentTimeMillis())}
        }catch(e: CancellationException){
            if(e !is TimeoutCancellationException){connection.value=connection.value.copy(checking=false);throw e}
            val failedAt=System.currentTimeMillis()
            failures=(failures+1).coerceAtMost(5);blockedUntil=failedAt+30_000L*(1L shl (failures-1))
            val detail="The community service timed out. Other comment streams remain available."
            connection.value=AnywhereConnection("Connection timed out",detail,failedAt,blockedUntil)
            throw IllegalStateException(detail)
        }
        catch(e: Exception) {
            if(repo.gateway.currentPolicy().tor&&!repo.gateway.currentPolicy().ready){connection.value=AnywhereConnection("Waiting for Tor","Requests stay paused until your private connection is ready.",now);throw IllegalStateException("Tor is connecting. Community comments will wait for your private connection.")}
            failures=(failures+1).coerceAtMost(5)
            val failedAt=System.currentTimeMillis()
            blockedUntil=failedAt+maxOf((e as? SourceFailure)?.retryAfterMs ?: 0,30_000L*(1L shl (failures-1)))
            val code=(e as? SourceFailure)?.httpStatus
            val title=when {code!=null&&code>=500->"Service temporarily unavailable";code==429->"Service is rate limited";code==401||code==403->"Service refused public reading";else->"Unable to connect"}
            val detail=when {code!=null&&code>=500->"The community server returned HTTP $code. No login or key is needed for reading; please try later.";code!=null->"The community service returned HTTP $code. Other comment streams remain available.";e.message?.contains("Blocked by privacy filters")==true->"Network protection blocked the community service. You can review recent blocks in Privacy & connection.";else->"The community service could not be reached. Other comment streams remain available."}
            connection.value=AnywhereConnection(title,detail,failedAt,blockedUntil)
            throw IllegalStateException(detail)
        }
    }
    override suspend fun mutate(url: String,order: CommentOrder,action: AnywhereAction,text: String,id: String?,value: Int?)=writes.withLock {
        require(enabled){"Shared community comments are off."}
        repo.awaitReady()
        require(System.currentTimeMillis()>=blockedUntil){"CommentAnywhere is resting after a service error. Please return later."}
        try {withTimeout(45_000){client.mutate(url,order,action,text,id,value)}}
        catch(e: Exception) {
            if(e !is CancellationException||e is TimeoutCancellationException) {
                failures=(failures+1).coerceAtMost(5)
                blockedUntil=System.currentTimeMillis()+maxOf((e as? SourceFailure)?.retryAfterMs ?: 0,30_000L*(1L shl (failures-1)))
            }
            throw e
        }
        finally {invalidate(url)}
    }
}
