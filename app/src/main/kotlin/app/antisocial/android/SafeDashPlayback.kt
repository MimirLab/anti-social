/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream
import java.time.Instant

/** Media3 1.8.0 otherwise opens a direct SNTP socket for some live manifests,
 * bypassing the selected gateway. Use the device's clock without auxiliary
 * requests. Live playback consequently assumes the device clock is accurate.
 * Reference: androidx/media 1.8.0 DashMediaSource.resolveUtcTimingElement and
 * onManifestLoadCompleted; all thirteen DashManifest constructor fields retained.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class GatewayDashManifestParser(private val clock: ()->Long=System::currentTimeMillis): ParsingLoadable.Parser<DashManifest> {
    private val parser=DashManifestParser()
    override fun parse(uri: Uri,inputStream: InputStream): DashManifest {
        val manifest=parser.parse(uri,inputStream)
        if(!manifest.dynamic)return manifest
        return DashManifest(
            manifest.availabilityStartTimeMs,manifest.durationMs,manifest.minBufferTimeMs,
            manifest.dynamic,manifest.minUpdatePeriodMs,manifest.timeShiftBufferDepthMs,
            manifest.suggestedPresentationDelayMs,manifest.publishTimeMs,
            manifest.programInformation,
            UtcTimingElement("urn:mpeg:dash:utc:direct:2014",Instant.ofEpochMilli(clock()).toString()),
            manifest.serviceDescription,manifest.location,
            List(manifest.periodCount){manifest.getPeriod(it)}
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun gatewayDashMediaSource(data: DataSource.Factory,item: MediaItem): MediaSource =
    DashMediaSource.Factory(data).setManifestParser(GatewayDashManifestParser()).createMediaSource(item)
