/* anti social, GPL-3.0-or-later. Synthetic display names are not identity proofs. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class SourceAuthorLabelsTest {
    private val first=Post("710001",Platform.X,"https://x.com/workshopone/status/710001","Public studio","First report.",publishedAt=1_800_000_000_000)
    private val second=first.copy(id="710002",canonicalUrl="https://x.com/workshoptwo/status/710002",body="Another report.")
    private val third=first.copy(id="710003",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/710003",body="A separate report.")
    @Test fun oneDisplayNameRetainsEveryDistinctAccountAndOriginalWithoutEstablishingAConnection() {
        val posts=listOf(first,second,third);val before=posts.map {it.copy()};val label=SourceAuthorLabels.collect(posts).single()
        assertEquals("Public studio",label.label);assertEquals(posts,label.originals);assertEquals(posts,label.accounts)
        assertEquals(setOf(Platform.X,Platform.FACEBOOK),label.platforms.toSet())
        assertEquals(3,CrossPosts.group(posts,emptyList()).size);assertEquals(before,posts)
    }
    @Test fun repeatedPostsOfOneKnownAccountHaveOneProfileButKeepTheirIndividualEditions() {
        val repeat=first.copy(id="710004",canonicalUrl="https://x.com/workshopone/status/710004",author="Another supplied name")
        val label=SourceAuthorLabels.collect(listOf(first,repeat,first)).single()
        assertEquals(listOf(first,repeat),label.originals);assertEquals(listOf(first),label.accounts)
    }
    @Test fun unknownFacebookAliasesKeepBothDestinationsAndMissingAuthorsAreNeverAnIdentity() {
        val a=third.copy(canonicalUrl="https://www.facebook.com/reel/710003")
        val b=third.copy(id="710004",canonicalUrl="https://www.facebook.com/reel/710004")
        val label=SourceAuthorLabels.collect(listOf(a,b)).single()
        assertEquals(listOf(a,b),label.originals);assertEquals(2,label.accounts.size)
        assertEquals(2,SourceAuthorLabels.collect(listOf(a.copy(author=""),b.copy(author=""))).size)
    }
    @Test fun whitespaceCanCompressButCaseConfusablesAndDirectionalControlsRemainDistinct() {
        val names=listOf("Public studio","Public  studio","public studio","Public st\u0443dio","Public \u202estudio")
        val posts=names.mapIndexed {i,name->first.copy(id="${710010+i}",canonicalUrl="https://x.com/workshop$i/status/${710010+i}",author=name)}
        val labels=SourceAuthorLabels.collect(posts)
        assertEquals(4,labels.size);assertEquals(2,labels.first().originals.size)
        assertEquals(posts.toSet(),labels.flatMap {it.originals}.toSet())
    }
}
