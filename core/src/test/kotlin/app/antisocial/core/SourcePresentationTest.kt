package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class SourcePresentationTest {
    private val source=Source(Platform.X,"nitter","https://frontend.example")
    private val provenance=Provenance(source,1,"https://frontend.example/author/status/123")
    private val post=Post("123",Platform.X,"https://x.com/author/status/123","Author","Public text",provenance=provenance)

    @Test fun retrievalRoutesNeverReplaceTheOriginalSocialPlatform() {
        for(platform in Platform.entries.filterNot {it in ExpandedRoutes.federated||it==Platform.RSS}) {
            val value=post.copy(platform=platform,canonicalUrl=platform.origin+"/post/123",provenance=provenance.copy(source=source.copy(platform=platform)))
            assertEquals(platform.label,SourcePresentation.label(value))
        }
    }

    @Test fun freshCachedAndLocallySavedPostsKeepTheSamePlatformAttribution() {
        for(value in listOf(post,post.copy(provenance=provenance.copy(cached=true)),post.copy(provenance=null),post.copy(provenance=provenance.copy(source=source.copy(role=SourceRole.ORIGINAL))))) {
            assertEquals("X",SourcePresentation.label(value))
        }
    }

    @Test fun archiveRecoveryDoesNotBecomeTheSocialDestination() {
        val archived=post.copy(recoveredArchive=ArchiveRecovery("Internet Archive",1,"https://web.archive.org/web/20260101000000/"+post.canonicalUrl))
        assertEquals("X",SourcePresentation.label(archived))
        assertEquals("Internet Archive",archived.recoveredArchive?.provider)
        assertEquals(provenance,archived.provenance)
    }

    @Test fun federatedPostsIdentifyTheirCanonicalServerInsteadOfTheFrontend() {
        for(platform in ExpandedRoutes.federated) {
            val value=post.copy(platform=platform,canonicalUrl="https://Social.Example:8443/post/123",provenance=provenance.copy(source=source.copy(platform=platform)))
            assertEquals("${platform.label} · social.example:8443",SourcePresentation.label(value))
            assertEquals("https://frontend.example",value.provenance?.source?.root)
        }
    }

    @Test fun ordinaryServerPortsAreNotVisualClutter() {
        for(url in listOf("https://social.example:443/@author/123","http://social.example:80/@author/123","https://social.example/@author/123")) {
            assertEquals("Mastodon · social.example",SourcePresentation.label(post.copy(platform=Platform.MASTODON,canonicalUrl=url)))
        }
    }

    @Test fun malformedStoredFederationUrlsFallBackToTheirPlatformNotTheirRoute() {
        for(url in listOf("broken URL","mailto:author@social.example","https://name:password@social.example/post/123")) {
            assertEquals("Mastodon",SourcePresentation.label(post.copy(platform=Platform.MASTODON,canonicalUrl=url)))
        }
    }
}
