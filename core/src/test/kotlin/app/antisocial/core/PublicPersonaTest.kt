/* anti social, GPL-3.0-or-later. Custom persona consistency and state boundaries. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class PublicPersonaTest {
    private val source=Source(Platform.X,"original","https://x.com",role=SourceRole.ORIGINAL)
    private val target=Target(Platform.X,TargetKind.PROFILE,"NASA","https://x.com/NASA")
    private val url="https://x.com/NASA"
    private class Fixture(capacity:Int=16) {
        var now=System.currentTimeMillis();private var nonce=0
        var session=NetworkSession(NetworkPolicy())
        val bank=PublicPersonaBank(sessionFor={session},clock={now},entropy={bytes->bytes.fill((++nonce).toByte())},capacity=capacity,lifetimeMillis=1000)
    }
    @Test fun wholeCohortsKeepReducedUaHintsHardwareAndProtocolReleaseTogether() {
        for(cohort in PersonaCohort.entries)for(seed in -128..128) {
            val p=PublicPersonaFactory.create(cohort,seed)
            assertTrue(PublicPersonaFactory.problems(p).isEmpty())
            val h=p.headers(true).toMap()
            assertEquals(p.userAgent,h["user-agent"])
            assertTrue(p.userAgent.contains("Chrome/${p.browserVersion.substringBefore('.')}.0.0.0"))
            assertEquals(if(cohort.mobile)"?1"else "?0",h["sec-ch-ua-mobile"])
            assertEquals("\"${cohort.platform}\"",h["sec-ch-ua-platform"])
            assertTrue(p.brands.filter {it.name!="Not_A Brand"}.all {it.version==cohort.major.toString()})
        }
        val mobile=PublicPersonaFactory.create(PersonaCohort.ANDROID_131,0)
        assertTrue(mobile.userAgent.contains("Android 10; K"));assertEquals("14.0.0",mobile.device.platformVersion)
        assertEquals("Adreno (TM) 740",mobile.device.graphicsRenderer)
        assertEquals("\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",mobile.headers(true).toMap()["sec-ch-ua"])
    }
    @Test fun observedExternalGeneratorContradictionsAreRejectedIndependently() {
        val p=PublicPersonaFactory.create(PersonaCohort.MACOS_142,0)
        assertTrue("release" in PublicPersonaFactory.problems(p.copy(browserVersion="105.0.0.0")))
        assertTrue("brands" in PublicPersonaFactory.problems(p.copy(brands=p.brands.map {if(it.name=="Google Chrome")it.copy(name="Brave")else it})))
        assertTrue("device contract" in PublicPersonaFactory.problems(p.copy(device=p.device.copy(graphicsRenderer="Adreno (TM) 740"))))
        assertTrue("appVersion" in PublicPersonaFactory.problems(p.copy(appVersion="unrelated")))
        assertTrue("languages" in PublicPersonaFactory.problems(p.copy(languages=listOf("en-US\r\nCookie: tracking=yes"))))
    }
    @Test fun mobileAndDesktopContractsPreserveObservedProtocolDifferences() {
        val mobile=PublicPersonaFactory.create(PersonaCohort.ANDROID_131,0).wire
        val desktop=PublicPersonaFactory.create(PersonaCohort.MACOS_142,0).wire
        assertEquals(listOf(29,23,24),mobile.groups);assertEquals(17513,mobile.alpsExtension)
        assertEquals(listOf(4588,29,23,24),desktop.groups);assertEquals(17613,desktop.alpsExtension)
        assertEquals(listOf(1 to 65536,2 to 0,4 to 6291456,6 to 262144),desktop.h2Settings)
        assertEquals(15663105,desktop.connectionWindowIncrement)
        assertEquals(listOf(":method",":authority",":scheme",":path"),desktop.pseudoHeaderOrder)
        assertEquals(listOf("h2","http/1.1"),desktop.alpn)
        assertEquals(setOf("gzip","deflate","br","zstd"),desktop.decoders)
    }
    @Test fun navigationAndSameOriginApiReadsHaveDifferentFetchSemantics() {
        val p=PublicPersonaFactory.create(PersonaCohort.MACOS_142,0)
        val nav=p.headers(true).toMap();val api=p.headers(false).toMap()
        assertEquals("navigate",nav["sec-fetch-mode"]);assertEquals("none",nav["sec-fetch-site"]);assertEquals("?1",nav["sec-fetch-user"])
        assertEquals("cors",api["sec-fetch-mode"]);assertEquals("same-origin",api["sec-fetch-site"]);assertEquals("empty",api["sec-fetch-dest"])
        assertFalse(api.containsKey("sec-fetch-user"));assertFalse(api.containsKey("upgrade-insecure-requests"))
        assertEquals(nav["user-agent"],api["user-agent"])
    }
    @Test fun stateStaysWithinExactSourceTargetOriginAndIssuingSession() {
        val f=Fixture();val lease=f.bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        f.bank.receive(lease,source,target,url,listOf("guest=one; Secure; Path=/"))
        assertEquals("guest=one",f.bank.cookies(lease,source,target,url))
        val issuing=f.session
        for(session in listOf(NetworkSession(NetworkPolicy()),NetworkSession(NetworkPolicy(tor=true)))) {
            f.session=session
            assertTrue(runCatching {f.bank.cookies(lease,source,target,url)}.isFailure)
        }
        f.session=issuing
        assertTrue(runCatching {f.bank.cookies(lease,source.copy(role=SourceRole.ALTERNATE),target,url)}.isFailure)
        assertTrue(runCatching {f.bank.cookies(lease,source,target.copy(kind=TargetKind.POST),url)}.isFailure)
        assertTrue(runCatching {f.bank.cookies(lease,source,target,"https://sub.x.com/NASA")}.isFailure)
        assertTrue(runCatching {f.bank.cookies(PublicPersonaBank.Lease(lease.id,lease.persona),source,target,url)}.isFailure)
        assertFalse(lease.toString().contains(lease.id));assertFalse(lease.toString().contains("guest"))
    }
    @Test fun absoluteExpiryClearingAndClockRollbackCannotReviveIdentityState() {
        val f=Fixture();val first=f.bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        f.bank.receive(first,source,target,url,listOf("guest=one; Path=/"))
        f.now+=500;assertSame(first,f.bank.acquire(source,target,url,PersonaCohort.MACOS_142))
        f.now+=500;assertTrue(runCatching {f.bank.cookies(first,source,target,url)}.isFailure)
        val next=f.bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        assertNotEquals(first.id,next.id);assertNull(f.bank.cookies(next,source,target,url))
        f.now--;assertTrue(runCatching {f.bank.cookies(next,source,target,url)}.isFailure)
        val fresh=f.bank.acquire(source,target,url,PersonaCohort.MACOS_142);f.bank.clear()
        assertTrue(runCatching {f.bank.cookies(fresh,source,target,url)}.isFailure)
    }
    @Test fun cookieAdmissionHonorsHostPathDeletionPrefixesAndBounds() {
        val f=Fixture();val lease=f.bank.acquire(source,target,url,PersonaCohort.ANDROID_131)
        f.bank.receive(lease,source,target,url,listOf("root=one; Path=/; Secure","specific=two; Path=/NASA; Secure","other=never; Domain=other.invalid","__Host-bad=x; Domain=x.com; Path=/; Secure","__Secure-bad=x; Path=/","injection=x\r\nAnother: bad","oversize="+"x".repeat(4096)))
        assertEquals("specific=two; root=one",f.bank.cookies(lease,source,target,url))
        assertEquals("root=one",f.bank.cookies(lease,source,target,"https://x.com/nasa"))
        f.bank.receive(lease,source,target,url,listOf("root=gone; Path=/; Max-Age=0"))
        assertEquals("specific=two",f.bank.cookies(lease,source,target,url))
        f.bank.receive(lease,source,target,url,(0..100).map {"cookie$it=value; Path=/"})
        assertEquals(8,f.bank.cookies(lease,source,target,url)!!.split("; ").size)
    }
    @Test fun sessionCapacityEvictsWithoutAffectingAnotherActiveScope() {
        val f=Fixture(2);val first=f.bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        val other=target.copy(identity="ESA",canonicalUrl="https://x.com/ESA")
        val second=f.bank.acquire(source,other,other.canonicalUrl,PersonaCohort.MACOS_142)
        f.bank.receive(second,source,other,other.canonicalUrl,listOf("guest=ESA; Path=/"))
        val third=target.copy(identity="NOAA",canonicalUrl="https://x.com/NOAA")
        f.bank.acquire(source,third,third.canonicalUrl,PersonaCohort.MACOS_142)
        assertEquals(2,f.bank.retainedSessions());assertTrue(runCatching {f.bank.cookies(first,source,target,url)}.isFailure)
        assertEquals("guest=ESA",f.bank.cookies(second,source,other,other.canonicalUrl))
    }
    @Test fun invalidDestinationsAndUnavailableTorCannotAllocateAPersona() {
        val f=Fixture()
        for(address in listOf("http://x.com/NASA","https://user:secret@x.com/NASA","https://x.com/NASA#part","https://other.invalid/NASA")) {
            assertTrue(runCatching {f.bank.acquire(source,target,address,PersonaCohort.MACOS_142)}.isFailure)
        }
        f.session=NetworkSession(NetworkPolicy(tor=true))
        assertTrue(runCatching {f.bank.acquire(source,target,url,PersonaCohort.MACOS_142)}.isFailure)
        assertEquals(0,f.bank.retainedSessions())
    }
    @Test fun owningGatewayModeRoundTripRequiresFreshIdentityWithoutCallerSessionArguments() {
        val cleaner=UrlCleaner(com.google.gson.JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val gateway=NetworkGateway(cleaner,object:RequestFilter {override fun check(url:String,sourceUrl:String,type:String)=FilterDecision(false)})
        val bank=PublicPersonaBank(sessionFor=gateway::sessionFor)
        val first=bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        bank.receive(first,source,target,url,listOf("guest=first; Secure; Path=/"))
        gateway.configure(NetworkPolicy(tor=true))
        assertTrue(runCatching {bank.cookies(first,source,target,url)}.isFailure)
        assertTrue(runCatching {bank.acquire(source,target,url,PersonaCohort.MACOS_142)}.isFailure)
        gateway.configure(NetworkPolicy())
        assertTrue(runCatching {bank.cookies(first,source,target,url)}.isFailure)
        val next=bank.acquire(source,target,url,PersonaCohort.MACOS_142)
        assertNotEquals(first.id,next.id);assertNull(bank.cookies(next,source,target,url))
    }
}
