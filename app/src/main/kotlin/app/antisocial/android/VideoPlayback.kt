/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.antisocial.core.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/** Every remote manifest, key and segment uses the same guarded client.
 * A playlist cannot turn the remote player into a file/content/asset reader.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoNetworkDataSource(client: OkHttpClient,sourceUrl: String,cache: Cache?): DataSource.Factory {
    val http=OkHttpDataSource.Factory(client).setDefaultRequestProperties(mapOf("X-AntiSocial-Source" to sourceUrl.toHttpUrl().toString(),"X-AntiSocial-Type" to "media"))
    val data: DataSource.Factory=if(cache==null)http else CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return DataSource.Factory {val upstream=data.createDataSource();object: DataSource by upstream {
        override fun open(dataSpec: DataSpec): Long {
            if(dataSpec.uri.scheme?.lowercase() !in setOf("https","http"))throw IOException("This video contains an unsupported media address")
            return upstream.open(dataSpec)
        }
    }}
}


/** YouTube commonly delivers separate audio/video content tracks. Both stay on
 * the same filtered transport; no ad player or advertising manifest is loaded. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun gatewayPairedMediaSource(data: DataSource.Factory,video: androidx.media3.common.MediaItem,audioUrl: String,audioMime: String?): androidx.media3.exoplayer.source.MediaSource {
    val factory=androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data)
    val audio=androidx.media3.common.MediaItem.Builder().setUri(audioUrl).setMimeType(audioMime ?: "audio/mp4").build()
    return androidx.media3.exoplayer.source.MergingMediaSource(factory.createMediaSource(video),factory.createMediaSource(audio))
}
