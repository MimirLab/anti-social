package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Anonymous, read-only Facebook protocol adapter. No browser, cookie jar or account token.
 * Protocol research: Phice (Willie169/phice, AGPL-3.0), api.py and parsers.py;
 * https://github.com/Willie169/phice
 * RSSHub (DIYgod and contributors, AGPL-3.0), lib/routes/facebook/{page,utils}.ts;
 * https://github.com/DIYgod/RSSHub/tree/master/lib/routes/facebook
 * This independently written reader retains query identifiers as fallible protocol data.
 * A public profile, public posts and comments are separate source-dependent capabilities.
 */
class FacebookAdapter(override val source: Source, private val gateway: NetworkGateway, private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.COMMENTS,Capability.MEDIA,Capability.SEARCH)
    private val original get()=source.role==SourceRole.ORIGINAL
    override fun sentinel(capability: Capability)=if(capability==Capability.SEARCH)routes.search(Platform.FACEBOOK,"organic food")else routes.target(Platform.FACEBOOK,"NASA")
    override suspend fun feed(target: Target)=fetch(target)
    override suspend fun profile(target: Target)=fetch(target)

    /** Genuine first-window directory results, separate from post pagination.
     * A post's author is not evidence that the account matched this query. */
    override suspend fun directory(target: Target,cursor: String?): Page=coroutineScope {
        require(cursor.isNullOrBlank()) {"Refresh this Facebook account search for a new first window"}
        FacebookProtocol.searchText(target)
        val tor=gateway.currentPolicy().tor
        val deadline=if(original) {if(tor)25_000L else 12_000L}else {if(tor)12_000L else 6_000L}
        val results=listOf("pages","people").map {category->async {
            try {
                Result.success(withTimeout(deadline) {if(original) {
                    val tab=if(category=="pages")"PAGES_TAB"else "PEOPLE_TAB"
                    FacebookProtocol.directory(searchQuery(target,null,tab),target,source,routes,tab)
                }else {
                    val url=source.root.trimEnd('/')+"/search?t=$category&q="+DarwinRoutes.encode(FacebookProtocol.searchText(target))
                    FacebookProtocol.alternateSearchUrl(url,target,source,category)
                    val payload=gateway.text(url,target.canonicalUrl)
                    FacebookProtocol.alternateSearchUrl(payload.finalUrl,target,source,category)
                    FacebookProtocol.phiceDirectory(payload,target,source,routes)
                }})
            }catch(e: TimeoutCancellationException){
                // Only our category deadline is recoverable. Parent Stop or
                // source-budget cancellation must still cancel both requests.
                currentCoroutineContext().ensureActive()
                Result.failure<Page>(SourceFailure(HealthStatus.UNAVAILABLE,"This Facebook account search category timed out"))
            }catch(e: CancellationException){throw e}catch(e: Exception){Result.failure<Page>(e)}
        }}.map {it.await()}
        val pages=results.mapNotNull {it.getOrNull()}
        if(pages.isEmpty())throw (results.mapNotNull {it.exceptionOrNull()}.firstOrNull {(it as? SourceFailure)?.status in setOf(HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED,HealthStatus.RATE_LIMITED)} ?: results.first().exceptionOrNull()!!)
        Page(emptyList(),accounts=pages.flatMap {it.accounts}.distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}.take(48),provenance=pages.first().provenance,
            notice=if(results.any {it.isFailure})"Some matching Facebook accounts were unavailable from this route."else pages.firstNotNullOfOrNull {it.notice})
    }

    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(target.platform==Platform.FACEBOOK)
        if(target.kind !in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD,TargetKind.MEDIA,TargetKind.SEARCH))throw SourceFailure(HealthStatus.UNSUPPORTED,"This Facebook reader supports public profiles, posts, comments and search")
        if(target.kind==TargetKind.SEARCH)FacebookProtocol.searchText(target)
        if(!original)return alternate(target,cursor)
        if(target.kind==TargetKind.MEDIA) {
            val type=MediaFormats.type(gateway.sample(target.canonicalUrl,target.canonicalUrl)) ?: throw SourceFailure(HealthStatus.EMPTY,"This Facebook link did not return recognizable media")
            val provenance=Provenance(source,System.currentTimeMillis(),target.canonicalUrl)
            return Page(listOf(Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,"","",media=listOf(Media(target.canonicalUrl,type)),provenance=provenance)),provenance=provenance)
        }
        val saved=cursor?.let {FacebookProtocol.readCursor(it,source,target)}
        if(target.kind==TargetKind.SEARCH) {
            require(saved==null||saved.entity=="search:posts"){"Facebook continuation changed its search operation"}
            val payload=searchQuery(target,saved?.token)
            val page=FacebookProtocol.search(payload,target,source)
            return page.copy(next=page.next?.let {FacebookProtocol.cursor(source,target,"search:posts",it)})
        }
        if(target.kind==TargetKind.PROFILE) {
            val owner=saved?.entity ?: owner(target)
            val payload=query("ProfileCometTimelineFeedRefetchQuery",FacebookProtocol.timelineVariables(owner,saved?.token),target)
            val page=FacebookProtocol.timeline(payload,target,source,owner)
            return page.copy(next=page.next?.let {FacebookProtocol.cursor(source,target,owner,it)})
        }
        if(saved!=null) {
            val payload=query("CommentsListComponentsPaginationQuery",FacebookProtocol.commentVariables(saved.entity,saved.token,FacebookProtocol.ranked(target)),target)
            val page=FacebookProtocol.comments(payload,target,source,saved.entity)
            return page.copy(next=page.next?.let {FacebookProtocol.cursor(source,target,saved.entity,it)})
        }
        val storyId=FacebookProtocol.numericStoryId(target) ?: run {
            val payload=gateway.text(target.canonicalUrl,target.canonicalUrl,headers=mapOf("Accept-Language" to "en-US"))
            FacebookProtocol.storyId(payload,target) ?: navigationStory(target)
        }
        val payload=query("CometSinglePostDialogContentQuery",FacebookProtocol.storyVariables(storyId),target)
        val page=FacebookProtocol.post(payload,target,source)
        val feedback=FacebookProtocol.feedbackId(payload,storyId)
        if(feedback==null)return page.copy(notice="Public post retrieved; this source did not expose its comment identifier.")
        return try {
            val comments=query("CommentListComponentsRootQuery",FacebookProtocol.commentVariables(feedback,null,FacebookProtocol.ranked(target)),target)
            val discussion=FacebookProtocol.comments(comments,target,source,feedback,allowEmpty=true)
            page.copy(posts=page.posts+discussion.posts,next=discussion.next?.let {FacebookProtocol.cursor(source,target,feedback,it)},notice=page.notice ?: discussion.notice)
        }catch(e: CancellationException){throw e}catch(e: Exception){page.copy(notice="Public post retrieved; comments are unavailable from this route.")}
    }

    private suspend fun owner(target: Target): String {
        FacebookProtocol.numericProfileId(target)?.let {return it}
        val key=source.key+"|"+target.canonicalUrl
        FacebookProtocol.cachedOwner(key)?.let {return it}
        val payload=gateway.text(target.canonicalUrl,target.canonicalUrl,headers=mapOf("Accept-Language" to "en-US"))
        val id=FacebookProtocol.profileId(payload,target)
        FacebookProtocol.rememberOwner(key,id)
        return id
    }
    private suspend fun navigationStory(target: Target): String {
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Invalid Facebook post link")
        val payload=gateway.form("${source.root.trimEnd('/')}/ajax/navigation/",mapOf("route_url" to (url.encodedPath+(url.encodedQuery?.let {"?$it"}.orEmpty())),"routing_namespace" to "fb_comet","__a" to "1","__user" to "0","lsd" to "_"),target.canonicalUrl)
        return FacebookProtocol.storyId(payload,target) ?: throw SourceFailure(HealthStatus.EMPTY,"This route did not expose the selected public post")
    }
    private suspend fun query(name: String,variables: JsonObject,target: Target): Payload {
        val nonce=UUID.randomUUID().toString().replace("-","")
        val fields=mapOf("lsd" to nonce,"jazoest" to "2${nonce.sumOf {it.code}}","fb_api_req_friendly_name" to name,"variables" to variables.toString(),"doc_id" to FacebookProtocol.documentId(name))
        return gateway.form("${source.root.trimEnd('/')}/api/graphql/",fields,target.canonicalUrl,headers=mapOf("X-FB-LSD" to nonce,"Accept-Language" to "en-US"))
    }
    private suspend fun searchQuery(target: Target,cursor: String?,tab: String="POSTS_TAB"): Payload {
        // A read-only public operation. No login token, client cookie or browser
        // state is copied from the reference frontend.
        val operation="SearchCometResultsPaginatedResultsQuery"
        val fields=mapOf("lsd" to "_","__user" to "0","__a" to "1","fb_api_caller_class" to "RelayModern",
            "fb_api_req_friendly_name" to operation,"server_timestamps" to "true","doc_id" to FacebookProtocol.documentId(operation),
            "variables" to (if(tab=="POSTS_TAB")FacebookProtocol.searchVariables(FacebookProtocol.searchText(target),cursor)else FacebookProtocol.directoryVariables(FacebookProtocol.searchText(target),tab)).toString())
        return gateway.form("${source.root.trimEnd('/')}/api/graphql/",fields,target.canonicalUrl,
            headers=mapOf("X-FB-LSD" to "_","X-FB-Friendly-Name" to operation,"Accept-Language" to "en-US"))
    }
    private suspend fun alternate(target: Target,cursor: String?): Page {
        val uri=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid Facebook target")
        val raw=if(target.kind==TargetKind.SEARCH)source.root.trimEnd('/')+"/search?t=posts&q="+DarwinRoutes.encode(FacebookProtocol.searchText(target))else source.root.trimEnd('/')+uri.encodedPath+(uri.encodedQuery?.let {"?$it"}.orEmpty())
        val url=cursor?.let {value->FacebookProtocol.readCursor(value,source,target).also {require(it.entity=="alternate"){"Facebook continuation changed its source operation"}}.token} ?: raw
        require(DarwinRoutes.matches(URI(url),source.root)){"Pagination left the selected Facebook source"}
        if(target.kind==TargetKind.SEARCH)FacebookProtocol.alternateSearchUrl(url,target,source)
        val payload=gateway.text(url,target.canonicalUrl)
        // Do not silently turn a private alternate into a direct Facebook request.
        if(!DarwinRoutes.matches(URI(payload.finalUrl),source.root))throw SourceFailure(HealthStatus.UNAVAILABLE,"The alternate redirected outside its selected source")
        if(target.kind==TargetKind.SEARCH)FacebookProtocol.alternateSearchUrl(payload.finalUrl,target,source)
        return FacebookProtocol.phice(payload,target,source).let {page->page.copy(next=page.next?.let {FacebookProtocol.cursor(source,target,"alternate",it)})}
    }
}

/** Bounded extraction of protocol fields, deliberately independent of a web document's scripts. */
internal object FacebookProtocol {
    private val gson=Gson()
    internal data class Cursor(val source: String,val target: String,val entity: String,val token: String,val ranked: Boolean)
    private data class Owner(val id: String,val at: Long)
    private val owners=object: LinkedHashMap<String,Owner>(32,.75f,true){override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Owner>?)=size>32}
    @Synchronized fun cachedOwner(key: String): String?=owners[key]?.takeIf {System.currentTimeMillis()-it.at<20*60_000}?.id
    @Synchronized fun rememberOwner(key: String,id: String){owners[key]=Owner(id,System.currentTimeMillis())}
    fun ranked(target: Target)=target.canonicalUrl.toHttpUrlOrNull()?.queryParameter("sort") in setOf("top","popular","filtered")
    fun cursor(source: Source,target: Target,entity: String,token: String): String {
        require(token.length<=8*1024)
        return "facebook:"+gson.toJson(Cursor(source.key,target.canonicalUrl,entity,token,ranked(target)))
    }
    fun readCursor(value: String,source: Source,target: Target): Cursor {
        require(value.startsWith("facebook:")&&value.length<=16*1024){"Invalid Facebook continuation; refresh this page"}
        val saved=runCatching {gson.fromJson(value.removePrefix("facebook:"),Cursor::class.java)}.getOrNull()
        require(runCatching {saved!=null&&saved.source==source.key&&saved.target==target.canonicalUrl&&saved.ranked==ranked(target)&&saved.entity.isNotBlank()&&saved.entity.length<=512&&saved.token.isNotBlank()&&saved.token.length<=8*1024}.getOrDefault(false)){"Facebook continuation changed the selected source, post or order"}
        return saved!!
    }
    fun documentId(name: String)=when(name) {
        "ProfileCometTimelineFeedRefetchQuery"->"28844200915213260"
        "CometSinglePostDialogContentQuery"->"25507101412240295"
        "CommentListComponentsRootQuery"->"25526756030294601"
        "CommentsListComponentsPaginationQuery"->"25515916584706508"
        "SearchCometResultsPaginatedResultsQuery"->"25707066605590247"
        else->error("Unknown read-only Facebook operation")
    }
    private fun variables()=JsonObject().apply {
        // Public Relay provider field names verified by RSSHub protocol research.
        listOf("GHLShouldChangeAdIdFieldName","GHLShouldChangeSponsoredDataFieldName","CometFeedStory_enable_reactor_facepile","CometFeedStory_enable_social_bubbles","CometFeedStory_enable_post_permalink_white_space_click","CometUFICommentActionLinksRewriteEnabled","CometUFICommentAvatarStickerAnimatedImage","IsWorkUser","TestPilotShouldIncludeDemoAdUseCase","FBReels_deprecate_short_form_video_context_gk","FBReels_enable_view_dubbed_audio_type_gk","CometFeedShareMedia_shouldPrefetchShareImage","CometImmersivePhotoCanUserDisable3DMotion","WorkCometIsEmployeeGKProvider","IsMergQAPolls","CometUFIReactionsEnableShortName","CometUFISingleLineUFI","FBReelsIFUTileContent_reelsIFUPlayOnHover","StoriesShouldEnablePhotosensitiveContentWarning","ShouldEnableBakedInTextStories","StoriesShouldIncludeFbNotes").forEach {addProperty("__relay_internal__pv__${it}relayprovider",false)}
        listOf("CometUFI_dedicated_comment_routable_dialog_gk","FBReelsMediaFooter_comet_enable_reels_ads_gk","CometUFIShareActionMigration","relay_provider_comet_ufi_ssr_seo_defer").forEach {addProperty("__relay_internal__pv__${it}relayprovider",true)}
        addProperty("__relay_internal__pv__CometUFICommentAutoTranslationTyperelayprovider","AUTO_TRANSLATE")
        addProperty("__relay_internal__pv__GroupsCometGYSJFeedItemHeightrelayprovider",150)
        addProperty("scale",1);addProperty("useDefaultActor",false)
    }
    fun searchText(target: Target): String {
        require(target.platform==Platform.FACEBOOK&&target.kind==TargetKind.SEARCH){"Expected a Facebook search"}
        require(SearchScopes.from(target)==null){"Search this page through its selected public feed"}
        val query=target.identity.trim()
        require(query.isNotBlank()&&query.length<=500&&!query.any(Char::isISOControl)){"Enter a shorter Facebook search query"}
        require(DarwinRoutes.queryValue(URI(target.canonicalUrl),"q")==query){"Facebook search query changed its original address"}
        return query
    }
    fun searchVariables(query: String,cursor: String?): JsonObject {
        require(query.isNotBlank()&&query.length<=500&&!query.any(Char::isISOControl))
        require(cursor==null||cursor.length in 1..8*1024)
        return JsonObject().apply {
            // Search has its own public provider defaults. Keep the observed
            // non-personalized operation independent of timeline's flags.
            listOf("ProfilePageImprovementsEnabled","GHLShouldChangeAdIdFieldName","GHLShouldChangeSponsoredDataFieldName","CometUFICommentAvatarStickerAnimatedImage","IsWorkUser","TestPilotShouldIncludeDemoAdUseCase","FBReels_deprecate_short_form_video_context_gk","FeedDeepDiveTopicPillThreadViewEnabled","FBReels_enable_view_dubbed_audio_type_gk","CometImmersivePhotoCanUserDisable3DMotion","WorkCometIsEmployeeGKProvider","IsMergQAPolls","FBReels_enable_meta_ai_label_gk","FBReelsMediaFooter_comet_enable_reels_ads_gk","CometUFIReactionsEnableShortName","CometUFIShareActionMigration","CometUFI_dedicated_comment_routable_dialog_gk","StoriesArmadilloReplyEnabled","FBReelsIFUTileContent_reelsIFUPlayOnHover","GroupsCometGYSJFeedItemHeight","StoriesShouldIncludeFbNotes","CometFeedStoryCopyLinkMenuItem_isLinkSharingEnabled","isAttachmentsQueryOptimizationEnabled","isWaAddressableFieldsEnabled","GroupsCometGroupChatLazyLoadLastMessageSnippet","GroupsCometLazyLoadFeaturedSection","CometVideoHomeCopyLinkMenuItem_isLinkSharingEnabled","MWEBNewUserOnboardingShouldLoadUpsells","CometUnifiedVideoCreation_showPrivacyMerge").forEach {addProperty("__relay_internal__pv__${it}relayprovider",0)}
            addProperty("scale",1);addProperty("useDefaultActor",false)
            addProperty("allow_streaming",false)
            add("args",JsonObject().apply {
                addProperty("callsite","COMET_GLOBAL_SEARCH")
                add("config",JsonObject().apply {addProperty("exact_match",false);listOf("high_confidence_config","intercept_config","sts_disambiguation","watch_config").forEach {add(it,null)}})
                add("context",JsonObject())
                add("experience",JsonObject().apply {add("client_defined_experiences",com.google.gson.JsonArray().apply {add("ADS_PARALLEL_FETCH")});add("encoded_server_defined_params",null);add("fbid",null);addProperty("type","POSTS_TAB")})
                add("filters",com.google.gson.JsonArray());addProperty("text",query)
            })
            addProperty("count",5);addProperty("cursor",cursor);addProperty("feedLocation","SEARCH");addProperty("feedbackSource",23)
            addProperty("fetch_filters",true);add("focusCommentID",null);add("locale",null);addProperty("privacySelectorRenderLocation","COMET_STREAM")
            addProperty("renderLocation","search_results_page");addProperty("stream_initial_count",0)
        }
    }
    fun directoryVariables(query: String,tab: String): JsonObject {
        require(tab in setOf("PAGES_TAB","PEOPLE_TAB")) {"Unsupported Facebook account search"}
        return searchVariables(query,null).apply {getAsJsonObject("args").getAsJsonObject("experience").addProperty("type",tab)}
    }
    fun alternateSearchUrl(value: String,target: Target,source: Source,category: String="posts") {
        require(category in setOf("posts","pages","people")) {"Unsupported Facebook search category"}
        val url=value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Facebook search continuation")
        val root=source.root.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Facebook source")
        require(url.username.isEmpty()&&url.password.isEmpty()&&DarwinRoutes.matches(URI(value),source.root)&&url.encodedPath==root.encodedPath.trimEnd('/')+"/search"&&url.queryParameterValues("q")==listOf(searchText(target))&&url.queryParameterValues("t")==listOf(category)){"Facebook continuation changed its search query"}
    }
    fun timelineVariables(owner: String,cursor: String?)=variables().apply {
        require(owner.matches(Regex("[0-9]{1,32}")))
        addProperty("id",owner);addProperty("count",3);addProperty("cursor",cursor);addProperty("feedLocation","TIMELINE");addProperty("feedbackSource",0);addProperty("omitPinnedPost",true);addProperty("privacySelectorRenderLocation","COMET_STREAM");addProperty("renderLocation","timeline");addProperty("run_with_continuation_key",false);addProperty("stream_count",1)
    }
    fun storyVariables(story: String)=variables().apply {addProperty("storyID",story);addProperty("feedbackSource",2);addProperty("feedLocation","POST_PERMALINK_DIALOG");add("focusCommentID",null);addProperty("privacySelectorRenderLocation","COMET_STREAM");addProperty("renderLocation","permalink")}
    fun commentVariables(feedback: String,cursor: String?,ranked: Boolean)=variables().apply {
        addProperty("id",feedback);addProperty("feedLocation","POST_PERMALINK_DIALOG");add("focusCommentID",null)
        addProperty("commentsIntentToken",if(ranked)"RANKED_FILTERED_INTENT_V1"else "REVERSE_CHRONOLOGICAL_UNFILTERED_INTENT_V1")
        if(cursor==null)addProperty("feedbackSource",2)else {addProperty("commentsAfterCount",-1);addProperty("commentsAfterCursor",cursor);add("commentsBeforeCount",null);add("commentsBeforeCursor",null)}
    }
    private fun JsonElement?.obj()=this?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonElement?.str()=this?.takeIf {it.isJsonPrimitive}?.asString
    private fun JsonObject.at(vararg path: String): JsonElement? {var value: JsonElement?=this;path.forEach {value=value.obj()?.get(it)};return value}
    private fun JsonObject.s(vararg path: String)=at(*path).str()
    private fun JsonObject.long(vararg path: String)=s(*path)?.toLongOrNull()
    private fun JsonObject.list(vararg path: String)=at(*path)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    private fun objects(roots: List<JsonElement>): List<JsonObject> {
        val found=ArrayList<JsonObject>();var remaining=50_000
        fun visit(value: JsonElement,depth: Int) {
            if(depth>80||--remaining<0)return
            if(value.isJsonObject){found+=value.asJsonObject;value.asJsonObject.entrySet().forEach {visit(it.value,depth+1)}}
            else if(value.isJsonArray)value.asJsonArray.forEach {visit(it,depth+1)}
        }
        roots.forEach {visit(it,0)};return found
    }
    private fun documents(text: String): List<JsonElement> {
        val clean=text.trim().removePrefix("for (;;);")
        if(clean.startsWith("<"))return Jsoup.parse(clean).select("script[type=application/json]").take(128).mapNotNull {runCatching {JsonParser.parseString(it.data())}.getOrNull()}
        return clean.lineSequence().filter {it.isNotBlank()}.take(256).mapNotNull {runCatching {JsonParser.parseString(it)}.getOrNull()}.toList()
    }
    private fun safeUrl(value: String?): String?=value?.takeIf {it.length<=8192&&it.toHttpUrlOrNull()?.let {url->url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()}==true}
    private fun facebookUrl(value: String?): String?=safeUrl(value)?.takeIf {it.toHttpUrlOrNull()?.host.let {host->host=="facebook.com"||host?.endsWith(".facebook.com")==true}}
    private fun text(value: String?)=value.orEmpty().take(100_000)
    private fun key(url: String)=ContentIdentity.key(Platform.FACEBOOK,url)
    private fun rootUrl(target: Target)=target.canonicalUrl.toHttpUrlOrNull()!!.newBuilder().removeAllQueryParameters("sort").removeAllQueryParameters("comment_id").removeAllQueryParameters("reply_comment_id").fragment(null).build().toString()
    fun numericProfileId(target: Target): String? {
        val uri=target.canonicalUrl.toHttpUrlOrNull() ?: return null
        return (if(uri.encodedPath=="/profile.php")uri.queryParameter("id")else uri.pathSegments.lastOrNull {it.isNotBlank()})?.takeIf {it.matches(Regex("[0-9]{1,32}"))}
    }
    fun profileId(payload: Payload,target: Target): String {
        val wanted=target.identity.trim('/').removePrefix("@").lowercase()
        val candidates=objects(documents(payload.text)).filter {it.s("userID")?.matches(Regex("[0-9]{1,32}"))==true&&it.s("userVanity")?.lowercase()==wanted}.mapNotNull {it.s("userID")}.distinct()
        if(candidates.size==1)return candidates.single()
        // These fields can live inside a JSON string that the public HTML embeds.
        val pairs=Regex("\\\"userID\\\":\\\"([0-9]{1,32})\\\",\\\"userVanity\\\":\\\"([^\\\"]+)\\\"").findAll(payload.text).filter {it.groupValues[2].equals(wanted,true)}.map {it.groupValues[1]}.distinct().toList()
        if(pairs.size==1)return pairs.single()
        fail(payload,"This route did not identify the selected public Facebook profile")
    }
    fun numericStoryId(target: Target): String? {
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty()
        if(path.size>=3&&path[0].matches(Regex("[0-9]{1,32}"))&&path[1]=="posts"&&path[2].matches(Regex("[0-9]{1,32}")))return Base64.getEncoder().encodeToString("S:_I${path[0]}:${path[2]}:${path[2]}".toByteArray())
        return null
    }
    fun storyId(payload: Payload,target: Target): String? {
        val all=objects(documents(payload.text))
        all.firstOrNull {it.s("storyID")!=null}?.s("storyID")?.takeIf {it.length in 8..512}?.let {return it}
        return stories(all).firstOrNull {matchesPost(it,target)}?.s("id")
    }
    private fun stories(all: List<JsonObject>)=all.filter {it.at("comet_sections","content","story").obj()!=null&&it.s("id")!=null}.distinctBy {it.s("id")}
    private fun postToken(url: String): String? {
        val uri=url.toHttpUrlOrNull() ?: return null
        return Regex("/(?:posts|videos|reel)/([^/]+)").find(uri.encodedPath)?.groupValues?.get(1) ?: uri.queryParameter("story_fbid") ?: uri.queryParameter("fbid")
    }
    private fun matchesPost(story: JsonObject,target: Target): Boolean {
        val wanted=postToken(target.canonicalUrl) ?: return false
        return wanted==story.s("post_id")||postToken(story.s("permalink_url").orEmpty())==wanted||story.at("comet_sections","content","story").obj()?.let {wanted==it.s("post_id")||postToken(it.s("wwwURL").orEmpty())==wanted}==true
    }
    private fun feedback(story: JsonObject): JsonObject? {
        val section=story.at("comet_sections","feedback").obj() ?: return null
        return objects(listOf(section)).firstOrNull {it.at("reaction_count","count")!=null||it.at("comment_rendering_instance","comments")!=null} ?: objects(listOf(section)).firstOrNull {it.s("id")?.startsWith("ZmVlZGJhY2s6")==true}
    }
    fun feedbackId(payload: Payload,storyId: String)=stories(objects(documents(payload.text))).firstOrNull {it.s("id")==storyId}?.let {feedback(it)?.s("id") ?: it.s("feedback","id")}
    private fun media(element: JsonElement?): List<Media> {
        if(element==null)return emptyList()
        return objects(listOf(element)).filter {it.s("__typename") in setOf("Photo","Video","Image","GenericAttachmentMedia")}.mapNotNull {item->
            val video=item.at("videoDeliveryLegacyFields").obj()
            val stream=safeUrl(video?.s("browser_native_hd_url")) ?: safeUrl(video?.s("browser_native_sd_url"))
            val image=listOf("viewer_image","photo_image","image","large_share_image","placeholder_image","fallback_image").firstNotNullOfOrNull {safeUrl(item.s(it,"uri"))}
            val preview=safeUrl(item.s("preferred_thumbnail","image","uri")) ?: image
            if(stream!=null)Media(stream,"video",preview,text(item.s("accessibility_caption")))else (image ?: preview)?.let {Media(it,"image",description=if(item.s("__typename")=="Video")"Video preview; full media unavailable"else text(item.s("accessibility_caption")))}
        }.distinctBy {it.url}.take(16)
    }
    private fun parsedPost(node: JsonObject,target: Target,source: Source,payload: Payload,selected: Boolean=false): Post? {
        val story=node.at("comet_sections","content","story").obj() ?: return null
        val actor=story.list("actors").firstOrNull().obj() ?: node.list("actors").firstOrNull().obj() ?: return null
        val actorId=actor.s("id") ?: return null
        val postId=node.s("post_id") ?: story.s("post_id")
        val permalink=facebookUrl(node.s("permalink_url"))
        // A public group story belongs to its group route, not necessarily to
        // the actor's personal timeline. Keep that publicly supplied context.
        val groupPermalink=permalink?.takeIf {it.toHttpUrlOrNull()?.pathSegments?.firstOrNull()=="groups"&&postToken(it)!=null}
        val url=if(selected)rootUrl(target)else groupPermalink ?: if(actorId.matches(Regex("[0-9]{1,32}"))&&postId?.matches(Regex("[0-9]{1,32}"))==true)"https://www.facebook.com/$actorId/posts/$postId"else permalink ?: return null
        fun message(value: JsonObject): String? {
            val section=value.at("comet_sections","message").obj()
            return section?.list("rich_message")?.mapNotNull {it.obj()?.s("text")}?.joinToString("\n")?.takeIf {it.isNotBlank()} ?: section?.s("story","message","text") ?: value.s("message","text")
        }
        val shared=story.at("attached_story").obj()
        val sharedText=shared?.let(::message)?.takeIf {it.isNotBlank()}
        val body=listOfNotNull(message(story),sharedText?.let {"Shared from ${shared?.list("actors")?.firstOrNull().obj()?.s("name").orEmpty()}:\n$it"}).joinToString("\n\n")
        val attachments=(media(story.at("attachments"))+media(shared?.at("attachments"))).distinctBy {it.url}.take(16)
        if(body.isNullOrBlank()&&attachments.isEmpty())return null
        val created=node.long("creation_time") ?: node.long("comet_sections","timestamp","story","creation_time") ?: objects(listOf(node.at("comet_sections","context_layout") ?: JsonObject())).firstNotNullOfOrNull {it.long("creation_time")}
        val avatar=safeUrl(actor.s("profile_picture","uri")) ?: objects(listOf(node.at("comet_sections","context_layout") ?: JsonObject())).firstOrNull {it.s("id")==actorId&&it.s("profile_picture","uri")!=null}?.let {safeUrl(it.s("profile_picture","uri"))}
        val feedback=feedback(node)
        val links=objects(listOf(story)).mapNotNull {safeUrl(it.s("external_url")) ?: safeUrl(it.s("web_link","url"))}.distinct().take(32)
        return Post(key(url),Platform.FACEBOOK,url,text(actor.s("name")),text(body),publishedAt=created?.times(1000),avatar=avatar,media=attachments,replyCount=feedback?.long("comment_rendering_instance","comments","total_count"),score=feedback?.long("reaction_count","count"),provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl),links=links)
    }
    private fun directoryProfile(url: String?,name: String?,description: String,avatar: String?,routes: DarwinRoutes): Profile? {
        val original=facebookUrl(url) ?: return null
        val target=routes.recognize(original)?.takeIf {it.platform==Platform.FACEBOOK&&it.kind==TargetKind.PROFILE&&PublicSearchAdapter.profileAllowed(it)} ?: return null
        val title=name?.trim()?.take(300)?.takeIf {it.isNotBlank()} ?: return null
        val canonical=target.canonicalUrl.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/') ?: return null
        return Profile(target.copy(canonicalUrl=canonical),title,description.take(4000),safeUrl(avatar))
    }
    /** Explicit directory edges only; never mine posts or nested recommendation
     * objects for account names. The caller owns a separate directory window. */
    fun directory(payload: Payload,target: Target,source: Source,routes: DarwinRoutes,tab: String): Page {
        searchText(target)
        require(tab in setOf("PAGES_TAB","PEOPLE_TAB")) {"Unsupported Facebook account search"}
        val docs=documents(payload.text)
        val response=docs.mapNotNull {it.obj()?.at("data","serpResponse","results").obj()}.firstOrNull()
            ?: fail(payload,"This route did not return public Facebook account results")
        if(response.at("edges")?.isJsonArray!=true)fail(payload,"This route did not return a Facebook account search connection")
        val role=if(tab=="PAGES_TAB")"ENTITY_PAGES"else "ENTITY_USER"
        val accounts=response.list("edges").take(64).mapNotNull {item->
            val edge=item.obj() ?: return@mapNotNull null
            if(edge.s("node","role")!=role)return@mapNotNull null
            val view=edge.at("rendering_strategy","view_model").obj() ?: return@mapNotNull null
            val profile=view.at("profile").obj() ?: return@mapNotNull null
            if(profile.s("id")?.matches(Regex("[0-9]{1,40}|pfbid[A-Za-z0-9]{1,195}"))!=true)return@mapNotNull null
            val description=view.list("description_snippets_text_with_entities").mapNotNull {it.obj()?.s("text")?.trim()?.takeIf(String::isNotBlank)}.distinct().take(8).joinToString("\n")
            directoryProfile(profile.s("url"),profile.s("name"),description,profile.s("profile_picture","uri"),routes)
        }.distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}.take(24)
        if(accounts.isEmpty())fail(payload,"This route supplied no matching public Facebook accounts")
        return Page(emptyList(),accounts=accounts,provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl),notice=warning(objects(docs)))
    }
    /** Phice's search-card template is a directory result, unlike post authors. */
    fun phiceDirectory(payload: Payload,target: Target,source: Source,routes: DarwinRoutes): Page {
        searchText(target)
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        val basePath=URI(source.root).rawPath.orEmpty().trimEnd('/')
        val accounts=document.select(".search_card").take(64).mapNotNull {card->
            val href=card.closest("a[href]")?.attr("href") ?: return@mapNotNull null
            val link=runCatching {URI(payload.finalUrl).resolve(href)}.getOrNull() ?: return@mapNotNull null
            if(link.userInfo!=null||!DarwinRoutes.matches(link,source.root))return@mapNotNull null
            val original="https://www.facebook.com"+link.rawPath.removePrefix(basePath)+(link.rawQuery?.let {"?$it"}.orEmpty())
            directoryProfile(original,card.selectFirst(".search_body > span")?.text(),card.selectFirst(".search_card_description")?.text().orEmpty(),card.selectFirst(".search_card_picture[src]")?.absUrl("src"),routes)
        }.distinctBy {ContentIdentity.key(it.target.platform,it.target.canonicalUrl)}.take(24)
        if(accounts.isEmpty())fail(payload,"This alternate supplied no matching public Facebook accounts")
        return Page(emptyList(),accounts=accounts,provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl))
    }
    /** Only explicit search-result edges qualify. Recommended posts, nested
     * quotes, comments and bootstrap objects are not extra search hits. */
    fun search(payload: Payload,target: Target,source: Source): Page {
        searchText(target)
        val docs=documents(payload.text)
        val response=docs.mapNotNull {it.obj()?.at("data","serpResponse","results").obj()}.firstOrNull()
            ?: fail(payload,"This route did not return public Facebook search results")
        if(response.at("edges")?.isJsonArray!=true)fail(payload,"This route did not return a public Facebook search connection")
        val posts=response.list("edges").take(64).mapNotNull {entry->
            val edge=entry.obj() ?: return@mapNotNull null
            if(edge.s("node","role")!="TOP_PUBLIC_POSTS")return@mapNotNull null
            val view=edge.at("rendering_strategy","view_model").obj() ?: return@mapNotNull null
            val story=view.at("click_model","story").obj() ?: view.at("story").obj() ?: return@mapNotNull null
            if(story["sponsored_data"].let {it!=null&&!it.isJsonNull})return@mapNotNull null
            parsedPost(story,target,source,payload)
        }.distinctBy {it.id}.sortedWith(compareByDescending<Post> {it.publishedAt ?: Long.MIN_VALUE}.thenBy {it.id}).take(24)
        val pageInfo=response.at("page_info").obj() ?: docs.mapNotNull {it.obj()}.firstOrNull {it.s("label")?.contains("SearchCometResults")==true&&it.list("path").let {path->path.size==2&&path[0].str()=="serpResponse"&&path[1].str()=="results"}}?.at("data","page_info").obj()
        val continuation=pageInfo?.let(::searchNext)
        val partial=warning(objects(docs))
        if(posts.isEmpty()&&(continuation==null||partial!=null))fail(payload,"This route supplied no readable public Facebook posts for this search")
        // This block is progress, not evidence of working content. SourcePool
        // keeps it EMPTY while the bounded search reader follows its cursor.
        return Page(posts,next=continuation,provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl),notice=if(posts.isEmpty())"This search block contained no readable public posts; more results are available."else partial)
    }
    fun timeline(payload: Payload,target: Target,source: Source,owner: String): Page {
        val documents=documents(payload.text);val all=objects(documents)
        val nodes=stories(all).filter {node->node.get("sponsored_data").let {it==null||it.isJsonNull}&&(node.list("actors").firstOrNull().obj()?.s("id") ?: node.at("comet_sections","content","story").obj()?.list("actors")?.firstOrNull().obj()?.s("id"))==owner}
        val posts=nodes.mapNotNull {parsedPost(it,target,source,payload)}.filter {it.publishedAt!=null}.distinctBy {it.id}.sortedByDescending {it.publishedAt}.take(24)
        if(posts.isEmpty())fail(payload,"This route returned no dated public posts for the selected Facebook profile")
        val actor=nodes.firstOrNull()?.list("actors")?.firstOrNull().obj()
        val connection=all.firstNotNullOfOrNull {it.at("timeline_list_feed_units").obj()}
        val next=connection?.at("page_info").obj()?.let(::next) ?: documents.mapNotNull {it.obj()}.firstOrNull {it.s("label")?.contains("ProfileCometTimelineFeed_user_timeline_list_feed_units\$page_info")==true&&it.list("path").lastOrNull().str()=="timeline_list_feed_units"}?.at("data","page_info").obj()?.let(::next)
        return Page(posts,next,Profile(target,text(actor?.s("name")).ifBlank {posts.first().author},avatar=posts.first().avatar),Provenance(source,System.currentTimeMillis(),payload.finalUrl),notice=warning(all))
    }
    fun post(payload: Payload,target: Target,source: Source): Page {
        val all=objects(documents(payload.text));val node=stories(all).firstOrNull {matchesPost(it,target)}
        val post=node?.let {parsedPost(it,target,source,payload,true)} ?: fail(payload,"This route did not return the selected public Facebook post")
        return Page(listOf(post),provenance=post.provenance,notice=warning(all))
    }
    private fun next(info: JsonObject)=info.s("end_cursor")?.takeIf {info.s("has_next_page")=="true"&&it.isNotBlank()&&it.length<=8*1024}
    private fun searchNext(info: JsonObject): String? {
        val more=info.at("has_next_page")?.takeIf {it.isJsonPrimitive}?.asJsonPrimitive
        val token=info.at("end_cursor")?.takeIf {it.isJsonPrimitive}?.asJsonPrimitive
        return token?.takeIf {it.isString&&more?.isBoolean==true&&more.asBoolean}?.asString?.takeIf {it.isNotBlank()&&it.length<=8*1024&&!it.any(Char::isISOControl)}
    }
    private fun commentUrl(root: String,id: String)=root.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("comment_id",id).build().toString()
    fun comments(payload: Payload,target: Target,source: Source,feedback: String,allowEmpty: Boolean=false): Page {
        val all=objects(documents(payload.text));val container=all.firstOrNull {it.s("id")==feedback&&it.at("comment_rendering_instance_for_feed_location","comments")!=null}
        val connection=container?.at("comment_rendering_instance_for_feed_location","comments").obj() ?: fail(payload,"This route did not return the selected Facebook discussion")
        val root=rootUrl(target);val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val comments=objects(listOf(connection)).filter {it.s("__typename")=="Comment"}.mapNotNull {node->
            val id=node.s("legacy_fbid")?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            val parentFeedback=node.s("parent_feedback","id")
            if(parentFeedback!=null&&parentFeedback!=feedback)return@mapNotNull null
            val author=node.at("author").obj() ?: return@mapNotNull null
            val body=text(node.s("body","text"));val attachments=media(node.at("attachments"))
            if(body.isBlank()&&attachments.isEmpty())return@mapNotNull null
            val url=commentUrl(root,id)
            val parent=node.s("comment_direct_parent","legacy_fbid") ?: node.s("comment_parent","legacy_fbid")
            Post(key(url),Platform.FACEBOOK,url,text(author.s("name")),body,publishedAt=node.long("created_time")?.times(1000),avatar=safeUrl(author.s("profile_picture_depth_0","uri")),media=attachments,replyCount=node.long("feedback","replies_fields","total_count"),score=node.long("feedback","reaction_count","count") ?: node.at("feedback").obj()?.list("top_reactions","edges")?.sumOf {it.obj()?.long("reaction_count") ?: 0},parentId=if(parent==null)key(root)else key(commentUrl(root,parent)),provenance=provenance)
        }.distinctBy {it.id}.take(100).let {if(ranked(target))it else it.sortedByDescending {p->p.publishedAt}}
        if(comments.isEmpty()&&!allowEmpty)fail(payload,"This route returned no readable comments for the selected Facebook post")
        return Page(comments,next(connection.at("page_info").obj() ?: JsonObject()),provenance=provenance,notice=warning(all))
    }
    private fun warning(all: List<JsonObject>)=if(all.any {it.at("errors")?.isJsonArray==true&&it.list("errors").isNotEmpty()})"Facebook returned partial data; some attachments or fields may be unavailable."else null
    private fun fail(payload: Payload,message: String): Nothing {
        val body=payload.text.take(100_000).lowercase()
        val status=when {body.contains("rate limit")||body.contains("1675004")->HealthStatus.RATE_LIMITED;body.contains("checkpoint")||body.contains("captcha")->HealthStatus.CHALLENGE;body.contains("log in to continue")||body.contains("login_required")||payload.finalUrl.contains("/login")->HealthStatus.LOGIN_REQUIRED;else->HealthStatus.EMPTY}
        throw SourceFailure(status,message)
    }
    fun phice(payload: Payload,target: Target,source: Source): Page {
        val document=Jsoup.parse(payload.text,payload.finalUrl);val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val profile=target.kind==TargetKind.PROFILE
        val listing=profile||target.kind==TargetKind.SEARCH
        val root=rootUrl(target)
        val posts=document.select("article.post").mapNotNull {article->
            val relative=article.selectFirst("a.post_link[href]")?.attr("href") ?: return@mapNotNull null
            val link=URI(payload.finalUrl).resolve(relative)
            if(!DarwinRoutes.matches(link,source.root))return@mapNotNull null
            val url=facebookUrl("https://www.facebook.com${link.rawPath}"+(link.rawQuery?.let {"?$it"}.orEmpty())) ?: return@mapNotNull null
            if(!listing&&postToken(url)!=postToken(root))return@mapNotNull null
            val body=article.select(".post_text > span > span").lastOrNull()?.text() ?: article.selectFirst(".post_text")?.text().orEmpty()
            val media=article.select(".post_attachment img[src], .post_attachment video[src]").mapNotNull {element->safeUrl(element.absUrl("src"))?.let {Media(it,if(element.tagName()=="video")"video"else "image",preview=safeUrl(element.absUrl("poster")))}}.take(16)
            if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            val canonical=if(listing)url else root
            Post(key(canonical),Platform.FACEBOOK,canonical,article.selectFirst(".post_name b")?.text().orEmpty(),body,publishedAt=article.selectFirst(".post_time [title]")?.attr("title")?.let(::phiceTime),avatar=safeUrl(article.selectFirst(".post_profile_picture")?.absUrl("src")),media=media,provenance=provenance)
        }.distinctBy {it.id}.let {if(profile)it.filter {post->post.publishedAt!=null}else it}
        if(posts.isEmpty())fail(payload,"This alternate returned no recognizable Facebook posts")
        val replies=if(listing)emptyList()else document.select("article.comment").mapNotNull {article->
            val relative=article.selectFirst("a.comment_link[href]")?.attr("href") ?: return@mapNotNull null
            val link=URI(payload.finalUrl).resolve(relative);if(!DarwinRoutes.matches(link,source.root))return@mapNotNull null
            val id=link.toString().toHttpUrlOrNull()?.queryParameter("comment_id")?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            val body=(article.select(".comment_text > span > span").lastOrNull()?.text() ?: article.selectFirst(".comment_text")?.text())?.takeIf {it.isNotBlank()} ?: return@mapNotNull null
            val url=commentUrl(root,id)
            Post(key(url),Platform.FACEBOOK,url,article.selectFirst(".comment_header .profile_name")?.text().orEmpty(),body,publishedAt=article.selectFirst(".comment_time[title]")?.attr("title")?.let(::phiceTime),parentId=key(root),provenance=provenance)
        }
        val next=document.select("#navigation_buttons a[href]").mapNotNull {a->runCatching {URI(payload.finalUrl).resolve(a.attr("href"))}.getOrNull()}.firstOrNull {DarwinRoutes.matches(it,source.root)&&DarwinRoutes.queryValue(it,"cursor")!=null&&(target.kind!=TargetKind.SEARCH||runCatching {alternateSearchUrl(it.toString(),target,source)}.isSuccess)}?.toString()
        return Page((if(listing)posts.sortedByDescending {it.publishedAt ?: Long.MIN_VALUE}else posts+replies),next,profile=if(profile)Profile(target,posts.first().author,avatar=posts.first().avatar)else null,provenance=provenance)
    }
    private fun phiceTime(value: String): Long? {
        runCatching {java.time.Instant.parse(value).toEpochMilli()}.getOrNull()?.let {return it}
        val phice=Regex("^(.+) UTC([+-][0-9]{1,2})$").matchEntire(value) ?: return null
        return runCatching {java.time.LocalDateTime.parse(phice.groupValues[1],java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd - hh:mm:ss a",java.util.Locale.US)).toInstant(java.time.ZoneOffset.ofHours(phice.groupValues[2].toInt())).toEpochMilli()}.getOrNull()
    }
}
