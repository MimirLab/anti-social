/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native manifest parsing, with no sockets and no mutable application state. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DashPrivacyTest {
    private val uri=Uri.parse("https://media.invalid/manifest.mpd")
    private val now=1_792_170_000_123L
    private fun xml(timing: String="",dynamic: Boolean=true)="""
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="${if(dynamic)"dynamic"else "static"}" availabilityStartTime="2026-10-16T10:00:00Z" publishTime="2026-10-16T12:00:00Z" mediaPresentationDuration="PT10M" minimumUpdatePeriod="PT5S" minBufferTime="PT1.5S" timeShiftBufferDepth="PT60S" suggestedPresentationDelay="PT15S">
          <ProgramInformation lang="en" moreInformationURL="https://example.invalid/info"><Title>Public fixture</Title><Source>Example</Source><Copyright>Fixture</Copyright></ProgramInformation>
          $timing
          <ServiceDescription id="0"><Latency target="15000" min="10000" max="20000"/><PlaybackRate min="0.95" max="1.05"/></ServiceDescription>
          <Location>https://media.invalid/refreshed.mpd</Location>
          <Period id="first" start="PT0S">
            <AdaptationSet id="1" contentType="video" mimeType="video/mp4">
              <Representation id="main" bandwidth="1000" width="16" height="16" codecs="avc1.42001e"><BaseURL>https://media.invalid/video.mp4</BaseURL><SegmentBase indexRange="100-200"><Initialization range="0-99"/></SegmentBase></Representation>
            </AdaptationSet>
            <EventStream schemeIdUri="urn:antisocial:fixture" timescale="1"><Event id="1" presentationTime="1" duration="1">Fixture event</Event></EventStream>
          </Period>
          <Period id="second" start="PT5M"><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="other" bandwidth="1000"><BaseURL>https://media.invalid/other.mp4</BaseURL></Representation></AdaptationSet></Period>
        </MPD>
    """.trimIndent()
    private fun parse(body: String)=GatewayDashManifestParser {now}.parse(uri,body.byteInputStream())
    private fun assertDirect(manifest: DashManifest) {
        assertTrue(manifest.dynamic)
        assertEquals("urn:mpeg:dash:utc:direct:2014",manifest.utcTiming?.schemeIdUri)
        assertEquals("2026-10-16T17:00:00.123Z",manifest.utcTiming?.value)
    }
    @Test fun absentLiveTimingCannotFallBackToDirectSntp() {
        val body=xml();val parsed=parse(body);val original=DashManifestParser().parse(uri,body.byteInputStream())
        assertNull(original.utcTiming);assertDirect(parsed)
        assertEquals(original.availabilityStartTimeMs,parsed.availabilityStartTimeMs)
        assertEquals(original.durationMs,parsed.durationMs);assertEquals(original.minBufferTimeMs,parsed.minBufferTimeMs)
        assertEquals(original.minUpdatePeriodMs,parsed.minUpdatePeriodMs);assertEquals(original.timeShiftBufferDepthMs,parsed.timeShiftBufferDepthMs)
        assertEquals(original.suggestedPresentationDelayMs,parsed.suggestedPresentationDelayMs);assertEquals(original.publishTimeMs,parsed.publishTimeMs)
        assertEquals(original.location,parsed.location)
        assertEquals(original.programInformation,parsed.programInformation)
        assertNotNull(parsed.serviceDescription)
        assertEquals(original.serviceDescription!!.targetOffsetMs,parsed.serviceDescription!!.targetOffsetMs)
        assertEquals(original.serviceDescription!!.minOffsetMs,parsed.serviceDescription!!.minOffsetMs)
        assertEquals(original.serviceDescription!!.maxOffsetMs,parsed.serviceDescription!!.maxOffsetMs)
        assertEquals(original.periodCount,parsed.periodCount)
        for(index in 0 until original.periodCount) {
            val before=original.getPeriod(index);val after=parsed.getPeriod(index)
            assertEquals(before.id,after.id);assertEquals(before.startMs,after.startMs)
            assertEquals(before.adaptationSets.size,after.adaptationSets.size)
            assertEquals(before.eventStreams.size,after.eventStreams.size)
            assertEquals(before.adaptationSets[0].representations[0].format.id,after.adaptationSets[0].representations[0].format.id)
        }
    }
    @Test fun explicitNtpTimingIsReplacedBeforeTheMediaSourceSeesIt() {
        assertDirect(parse(xml("""<UTCTiming schemeIdUri="urn:mpeg:dash:utc:ntp:2014" value="time.example.invalid"/>""")))
        assertDirect(parse(xml("""<UTCTiming schemeIdUri="urn:mpeg:dash:utc:ntp:2012" value="time.example.invalid"/>""")))
    }
    @Test fun httpAndDirectTimingAlsoUseTheDeviceClockWithoutAuxiliaryRequests() {
        assertDirect(parse(xml("""<UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-xsdate:2014" value="https://clock.example.invalid/now"/>""")))
        assertDirect(parse(xml("""<UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014" value="2001-01-01T00:00:00Z"/>""")))
    }
    @Test fun staticDashKeepsItsOriginalTimingAndMetadata() {
        val parsed=parse(xml("""<UTCTiming schemeIdUri="urn:mpeg:dash:utc:ntp:2014" value="unused.example.invalid"/>""",dynamic=false))
        assertFalse(parsed.dynamic)
        assertEquals("urn:mpeg:dash:utc:ntp:2014",parsed.utcTiming?.schemeIdUri)
        assertEquals("unused.example.invalid",parsed.utcTiming?.value)
        assertEquals(2,parsed.periodCount);assertEquals("Public fixture",parsed.programInformation?.title)
    }
    @Test fun factoryBuildsWithoutOpeningAnyDataSource() {
        var created=0
        val data=DataSource.Factory {created++;error("No source should be opened while creating a media source")}
        val item=MediaItem.Builder().setUri(uri).setMimeType("application/dash+xml").build()
        val source=gatewayDashMediaSource(data,item)
        assertEquals(0,created);assertEquals(item.mediaId,source.mediaItem.mediaId)
        assertEquals("application/dash+xml",source.mediaItem.localConfiguration?.mimeType)
    }
}
