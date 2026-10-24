/* anti social, GPL-3.0-or-later. Exercise actual core bytecode on stock Android.
 * Java accesses the existing JVM signatures; no production testing hook or
 * Kotlin visibility suppression is added. These tests make no network reads. */
package app.antisocial.core;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Pair;
import kotlin.Unit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class PublicPersonaAndroidTest {
    private void isolated() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")));
    }
    private void record(String name, JSONObject result) throws Exception {
        File root=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("validation"),"persona-model");
        assertTrue(root.isDirectory() || root.mkdirs());
        result.put("scope","Android core construction/state only; no browser realm, native protocol dispatch, public preview or live Tor qualification.");
        Files.write(new File(root,name+".json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void wholePersonasRetainTheirDistinctMobileAndDesktopContracts() throws Exception {
        isolated();int checked=0;
        for(PersonaCohort cohort:PersonaCohort.values())for(int seed=-16;seed<16;seed++) {
            PublicPersona p=PublicPersonaFactory.INSTANCE.create(cohort,seed);
            Map<String,String> headers=new LinkedHashMap<>();
            for(Pair<String,String> pair:p.headers(true))headers.put(pair.getFirst(),pair.getSecond());
            assertEquals(p.getUserAgent(),headers.get("user-agent"));
            assertEquals(p.getUserAgent().substring("Mozilla/".length()),p.getAppVersion());
            assertTrue(PublicPersonaFactory.INSTANCE.problems(p).isEmpty());
            if(cohort==PersonaCohort.ANDROID_131) {
                assertEquals("?1",headers.get("sec-ch-ua-mobile"));
                assertTrue(p.getUserAgent().contains("Android 10; K"));
                assertEquals(17513,p.getWire().getAlpsExtension());
                assertFalse(p.getWire().getGroups().contains(4588));
            }else {
                assertEquals("?0",headers.get("sec-ch-ua-mobile"));
                assertTrue(p.getUserAgent().contains("Mac OS X 10_15_7"));
                assertEquals(17613,p.getWire().getAlpsExtension());
                assertTrue(p.getWire().getGroups().contains(4588));
            }
            checked++;
        }
        record("construction",new JSONObject().put("profilesChecked",checked).put("consistent",true));
    }
    @Test public void sessionOwnerExpiryAndClearDiscardGuestState() throws Exception {
        isolated();AtomicLong now=new AtomicLong(System.currentTimeMillis());
        AtomicReference<NetworkSession> owner=new AtomicReference<>(new NetworkSession(new NetworkPolicy(false,0,false)));
        SecureRandom random=new SecureRandom();
        PublicPersonaBank bank=new PublicPersonaBank(url->owner.get(),now::get,bytes->{random.nextBytes(bytes);return Unit.INSTANCE;},4,1000L);
        Source source=new Source(Platform.X,"original","https://x.com",false,SourceRole.ORIGINAL);
        Target target=new Target(Platform.X,TargetKind.PROFILE,"NASA","https://x.com/NASA");String url=target.getCanonicalUrl();
        PublicPersonaBank.Lease first=bank.acquire(source,target,url,PersonaCohort.MACOS_142);
        bank.receive(first,source,target,url,Collections.singletonList("guest=fixture; Secure; Path=/"));
        assertEquals("guest=fixture",bank.cookies(first,source,target,url));
        owner.set(new NetworkSession(new NetworkPolicy(false,0,false)));
        try {bank.cookies(first,source,target,url);fail("Old owner session retained its identity");}catch(IllegalStateException expected) { }
        PublicPersonaBank.Lease next=bank.acquire(source,target,url,PersonaCohort.MACOS_142);
        assertNull(bank.cookies(next,source,target,url));
        now.addAndGet(1000L);
        try {bank.cookies(next,source,target,url);fail("Expired identity survived");}catch(IllegalStateException expected) { }
        bank.acquire(source,target,url,PersonaCohort.MACOS_142);bank.clear();assertEquals(0,bank.retainedSessions());
        record("lifetime",new JSONObject().put("sessionChangeDiscardedState",true).put("absoluteExpiry",true).put("clear",true));
    }
}
