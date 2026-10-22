/* AntiSocial, GPL-3.0-or-later. Individual vector artwork keeps the licenses in licenses/platform-icons.md. */
package app.antisocial.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antisocial.core.Platform

/** Bundled vectors only: every platform has a mark and none needs a network icon request. */
@DrawableRes internal fun platformIcon(platform: Platform): Int = when(platform) {
    Platform.X -> R.drawable.platform_x
    Platform.BLUESKY -> R.drawable.platform_bluesky
    Platform.REDDIT -> R.drawable.platform_reddit
    Platform.TUMBLR -> R.drawable.platform_tumblr
    Platform.TIKTOK -> R.drawable.platform_tiktok
    Platform.INSTAGRAM -> R.drawable.platform_instagram
    Platform.PINTEREST -> R.drawable.platform_pinterest
    Platform.IFUNNY -> R.drawable.platform_ifunny
    Platform.TIEBA -> R.drawable.platform_tieba
    Platform.THREADS -> R.drawable.platform_threads
    Platform.FACEBOOK -> R.drawable.platform_facebook
    Platform.TRUTH_SOCIAL -> R.drawable.platform_truthsocial
    Platform.MASTODON -> R.drawable.platform_mastodon
    Platform.LEMMY -> R.drawable.platform_lemmy
    Platform.PIXELFED -> R.drawable.platform_pixelfed
    Platform.MISSKEY -> R.drawable.platform_misskey
    Platform.GETTR -> R.drawable.platform_gettr
    Platform.GAB -> R.drawable.platform_gab
    Platform.TELEGRAM -> R.drawable.platform_telegram
    Platform.LINKEDIN -> R.drawable.platform_linkedin
    Platform.SNAPCHAT -> R.drawable.platform_snapchat
    Platform.YOUTUBE -> R.drawable.platform_youtube
    Platform.RSS -> R.drawable.platform_rss
}

/** Quiet, optically aligned marks; the active tab/chip supplies the selection colour. */
@Composable internal fun PlatformMark(p: Platform,size: Dp,contentDescription: String?=p.label) {
    val colors=MaterialTheme.colorScheme
    val dark=colors.surface.luminance()<.5f
    val tint=colors.onSurface.copy(alpha=if(dark).88f else .82f)
    val tile=size>=24.dp
    // Small attribution/comment marks stay crisp instead of squeezing a tile around them.
    val scale=if(tile)when(p) {
        Platform.TUMBLR,Platform.PINTEREST -> .67f
        Platform.LEMMY,Platform.GETTR,Platform.TIEBA -> .84f
        Platform.IFUNNY -> .80f
        else -> .72f
    } else 1f
    Box(Modifier.size(size).then(if(tile)Modifier.background(colors.onSurface.copy(alpha=if(dark).055f else .045f),RoundedCornerShape(28))else Modifier),contentAlignment=Alignment.Center) {
        Icon(painterResource(platformIcon(p)),contentDescription,Modifier.size(size*scale),tint=tint)
    }
}
