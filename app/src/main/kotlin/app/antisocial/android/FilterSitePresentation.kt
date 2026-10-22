/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.Platform

/** Describe actual normalized post fields. No flair, engagement, inferred intent,
 * or feed membership is promised when a public source did not supply it. */
internal data class FilterSitePresentation(val wording: String,val tags: String?="Hashtags",val account: String="Account",val photos: String="Images")
internal fun filterSitePresentation(site: Platform?): FilterSitePresentation=when(site) {
    null->FilterSitePresentation("Words or phrases",tags=null)
    Platform.REDDIT->FilterSitePresentation("Title or post text",tags=null,account="Author")
    Platform.LEMMY,Platform.TIEBA->FilterSitePresentation("Title or post text",tags=null,account="Author")
    Platform.INSTAGRAM,Platform.TIKTOK,Platform.PIXELFED->FilterSitePresentation("Caption words",photos="Photos")
    Platform.SNAPCHAT->FilterSitePresentation("Caption words",tags=null,account="Creator",photos="Photos")
    Platform.PINTEREST->FilterSitePresentation("Pin title or description",tags=null,account="Creator")
    Platform.TELEGRAM->FilterSitePresentation("Message text",account="Channel")
    Platform.TUMBLR->FilterSitePresentation("Blog post text",tags="Tags in text",account="Blog")
    Platform.FACEBOOK->FilterSitePresentation("Post text",account="Page or account")
    Platform.LINKEDIN->FilterSitePresentation("Update text",account="Organization or author")
    Platform.RSS->FilterSitePresentation("Article text",tags=null,account="Website")
    else->FilterSitePresentation("Post text")
}
