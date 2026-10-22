/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** Social sources populate feeds; YouTube supplies explicitly opened video media.
 * Keep its platform identifier for routing, saved copies and video discussions. */
object ReaderScope {
    val platforms: List<Platform> = Platform.entries.filterNot {it==Platform.YOUTUBE}
    const val VIDEO_ONLY = "anti social opens linked YouTube videos. Channel feeds and YouTube search belong in a video app."
    fun isFeedSource(platform: Platform)=platform!=Platform.YOUTUBE
    fun active(follow: Follow)=follow.enabled&&isFeedSource(follow.target.platform)
    fun canOpen(target: Target)=isFeedSource(target.platform)||
        target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&YouTubeUrls.videoId(target.canonicalUrl)!=null
    fun handles(target: Target,selected: kotlin.collections.Collection<Platform>)=
        if(target.platform==Platform.YOUTUBE)canOpen(target)else target.platform in selected
}
