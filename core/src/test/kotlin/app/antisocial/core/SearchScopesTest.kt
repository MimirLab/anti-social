package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SearchScopesTest {
    @Test fun explicitScopeKeepsLeadingMentionsAndHashtagsAsSearchTerms() {
        val scope=SearchScope(SearchScopeKind.COMMUNITY,"android")
        for(query in listOf("#privacy","@person")) {
            val target=routes.openInput(Platform.REDDIT,query,true,scope)
            assertEquals(TargetKind.SEARCH,target.kind)
            assertEquals(query,target.identity)
            assertEquals(scope,SearchScopes.from(target))
        }
        assertEquals(TargetKind.PROFILE,routes.openInput(Platform.REDDIT,"@person",false,scope).kind)
        assertEquals(TargetKind.POST,routes.openInput(Platform.REDDIT,"https://www.reddit.com/r/android/comments/abc/title",true,scope).kind)
    }
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private fun search(platform: Platform,kind: SearchScopeKind,value: String,query: String="space & science")=SearchScopes.apply(routes.search(platform,query),SearchScopes.normalize(platform,kind,value))
    private fun post(platform: Platform,url: String,body: String="Actual text")=Post(url,platform,url,"A display name",body)

    @Test fun everyPlatformOffersAConcretePublicFeedScope() {
        Platform.entries.filterNot {it in setOf(Platform.RSS,Platform.YOUTUBE)}.forEach {platform->
            val option=SearchScopes.options(platform).first()
            val input=when(platform){Platform.BLUESKY->"nasa.gov";Platform.LINKEDIN->"company/nasa";Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY->"space@${platform.id}.example";else->"space"}
            val target=search(platform,option.kind,input)
            val container=SearchScopes.container(routes,target)
            assertEquals(platform,container.platform)
            assertEquals("A container must restore its exact selected scope for $platform",SearchScopes.from(target),SearchScopes.fromContext(container))
            assertFalse(container.canonicalUrl.contains("antisocial-scope"))
        }
    }
    @Test fun unimplementedRssAndYouTubeSearchScopesCannotPretendToRestrictGlobalResults() {
        for(platform in listOf(Platform.RSS,Platform.YOUTUBE)) {
            assertTrue(SearchScopes.options(platform).isEmpty())
            assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(platform,SearchScopeKind.ACCOUNT,"NASA")}
        }
    }
    @Test fun scopeCanonicalIdentitySurvivesBackupAndDoesNotLeakIntoNetworkUrl() {
        val everywhere=routes.search(Platform.REDDIT,"space & science")
        val android=search(Platform.REDDIT,SearchScopeKind.COMMUNITY,"r/Android")
        val linux=search(Platform.REDDIT,SearchScopeKind.COMMUNITY,"linux")
        assertEquals(everywhere.identity,android.identity)
        assertEquals(3,setOf(everywhere.canonicalUrl,android.canonicalUrl,linux.canonicalUrl).size)
        val restored=Gson().fromJson(Gson().toJson(android),Target::class.java)
        assertEquals(SearchScope(SearchScopeKind.COMMUNITY,"android"),SearchScopes.from(restored))
        assertFalse(SearchScopes.networkUrl(android).contains('#'))
        assertEquals(everywhere,SearchScopes.apply(android,null))
    }
    @Test fun malformedLocalScopesFailRatherThanBecomingEverywhere() {
        val base=routes.search(Platform.X,"test")
        listOf("antisocial-scope","antisocial-scope=UNSUPPORTED&value=nasa","antisocial-scope=ACCOUNT","antisocial-scope=ACCOUNT&value=","antisocial-scope=ACCOUNT&value=nasa&extra=ignored","antisocial-scope=ACCOUNT&value=%ZZ","antisocial-scope=ACCOUNT&value=nasa%26from%3Aother").forEach {fragment->
            assertThrows("$fragment must not widen the query",IllegalArgumentException::class.java){SearchScopes.from(base.copy(canonicalUrl=base.canonicalUrl+"#"+fragment))}
        }
        assertNull(SearchScopes.from(base.copy(canonicalUrl=base.canonicalUrl+"#ordinary-site-anchor")))
        assertThrows(IllegalArgumentException::class.java){SearchScopes.from(base.copy(kind=TargetKind.PROFILE,canonicalUrl=base.canonicalUrl+"#antisocial-scope=ACCOUNT&value=nasa"))}
    }
    @Test fun emptyUnknownOrInjectedInputsAreNotValidScopes() {
        listOf(""," ","nasa other","nasa&from:other","https://x.com/nasa","../nasa","na\nsa").forEach {value->
            assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(Platform.X,SearchScopeKind.ACCOUNT,value)}
        }
        assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(Platform.FACEBOOK,SearchScopeKind.COMMUNITY,"group")}
        assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(Platform.MASTODON,SearchScopeKind.TAG,"tag")}
    }
    @Test fun nativeSubredditSearchKeepsTheCleanQueryAndRestrictsTheCommunity() {
        val target=search(Platform.REDDIT,SearchScopeKind.COMMUNITY,"r/Android")
        val url=URI(SearchScopes.networkUrl(target))
        assertTrue(SearchScopes.nativeSearch(target))
        assertEquals("/r/android/search",url.path)
        assertEquals("on",DarwinRoutes.queryValue(url,"restrict_sr"))
        assertEquals("new",DarwinRoutes.queryValue(url,"sort"))
        assertEquals("space & science",DarwinRoutes.queryValue(url,"q"))
        assertEquals("site:reddit.com/r/android/comments/ space & science",SearchScopes.publicIndexQuery(target))
    }
    @Test fun nativeXSearchScopesAreOperatorsWithoutChangingDisplayedQuery() {
        val account=search(Platform.X,SearchScopeKind.ACCOUNT,"@NASA")
        val tag=search(Platform.X,SearchScopeKind.TAG,"#Space")
        assertEquals("space & science",account.identity)
        assertEquals("from:nasa (space & science)",DarwinRoutes.queryValue(URI(SearchScopes.networkUrl(account)),"q"))
        assertEquals("#space (space & science)",DarwinRoutes.queryValue(URI(SearchScopes.networkUrl(tag)),"q"))
        assertEquals("site:x.com/nasa/status/ space & science",SearchScopes.publicIndexQuery(account))
        assertEquals("site:x.com \"#space\" space & science",SearchScopes.publicIndexQuery(tag))
    }
    @Test fun nativeBlueskyDescriptorsKeepAuthorAndTagSeparateFromRawQuery() {
        val account=search(Platform.BLUESKY,SearchScopeKind.ACCOUNT,"nasa.gov")
        val tag=search(Platform.BLUESKY,SearchScopeKind.TAG,"#space")
        assertTrue(SearchScopes.nativeSearch(account));assertTrue(SearchScopes.nativeSearch(tag))
        assertEquals("space & science",DarwinRoutes.queryValue(URI(SearchScopes.networkUrl(account)),"q"))
        assertEquals("site:bsky.app/profile/nasa.gov/post/ space & science",SearchScopes.publicIndexQuery(account))
    }
    @Test fun nativeMembershipUsesActualPostUrlsRatherThanDisplayNames() {
        val reddit=search(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android")
        assertTrue(SearchScopes.accepts(reddit,post(Platform.REDDIT,"https://www.reddit.com/r/Android/comments/abc/title/")))
        assertFalse(SearchScopes.accepts(reddit,post(Platform.REDDIT,"https://www.reddit.com/r/androiddev/comments/abc/title/")))
        assertFalse(SearchScopes.accepts(reddit,post(Platform.REDDIT,"https://www.reddit.com/comments/abc/title/","r/android")))
        val x=search(Platform.X,SearchScopeKind.ACCOUNT,"nasa")
        assertTrue(SearchScopes.accepts(x,post(Platform.X,"https://x.com/NASA/status/123")))
        assertFalse(SearchScopes.accepts(x,post(Platform.X,"https://x.com/other/status/123").copy(author="nasa")))
        assertFalse(SearchScopes.accepts(x,post(Platform.X,"https://evil.example/nasa/status/123")))
        assertFalse(SearchScopes.accepts(x,post(Platform.X,"https://x.com:444/nasa/status/123")))
        val bluesky=search(Platform.BLUESKY,SearchScopeKind.ACCOUNT,"nasa.gov")
        assertTrue(SearchScopes.accepts(bluesky,post(Platform.BLUESKY,"https://bsky.app/profile/nasa.gov/post/3abc")))
        assertFalse(SearchScopes.accepts(bluesky,post(Platform.BLUESKY,"https://bsky.app/profile/other.example/post/3abc").copy(author="nasa.gov")))
    }
    @Test fun hashtagsRequireActualExactHydratedText() {
        val target=search(Platform.X,SearchScopeKind.TAG,"#space")
        val source=post(Platform.X,"https://x.com/nasa/status/123")
        assertTrue(SearchScopes.accepts(target,source.copy(body="Explore #Space!")))
        assertFalse(SearchScopes.accepts(target,source.copy(body="#spaceflight")))
        assertFalse(SearchScopes.accepts(target,source.copy(body="The topic is space")))
        assertFalse(SearchScopes.accepts(target,source.copy(body="abc#space")))
        assertFalse(SearchScopes.accepts(target,source.copy(platform=Platform.BLUESKY,body="#space")))
        assertTrue(SearchScopes.resultAccepts(target,Target(Platform.X,TargetKind.POST,"nasa/status/123",source.canonicalUrl)))
    }
    @Test fun opaquePostIdsRequireVerifiedContainerEvidence() {
        val target=search(Platform.PINTEREST,SearchScopeKind.BOARD,"nasa/space")
        val result=post(Platform.PINTEREST,"https://www.pinterest.com/pin/123/").copy(author="nasa",links=listOf("https://www.pinterest.com/nasa/space/"))
        assertFalse(SearchScopes.accepts(target,result))
        val feed=SearchScopes.container(routes,target)
        assertTrue(SearchScopes.accepts(target,result,feed))
        assertFalse(SearchScopes.accepts(target,result,routes.target(Platform.PINTEREST,"nasa/other",TargetKind.COMMUNITY)))
    }
    @Test fun nativeScopedResultsNeverDisplayPeopleOrOutOfScopePosts() {
        val target=search(Platform.X,SearchScopeKind.ACCOUNT,"nasa")
        val nasa=post(Platform.X,"https://x.com/nasa/status/123")
        val other=post(Platform.X,"https://x.com/other/status/124")
        val profile=routes.target(Platform.X,"nasa")
        val page=Page(listOf(nasa,other),accounts=listOf(Profile(profile,"NASA")),collections=listOf(Collection(profile,"NASA"),Collection(routes.recognize(nasa.canonicalUrl)!!,"Real post"),Collection(routes.recognize(other.canonicalUrl)!!,"Other post")))
        val filtered=SearchScopes.scopePage(target,page)
        assertEquals(listOf(nasa),filtered.posts);assertTrue(filtered.accounts.isEmpty());assertEquals(listOf(nasa.canonicalUrl),filtered.collections.map {it.target.canonicalUrl})
    }
    @Test fun federatedAccountAndCommunityScopesRetainTheirServer() {
        val a=search(Platform.LEMMY,SearchScopeKind.COMMUNITY,"!technology@one.example")
        val b=search(Platform.LEMMY,SearchScopeKind.COMMUNITY,"technology@two.example")
        assertNotEquals(a.canonicalUrl,b.canonicalUrl)
        assertEquals("https://one.example/c/technology",SearchScopes.container(routes,a).canonicalUrl)
        assertEquals("https://two.example/c/technology",SearchScopes.container(routes,b).canonicalUrl)
        listOf("space@127.0.0.1","space@server.local","space@server.example:0","space@server.example:65536","space@server.example/path").forEach {input->
            assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(Platform.MASTODON,SearchScopeKind.ACCOUNT,input)}
        }
    }
    @Test fun currentContextMakesRelevantScopesAvailableWithoutGuessingFromAPost() {
        assertEquals(SearchScope(SearchScopeKind.COMMUNITY,"android"),SearchScopes.fromContext(routes.target(Platform.REDDIT,"r/Android")))
        assertEquals(SearchScope(SearchScopeKind.CHANNEL,"telegram"),SearchScopes.fromContext(routes.target(Platform.TELEGRAM,"telegram")))
        assertEquals(SearchScope(SearchScopeKind.TAG,"space"),SearchScopes.fromContext(routes.target(Platform.BLUESKY,"#space")))
        assertNull(SearchScopes.fromContext(routes.recognize("https://x.com/nasa/status/123")!!))
        assertNull(SearchScopes.fromContext(Target(Platform.X,TargetKind.PROFILE,"nasa","https://evil.example/nasa")))
    }
    @Test fun alternatePublicContextFormsRetainOneCorrectScope() {
        assertEquals(SearchScope(SearchScopeKind.ACCOUNT,"nasa"),SearchScopes.fromContext(routes.target(Platform.REDDIT,"u/nasa")))
        assertNull(SearchScopes.fromContext(Target(Platform.LEMMY,TargetKind.COMMUNITY,"c/android@lemmy.ml@lemmy.world","https://lemmy.world/c/android@lemmy.ml")))
        assertEquals(SearchScope(SearchScopeKind.COMMUNITY,"android@lemmy.world"),SearchScopes.fromContext(Target(Platform.LEMMY,TargetKind.COMMUNITY,"c/android@lemmy.world","https://lemmy.world/c/android")))
        assertEquals(SearchScope(SearchScopeKind.ACCOUNT,"staff"),SearchScopes.fromContext(Target(Platform.TUMBLR,TargetKind.PROFILE,"staff","https://staff.tumblr.com/")))
        assertEquals(SearchScope(SearchScopeKind.BOARD,"nasa/space"),SearchScopes.fromContext(routes.target(Platform.PINTEREST,"nasa/space",TargetKind.COMMUNITY)))
        assertEquals(SearchScope(SearchScopeKind.CHANNEL,"telegram"),SearchScopes.fromContext(Target(Platform.TELEGRAM,TargetKind.PROFILE,"s/telegram","https://t.me/s/telegram")))
        assertNull(SearchScopes.fromContext(Target(Platform.LEMMY,TargetKind.COMMUNITY,"broken","https://lemmy.world/c/android@lemmy.ml@lemmy.world")))
    }
    @Test fun savedScopedSearchLinksRecognizeTheSameScopeAndCleanDisplayedQuery() {
        listOf(Triple(Platform.X,SearchScopeKind.ACCOUNT,"nasa"),Triple(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android"),Triple(Platform.BLUESKY,SearchScopeKind.TAG,"space"),Triple(Platform.LEMMY,SearchScopeKind.COMMUNITY,"technology@lemmy.world")).forEach {(platform,kind,value)->
            val scope=SearchScopes.normalize(platform,kind,value)
            val target=routes.search(platform,"space & science",scope)
            val restored=routes.recognize(target.canonicalUrl,platform)!!
            assertEquals(target,restored)
            assertEquals(scope,SearchScopes.from(restored))
            assertEquals("space & science",restored.identity)
        }
    }
    @Test fun nativeMembershipWorksWithRecognizedOriginalHostAliases() {
        val x=search(Platform.X,SearchScopeKind.ACCOUNT,"nasa")
        listOf("https://twitter.com/NASA/status/123","https://mobile.twitter.com/NASA/status/123","https://x.com/NASA/status/123").forEach {url->
            assertTrue(url,SearchScopes.accepts(x,routes.recognize(url)!!))
        }
        val reddit=search(Platform.REDDIT,SearchScopeKind.COMMUNITY,"android")
        listOf("https://old.reddit.com/r/Android/comments/abc/title/","https://reddit.com/r/Android/comments/abc/title/").forEach {url->
            assertTrue(url,SearchScopes.accepts(reddit,routes.recognize(url)!!))
        }
    }
    @Test fun opaqueAccountContainersUseTheActualSupportedProfilePath() {
        val funny=search(Platform.IFUNNY,SearchScopeKind.ACCOUNT,"user/space")
        assertEquals("https://ifunny.co/user/space",SearchScopes.container(routes,funny).canonicalUrl)
        val company=search(Platform.LINKEDIN,SearchScopeKind.ACCOUNT,"company/nasa")
        assertEquals("https://www.linkedin.com/company/nasa",SearchScopes.container(routes,company).canonicalUrl)
        assertThrows(IllegalArgumentException::class.java){SearchScopes.normalize(Platform.LINKEDIN,SearchScopeKind.ACCOUNT,"in/someone")}
    }
    @Test fun unsupportedFullTextScopesCannotFallBackToAPlatformWideQuery() {
        val board=search(Platform.PINTEREST,SearchScopeKind.BOARD,"nasa/space")
        assertFalse(SearchScopes.nativeSearch(board))
        assertThrows(IllegalArgumentException::class.java){SearchScopes.networkUrl(board)}
        assertThrows(IllegalArgumentException::class.java){SearchScopes.publicIndexQuery(board)}
        assertEquals("https://www.pinterest.com/nasa/space",SearchScopes.container(routes,board).canonicalUrl)
    }
}
