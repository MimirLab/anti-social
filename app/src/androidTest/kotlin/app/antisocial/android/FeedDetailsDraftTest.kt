/* anti social, GPL-3.0-or-later. Card-scoped admission for explicit details. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Assert.*
import org.junit.Test

class FeedDetailsDraftTest {
    private val first=Post("71000001",Platform.X,"https://x.com/publicstudio/status/71000001","publicstudio","Shared workshop announcement.")
    private val peer=first.copy(id="72000001",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/72000001",body=first.body+" Bring safety glasses.")
    private val other=first.copy(id="71000002",canonicalUrl="https://x.com/publicstudio/status/71000002",body="A separate announcement.")
    private val row=FrozenFeedRow(first.identityKey,listOf(first.identityKey,peer.identityKey),listOf(first,peer),listOf(first))
    private val groups=row.aliases.associateWith {listOf(first,peer)}
    private val draft=FeedDetailsDraft(row,listOf(first,peer),groups,row.copy(bodyPosts=listOf(first,peer)))
    @Test fun unrelatedSourceAndRowUpdatesDoNotInvalidateTheReadersDetailsAction() {
        val otherRow=FrozenFeedRow(other.identityKey,listOf(other.identityKey),listOf(other))
        assertTrue(draft.current(listOf(otherRow,row),listOf(first,peer,other),groups+(other.identityKey to listOf(other)),true))
        assertEquals(listOf(first,peer),draft.replacement.bodyPosts)
        assertEquals(row.key,draft.replacement.key);assertEquals(row.orderAt,draft.replacement.orderAt)
    }
    @Test fun changedRelevantFactsMembershipOrFrozenBodyRequireAFreshDraft() {
        val changed=peer.copy(body=peer.body+" Registration closes Friday.")
        assertFalse(draft.current(listOf(row),listOf(first,changed),groups,true))
        assertFalse(draft.current(listOf(row),listOf(first,peer),groups+(first.identityKey to listOf(first,peer,other)),true))
        assertFalse(draft.current(listOf(row.copy(bodyPosts=listOf(first,peer))),listOf(first,peer),groups,true))
        assertFalse(draft.current(listOf(row),listOf(first),groups,true))
        assertFalse(draft.current(emptyList(),listOf(first,peer),groups,true))
        assertFalse(draft.current(listOf(row),listOf(first,peer),groups,false))
    }
}
