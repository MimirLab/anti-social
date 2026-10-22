/* AntiSocial, GPL-3.0-or-later. Deterministic local bank and priority boundaries. */
package app.antisocial.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class MentionNameStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var routes: DarwinRoutes
    private val scopes=mutableListOf<CoroutineScope>()
    private val databases=mutableListOf<MentionNameDatabase>()
    private val files=mutableListOf<String>()
    private val now=AtomicLong(1_780_000_000_000L)
    private val foreground=AtomicBoolean()
    private val readable=AtomicBoolean(true)
    private val remote=AtomicBoolean(true)
    private val calls=AtomicInteger()
    @Volatile private var mode=MentionLookupPolicy(NetworkPolicy(),false)
    @Volatile private var retrieve: suspend (MentionAccount)->Profile?={suppliedProfile(it.target,"A public display name")}

    @Before fun setup() {
        fun asset(name: String)=context.assets.open(name).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    }
    @After fun close(): Unit=runBlocking {
        scopes.forEach {it.coroutineContext[Job]?.cancelAndJoin()};databases.forEach {it.close()};files.forEach {context.deleteDatabase(it)}
    }
    private fun suppliedProfile(target: Target,name: String)=Profile(target,name,displayNameSupplied=true)
    private fun account(name: String="nasa",platform: Platform=Platform.X): MentionAccount {
        val url=when(platform){Platform.INSTAGRAM->"https://www.instagram.com/$name/";else->"https://x.com/$name"}
        return MentionNames.account(routes.recognize(url)!!,routes)!!
    }
    private fun store(maxEntries: Int=1024,maxBytes: Long=1024L*1024,database: MentionNameDatabase?=null): MentionNameStore {
        val db=database ?: Room.inMemoryDatabaseBuilder(context,MentionNameDatabase::class.java).build().also {databases+=it}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO).also {scopes+=it}
        return MentionNameStore(db,routes,scope,{readable.get()},{remote.get()},{foreground.get()},{mode},{},
            {calls.incrementAndGet();retrieve(it)},now::get,quietMs=15,pollMs=5,maxEntries=maxEntries,maxBytes=maxBytes)
    }
    private suspend fun eventually(condition: ()->Boolean) {
        withTimeout(5_000){while(!condition())delay(10)}
    }
    private suspend fun stable()=delay(160)

    @Test fun productionDatabaseHelperPersistsNamesAndKeepsJournalsBounded(): Unit=runBlocking {
        val filename="mention-bank-${UUID.randomUUID()}.db";files+=filename
        val firstDb=MentionNameDatabase.open(context,filename).also {databases+=it}
        val first=store(database=firstDb);first.awaitReady();val a=account()
        assertTrue(first.seed(suppliedProfile(a.target,"NASA public organization")))
        val connection=firstDb.openHelper.writableDatabase
        connection.query("PRAGMA journal_mode").use {cursor->assertTrue(cursor.moveToFirst());assertEquals("truncate",cursor.getString(0).lowercase())}
        connection.query("PRAGMA journal_size_limit").use {cursor->assertTrue(cursor.moveToFirst());assertEquals(0L,cursor.getLong(0))}
        val file=context.getDatabasePath(filename)
        assertTrue(file.isFile);assertTrue(first.bytes()>0)
        for(suffix in listOf("-wal","-shm","-journal"))assertEquals("No journal may grow beside the tiny ordinary cache",0L,java.io.File(file.path+suffix).length())
        scopes.last().coroutineContext[Job]?.cancelAndJoin();firstDb.close();databases.remove(firstDb)

        val reopened=MentionNameDatabase.open(context,filename).also {databases+=it}
        val next=store(database=reopened);next.awaitReady()
        assertEquals("NASA public organization",next.names.value[a.key.id])
        assertEquals(1,next.stats.value.entries);assertEquals(0,calls.get())
        val beforeClear=next.bytes();next.clear()
        assertTrue(next.names.value.isEmpty());assertTrue(reopened.names().all().isEmpty())
        assertTrue("Clearing also vacuums reclaimed pages",next.bytes()<=beforeClear)
        reopened.openHelper.writableDatabase.query("PRAGMA journal_size_limit").use {cursor->assertTrue(cursor.moveToFirst());assertEquals(0L,cursor.getLong(0))}
        for(suffix in listOf("-wal","-shm","-journal"))assertEquals(0L,java.io.File(file.path+suffix).length())
    }

    @Test fun repeatedVisibleMentionsShareOneProfileRead(): Unit=runBlocking {
        val bank=store();bank.awaitReady();val a=account()
        val first=bank.observe(listOf(a,a));val second=bank.observe(listOf(a))
        eventually {bank.names.value[a.key.id]=="A public display name"}
        stable();assertEquals(1,calls.get());assertEquals(1,bank.requestedCount)
        first.close();assertEquals(1,bank.requestedCount);second.close();assertEquals(0,bank.requestedCount)
    }
    @Test fun existingPublicProfileMetadataSeedsTheOfflineBank(): Unit=runBlocking {
        val bank=store();bank.awaitReady();val a=account()
        assertTrue(bank.seed(suppliedProfile(a.target,"National Aeronautics and Space Administration")))
        remote.set(false);bank.changed()
        val lease=bank.observe(listOf(a),networkAllowed=false)
        assertEquals("National Aeronautics and Space Administration",bank.names.value[a.key.id])
        stable();assertEquals(0,calls.get());lease.close()
    }
    @Test fun essentialReadsFinishBeforeNameQueriesStart(): Unit=runBlocking {
        foreground.set(true);val bank=store();bank.awaitReady();val a=account();val lease=bank.observe(listOf(a))
        stable();assertEquals(0,calls.get());assertTrue(bank.names.value.isEmpty())
        foreground.set(false);bank.changed();eventually {bank.names.value.containsKey(a.key.id)}
        assertEquals(1,calls.get());lease.close()
    }
    @Test fun foregroundWorkCancelsAnInFlightLookupWithoutBankingAMiss(): Unit=runBlocking {
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        retrieve={started.complete(Unit);try {awaitCancellation()}finally {cancelled.complete(Unit)}}
        val bank=store();bank.awaitReady();val a=account();val lease=bank.observe(listOf(a));started.await()
        foreground.set(true);cancelled.await();stable()
        assertEquals(0,bank.stats.value.entries);assertTrue(bank.names.value.isEmpty())
        retrieve={suppliedProfile(it.target,"National Aeronautics and Space Administration")}
        foreground.set(false);bank.changed();eventually {bank.names.value.containsKey(a.key.id)}
        assertEquals(2,calls.get());lease.close()
    }
    @Test fun leavingTheVisiblePostCancelsRatherThanHarvestsItsAccounts(): Unit=runBlocking {
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        retrieve={started.complete(Unit);try {awaitCancellation()}finally {cancelled.complete(Unit)}}
        val bank=store();bank.awaitReady();val lease=bank.observe(listOf(account()));started.await();lease.close()
        cancelled.await();stable();assertTrue(bank.names.value.isEmpty());assertEquals(0,bank.stats.value.entries)
    }
    @Test fun unavailableSelectedTorNeverQueriesTheProfile(): Unit=runBlocking {
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=false),true)
        val bank=store();bank.awaitReady();val a=account();val lease=bank.observe(listOf(a))
        stable();assertEquals(0,calls.get())
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=true),true);bank.changed()
        eventually {bank.names.value.containsKey(a.key.id)};assertEquals(1,calls.get());lease.close()
    }
    @Test fun changedNetworkPolicyRejectsALateResult(): Unit=runBlocking {
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        retrieve={started.complete(Unit);withContext(NonCancellable){finish.await()};suppliedProfile(it.target,"Late old-network name")}
        val bank=store();bank.awaitReady();val lease=bank.observe(listOf(account()));started.await()
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=false),false);bank.changed();finish.complete(Unit)
        stable();assertTrue(bank.names.value.isEmpty());assertEquals(0,bank.stats.value.entries);lease.close()
    }
    @Test fun wrongAccountsAndAtPrefixedFallbacksRemainUnresolved(): Unit=runBlocking {
        val requested=account();retrieve={suppliedProfile(account("other").target,"Someone Else")}
        val bank=store();bank.awaitReady();val lease=bank.observe(listOf(requested))
        eventually {bank.stats.value.entries==1};assertTrue(bank.names.value.isEmpty());assertEquals(1,calls.get());lease.close()
        assertFalse(bank.seed(suppliedProfile(requested.target,"@nasa")))
        assertTrue(bank.seed(suppliedProfile(requested.target,"NASA")));assertEquals("NASA",bank.names.value[requested.key.id])
    }
    @Test fun syntheticTitlesAndOldProfileRecordsNeverBecomeDisplayNames(): Unit=runBlocking {
        val bank=store();bank.awaitReady();val a=account()
        val generic=Profile(a.target,"Sign in to see more • Social")
        val gson=com.google.gson.Gson()
        val oldJson=gson.toJsonTree(Profile(a.target,"A plausible but unverified account name")).asJsonObject.apply {remove("displayNameSupplied")}
        val old=gson.fromJson(oldJson,Profile::class.java)
        assertFalse(old.displayNameSupplied);assertFalse(bank.seed(old));assertFalse(bank.seed(generic))
        bank.seed(Page(emptyList(),profile=generic,accounts=listOf(old)))
        assertEquals(0,bank.stats.value.entries)
        retrieve={old};val lease=bank.observe(listOf(a));eventually {bank.stats.value.entries==1}
        assertTrue(bank.names.value.isEmpty())
        val raw="A message for @nasa."
        val matches=MentionNames.find(raw,Platform.X,"https://x.com/example/status/123",routes)
        val presented=MentionNames.present(raw,matches,bank.names.value)
        assertEquals("A message for nasa.",presented.text)
        assertEquals(raw,presented.rawText);assertFalse(presented.mentions.single().resolved)
        lease.close()
    }
    @Test fun negativeBackoffSurvivesRestartAndExpiresOnSchedule(): Unit=runBlocking {
        val filename="mentions-${UUID.randomUUID()}.db";files+=filename
        val db=Room.databaseBuilder(context,MentionNameDatabase::class.java,filename).setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build().also {databases+=it}
        retrieve={null};val bank=store(database=db);bank.awaitReady();val a=account();val lease=bank.observe(listOf(a))
        eventually {bank.stats.value.entries==1};assertEquals(1,calls.get());lease.close()
        scopes.last().coroutineContext[Job]?.cancelAndJoin();db.close();databases.remove(db)
        val reopened=Room.databaseBuilder(context,MentionNameDatabase::class.java,filename).build().also {databases+=it}
        val next=store(database=reopened);next.awaitReady();val nextLease=next.observe(listOf(a));stable();assertEquals(1,calls.get())
        now.addAndGet(6*60*60_000L);next.changed();eventually {calls.get()==2};stable();assertTrue(next.names.value.isEmpty());nextLease.close()
        now.addAndGet(30*86_400_000L);next.cleanup();assertEquals(0,next.stats.value.entries)
    }
    @Test fun staleNamesStayReadableWhileAFailedRefreshBacksOff(): Unit=runBlocking {
        val bank=store();bank.awaitReady();val a=account();assertTrue(bank.seed(suppliedProfile(a.target,"NASA space agency")))
        now.addAndGet(7*86_400_000L);retrieve={null};val lease=bank.observe(listOf(a))
        eventually {calls.get()==1};stable();assertEquals("NASA space agency",bank.names.value[a.key.id])
        lease.close();val second=bank.observe(listOf(a));stable();assertEquals(1,calls.get());second.close()
        now.addAndGet(6*60*60_000L);retrieve={suppliedProfile(it.target,"NASA updated public name")}
        val third=bank.observe(listOf(a));eventually {bank.names.value[a.key.id]=="NASA updated public name"};third.close()
    }
    @Test fun changedPrivacyPolicyRetriesMissesButReadyStateAndPortsDoNot(): Unit=runBlocking {
        retrieve={null};val bank=store();bank.awaitReady();val lease=bank.observe(listOf(account()))
        eventually {bank.stats.value.entries==1};assertEquals(1,calls.get())
        bank.changed();stable();assertEquals(1,calls.get())
        mode=MentionLookupPolicy(NetworkPolicy(),true);bank.changed();eventually {calls.get()==2};stable()
        bank.changed();stable();assertEquals(2,calls.get())
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=true,socksPort=9050),true)
        bank.changed();eventually {calls.get()==3};stable()
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=false,socksPort=9150),true);bank.changed();stable()
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=true,socksPort=9150),true);bank.changed();stable()
        assertEquals(3,calls.get())
        mode=MentionLookupPolicy(NetworkPolicy(tor=true,ready=true,socksPort=9150),false)
        bank.changed();eventually {calls.get()==4};stable();assertTrue(bank.names.value.isEmpty());lease.close()
    }
    @Test fun pointerLeaseChurnDoesNotContinuouslyWriteViewCounts(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,MentionNameDatabase::class.java).build().also {databases+=it}
        val bank=store(database=db);bank.awaitReady();val a=account();bank.seed(suppliedProfile(a.target,"NASA agency"))
        repeat(20){bank.observe(listOf(a),false).close()};stable()
        assertEquals(1L,db.names().all().single().viewCount)
        now.addAndGet(10_000);repeat(20){bank.observe(listOf(a),false).close()};stable()
        assertEquals(2L,db.names().all().single().viewCount)
    }
    @Test fun bankEvictsColdNamesAndNeverExceedsItsEntryBudget(): Unit=runBlocking {
        val bank=store(maxEntries=3);bank.awaitReady();val hot=account("hot")
        bank.seed(suppliedProfile(hot.target,"Often read account"))
        repeat(6){now.addAndGet(10_000);bank.observe(listOf(hot),false).close();stable()}
        for(name in listOf("coldone","coldtwo","coldthree","coldfour")) {
            now.addAndGet(1_000);bank.seed(suppliedProfile(account(name).target,"Public $name"))
        }
        assertEquals(3,bank.stats.value.entries);assertEquals("Often read account",bank.names.value[hot.key.id])
        now.addAndGet(30*86_400_000L);bank.cleanup();assertEquals(0,bank.stats.value.entries);assertTrue(bank.names.value.isEmpty())
    }
    @Test fun byteBudgetAndForgetCannotRefillFromExistingLeases(): Unit=runBlocking {
        val bank=store(maxBytes=500);bank.awaitReady();val a=account();val lease=bank.observe(listOf(a))
        eventually {bank.names.value.containsKey(a.key.id)}
        for(name in listOf("one","two","three","four"))bank.seed(suppliedProfile(account(name).target,"Public display name $name"))
        assertTrue(bank.stats.value.entries<=2)
        bank.clear();assertEquals(0,bank.requestedCount);assertTrue(bank.names.value.isEmpty());val before=calls.get()
        stable();assertEquals(before,calls.get());lease.close()
    }
    @Test fun lookupSwitchLeavesCachedLabelsAndMasterSwitchIsReactive(): Unit=runBlocking {
        val bank=store();bank.awaitReady();val a=account();bank.seed(suppliedProfile(a.target,"Known public name"))
        remote.set(false);bank.changed();val lease=bank.observe(listOf(a,account("unknown")))
        stable();assertEquals(0,calls.get());assertEquals("Known public name",bank.names.value[a.key.id]);assertTrue(bank.enabled.value)
        readable.set(false);bank.changed();assertFalse(bank.enabled.value);assertEquals(0,calls.get());lease.close()
    }
    @Test fun demandQueueIsBoundedAndCrossPlatformHandlesDoNotCollide(): Unit=runBlocking {
        foreground.set(true);val bank=store();bank.awaitReady()
        val a=account();val b=account("nasa",Platform.INSTAGRAM)
        bank.seed(suppliedProfile(a.target,"NASA on X"));bank.seed(suppliedProfile(b.target,"NASA on Instagram"))
        assertEquals(2,bank.names.value.size);assertNotEquals(a.key.id,b.key.id)
        val lease=bank.observe((1..300).map {account("test$it")});assertEquals(128,bank.requestedCount);lease.close()
    }
}
