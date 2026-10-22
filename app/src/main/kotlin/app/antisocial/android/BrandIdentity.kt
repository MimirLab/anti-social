/* anti social, GPL-3.0-or-later. Artwork supplied by the project owner. */
package app.antisocial.android

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The supplied wordmark follows the reader's contrast in both display themes. */
@Composable internal fun BrandWordmark(modifier: Modifier=Modifier,height: Dp=24.dp) {
    val artwork=painterResource(R.drawable.brand_wordmark)
    Icon(artwork,stringResource(R.string.app_name),modifier.height(height)
        .aspectRatio(artwork.intrinsicSize.width/artwork.intrinsicSize.height),tint=MaterialTheme.colorScheme.onSurface)
}
