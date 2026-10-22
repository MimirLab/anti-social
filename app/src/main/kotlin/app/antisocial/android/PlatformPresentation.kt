package app.antisocial.android

import app.antisocial.core.Platform

/** Input guidance describes the available public surface, not a promise of live access. */
internal fun platformScope(platform: Platform): String? = when(platform) {
    Platform.YOUTUBE -> "Linked video playback with SponsorBlock and archive recovery."
    Platform.RSS -> "RSS and Atom feed text and media, in chronological order. Full articles may contain more."
    Platform.FACEBOOK -> "Public Pages and posts; personal profiles may be limited."
    Platform.TRUTH_SOCIAL -> "Public posts; access can be blocked by the source."
    Platform.MASTODON, Platform.PIXELFED, Platform.MISSKEY -> "Use a full @person@server address or public link."
    Platform.LEMMY -> "Use a full community or post link, including its server."
    Platform.TELEGRAM -> "Public channel posts; discussion access varies."
    Platform.LINKEDIN -> "Public company posts; member profiles may be limited."
    Platform.SNAPCHAT -> "Public profile highlights; comment access is limited."
    else -> null
}

internal fun platformInputLabel(platform: Platform): String = when(platform) {
    Platform.YOUTUBE -> "YouTube video link"
    Platform.RSS -> "HTTPS RSS or Atom feed URL"
    Platform.MASTODON, Platform.PIXELFED, Platform.MISSKEY -> "@person@server or public link"
    Platform.LEMMY -> "Community or post link"
    Platform.LINKEDIN -> "Company name or public company link"
    Platform.FACEBOOK -> "Page name or public post link"
    Platform.TELEGRAM -> "Channel name or public post link"
    Platform.SNAPCHAT -> "Public profile name or link"
    else -> "Username or public link"
}
