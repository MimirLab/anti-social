/* anti social, GPL-3.0-or-later. A bounded public-page fallback, not a browser. */
package app.antisocial.core

/** Keep this route separate from ordinary transport health and pagination.
 * No account session, JavaScript, login attempt or automatic identity rotation.
 * Alternate sources still run first. This candidate is unavailable without
 * the explicitly installed experimental byte engine. */
class PublicPreviewAdapter(override val source:Source,private val gateway:NetworkGateway,private val routes:DarwinRoutes):DataAdapter {
    override val capabilities=if(gateway.hasPublicReadFactory)capabilities(source.platform)else emptySet()
    override fun accepts(target:Target)=capabilities.isNotEmpty()&&target.platform==source.platform&&target.kind in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD)&&
        (target.kind==TargetKind.PROFILE||Capability.POST in capabilities)
    override suspend fun fetch(target:Target,cursor:String?):Page {
        require(accepts(target)&&cursor==null){"This public preview has no continuation"}
        val payload=gateway.publicPreview(target.canonicalUrl,target,source,if(source.frontend==MOBILE)0 else 1)
        return PublicPreviewPages.parse(payload,target,source,routes)
    }
    override suspend fun feed(target:Target)=fetch(target,null)
    override suspend fun profile(target:Target)=fetch(target,null)
    override fun sentinel(capability:Capability)=routes.target(source.platform,"NASA")
    companion object {
        const val DESKTOP="public-preview-desktop"
        const val MOBILE="public-preview-mobile"
        fun capabilities(platform:Platform):Set<Capability> = when(platform) {
            Platform.X,Platform.FACEBOOK->setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED)
            Platform.INSTAGRAM,Platform.TIKTOK->setOf(Capability.PROFILE)
            else->emptySet()
        }
    }
}

/** Reuse the source-specific readers. A shell/title cannot certify a timeline.
 * A first public window cannot prove that older/unreturned entries were read. */
object PublicPreviewPages {
    const val PARTIAL="Only a public preview was available; full feed coverage is still pending."
    fun parse(payload:Payload,target:Target,source:Source,routes:DarwinRoutes):Page {
        require(source.role==SourceRole.ORIGINAL&&source.platform==target.platform)
        val page=if(target.platform==Platform.FACEBOOK) {
            HtmlAdmission.check(payload.text)
            if(target.kind==TargetKind.PROFILE)FacebookProtocol.timeline(payload,target,source,
                FacebookProtocol.numericProfileId(target) ?: FacebookProtocol.profileId(payload,target))
            else FacebookProtocol.post(payload,target,source)
        }else Extraction.parse(payload,target,source,routes)
        if(target.platform in setOf(Platform.X,Platform.FACEBOOK)&&page.posts.none {it.publishedAt!=null&&!it.preview&&it.parentId==null})
            throw SourceFailure(HealthStatus.EMPTY,"This public page did not supply readable dated posts")
        return page.copy(next=null,notice=page.notice ?: PARTIAL,
            continuationIssue=if(target.kind==TargetKind.PROFILE)PARTIAL else page.continuationIssue)
    }
}
