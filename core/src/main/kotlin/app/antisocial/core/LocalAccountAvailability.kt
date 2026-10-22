/* AntiSocial, GPL-3.0-or-later. A discovered URL is a lead, not working content. */
package app.antisocial.core

object LocalAccountAvailability {
    fun usable(target:Target,page:Page):Boolean {
        if(page.posts.any {!it.preview&&(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty())&&it.platform==target.platform})return true
        val profile=page.profile ?: return false
        if(profile.target.platform!=target.platform||ContentIdentity.key(target.platform,profile.target.canonicalUrl)!=ContentIdentity.key(target.platform,target.canonicalUrl))return false
        // A fabricated handle/default avatar or a reachable empty frontend is
        // not readable public profile data. A supplied display name or public
        // biography/contact link is useful even if the timeline is unavailable.
        return profile.description.isNotBlank()||profile.links.orEmpty().isNotEmpty()||profile.displayNameSupplied&&profile.name.isNotBlank()&&!profile.name.equals(target.identity,true)
    }
}
