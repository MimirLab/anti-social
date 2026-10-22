package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PublicContextNotesTest {
    @Test fun savedAnnotationsAreBoundToTheirPostAndCannotIntroduceExecutableOrCredentialedLinks() {
        val post=Post("123",Platform.X,"https://x.com/person/status/123","Person","Post")
        val note=PublicContextNote("The report adds a corrected table.",post.canonicalUrl,listOf("https://agency.gov/report"))
        assertTrue(PublicContextNotes.valid(post.copy(contextNotes=listOf(note))))
        assertFalse(PublicContextNotes.valid(post.copy(contextNotes=listOf(note.copy(sourceUrl="https://x.com/person/status/456")))))
        assertFalse(PublicContextNotes.valid(post.copy(contextNotes=listOf(note.copy(links=listOf("javascript:alert(1)"))))))
        assertFalse(PublicContextNotes.valid(post.copy(contextNotes=listOf(note.copy(links=listOf("https://user:password@agency.gov/report"))))))
        assertFalse(PublicContextNotes.valid(post.copy(contextNotes=listOf(note.copy(text="x".repeat(12_001))))))
    }
    @Test fun providedNotesSupplyEvidenceLinksWithoutInventingTheirRating() {
        val node=JsonParser.parseString("""{"community_note":{"text":"The full report supplies an additional table.","facets":[{"type":"url","replacement":"https://agency.gov/report"},{"type":"url","replacement":"javascript:alert(1)"}]}}""").asJsonObject
        val note=PublicContextNotes.fx(node,Platform.X,"https://x.com/person/status/123")!!.single()
        assertNull(note.status);assertEquals(listOf("https://agency.gov/report"),note.links)
        assertNull(PublicContextNotes.fx(node,Platform.BLUESKY,"https://bsky.app/profile/person/post/id"))
    }
    @Test fun emptyAnnotationsAreNotSignalAndOfflineSnapshotsPreserveAvailableNotes() {
        assertNull(PublicContextNotes.fx(JsonParser.parseString("""{"community_note":{"text":""}}""").asJsonObject,Platform.X,"https://x.com/person/status/1"))
        val post=Post("1",Platform.X,"https://x.com/person/status/1","Author","Body",contextNotes=listOf(PublicContextNote("Public annotation","https://x.com/person/status/1")))
        assertEquals(post.contextNotes,PostSnapshot.from(post).asPost(post.copy(contextNotes=null)).contextNotes)
        assertNull(PostSnapshot(body="Earlier edition").asPost(post).contextNotes)
    }
}
