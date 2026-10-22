/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PostBodyDetailsTest {
    private val prose="The workshop published a complete guide to replacing a laptop battery safely, including the tools and every repair step."
    @Test fun detachedTrailingReferencesFoldWithoutChangingAnySourceCharacter() {
        val tail="@repair @workshop #BatteryCare\nhttps://example.org/Guide?Mode=Full#Tools  "
        val original="$prose\n\n$tail"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals(tail,split.details);assertEquals(4,split.referenceCount)
        assertEquals(original,split.originalText())
        val post=Post("fixture",Platform.X,"https://x.com/workshop/status/123","Workshop",original)
        val before=Gson().toJson(post)
        PostBodyDetails.split(PostPresentation.text(post).body)
        assertEquals(before,Gson().toJson(post));assertEquals(original,PostPresentation.content(post))
    }
    @Test fun anInlinePileNeedsACompletedSentenceAndEnoughSubstantiveText() {
        val original="$prose @repair @workshop #BatteryCare"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals("@repair @workshop #BatteryCare",split.details)
        assertEquals(original,split.originalText())
        val credit="$prose\nThanks to @repair @workshop @volunteer"
        assertEquals(credit,PostBodyDetails.split(credit).body)
        assertFalse(PostBodyDetails.split(credit).hasDetails)
    }
    @Test fun meaningfulClosingProseAndInContextReferencesRemainVisible() {
        val originals=listOf(
            "$prose\n@repair @workshop #BatteryCare\nKeep the disconnected battery away from sharp tools.",
            "$prose Contact @repair and @workshop before using #BatteryCare materials.",
            "$prose\nRead the guide at https://example.org/guide",
            "$prose\nThanks to @repair and @workshop",
            "$prose\nContact support@example.org for a repair.",
        )
        for(original in originals){assertEquals(original,PostBodyDetails.split(original).body);assertFalse(PostBodyDetails.split(original).hasDetails)}
    }
    @Test fun aCreditSentenceStaysAboveAFollowingDetachedPile() {
        val lead="$prose\nThanks to @repair for checking the instructions."
        val tail="@workshop @volunteer #RightToRepair"
        val original="$lead\n$tail"
        val split=PostBodyDetails.split(original)
        assertEquals(lead,split.body);assertEquals(tail,split.details);assertEquals(original,split.originalText())
    }
    @Test fun shortPostsStayVisibleAndStandaloneFusionTailsNeedExplicitOptIn() {
        for(original in listOf("@repair @workshop #BatteryCare","Fixed it today.\n@repair @workshop #BatteryCare")) {
            assertFalse(PostBodyDetails.split(original).hasDetails);assertEquals(original,PostBodyDetails.split(original).body)
        }
        val tail="\n@repair @workshop #BatteryCare\n"
        val standalone=PostBodyDetails.split(tail,allowStandalone=true)
        assertEquals("",standalone.body);assertTrue(standalone.hasDetails);assertEquals(tail,standalone.originalText())
        assertFalse(PostBodyDetails.split("Fixed it today.\n@repair @workshop #BatteryCare",allowStandalone=true).hasDetails)
    }
    @Test fun unicodeFederatedNamesAndBareLinksKeepTheirExactSpelling() {
        val tail="@修理屋 @repair@social.example.org · #修理\nExample.org/Guides/Battery?Mode=Full"
        val original="$prose\n\n$tail"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals(tail,split.details);assertEquals(4,split.referenceCount)
        assertEquals(original,split.originalText())
    }
    @Test fun anExplicitReferenceListLabelMovesWithItsOwnTrailingList() {
        val tail="Tags: #Repair #Battery #RightToRepair"
        val original="$prose\n$tail"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals(tail,split.details);assertEquals(original,split.originalText())
    }
    @Test fun oneOrTwoDetachedReferencesAreStillAnInventory() {
        for(tail in listOf("https://example.org/guide","Example.org/Guides/Battery?Mode=Full","@repair @workshop","#BatteryCare")) {
            val original="$prose\n\n$tail  "
            val split=PostBodyDetails.split(original)
            assertEquals(prose,split.body);assertEquals("$tail  ",split.details)
            assertEquals(if(tail=="@repair @workshop")2 else 1,split.referenceCount)
            assertEquals(original,split.originalText())
        }
    }
    @Test fun plainLinkCallsToActionFoldWithTheirDestinationAndKeepSurroundingProse() {
        for(tail in listOf("Read more ⬇️ https://example.org/releases/2026/guide","Learn more: https://example.org/guide","Source: Example.org/Guides/Battery","Video — https://x.com/workshop/status/123#m")) {
            for(separator in listOf("\n\n"," ")) {
                val original=prose+separator+tail
                val split=PostBodyDetails.split(original)
                assertEquals(prose,split.body);assertEquals(tail,split.details)
                assertEquals(1,split.referenceCount);assertEquals(original,split.originalText())
            }
        }
    }
    @Test fun aSingleInlineDestinationOnlyFoldsAfterACompletedSubstantivePassage() {
        val original="$prose https://example.org/guide"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals("https://example.org/guide",split.details)
        assertEquals(original,split.originalText())
        for(visible in listOf("Download the repair guide at https://example.org/guide","$prose\nThe source is https://example.org/guide","$prose\nDon't read more: https://example.org/guide","$prose\nOur recommended video — https://example.org/guide")) {
            assertEquals(visible,PostBodyDetails.split(visible).body);assertFalse(PostBodyDetails.split(visible).hasDetails)
        }
    }
    @Test fun detachedTopAndBottomListsShareOneInventoryWithExactReassembly() {
        val top="Links:\r\nhttps://example.org/Guide?Mode=Full\r\n@workshop"
        val tail="Source: https://example.org/repair  "
        val original="$top\r\n\r\n$prose\n\n$tail"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals(top+"\n\n"+tail,split.details)
        assertEquals(top,split.leadingDetails);assertEquals("\r\n\r\n",split.leadingSeparator)
        assertEquals(tail,split.trailingDetails);assertEquals(3,split.referenceCount)
        assertEquals(original,split.originalText())
    }
    @Test fun aLinkOnlyHeadingCanMoveBelowTheMessageButAnOpeningSentenceCannot() {
        val heading="Read more ⬇️ https://example.org/releases/2026/guide"
        val original="$heading\n\n$prose"
        val split=PostBodyDetails.split(original)
        assertEquals(prose,split.body);assertEquals(heading,split.details);assertEquals("",split.trailingDetails)
        assertEquals(original,split.originalText())
        for(visible in listOf("@repair explains every step in this guide.\n$prose","Our report at https://example.org/guide\n$prose","https://example.org/guide\nFixed it today.","Thanks to @repair and @workshop\n$prose")) {
            val kept=PostBodyDetails.split(visible)
            assertEquals(visible,kept.body);assertFalse(kept.hasDetails);assertEquals(visible,kept.originalText())
        }
    }
    @Test fun boundedPresentationDoesNotScanUnboundedPostsOrReferenceLists() {
        val huge=prose.repeat(600)+"\n@one @two @three"
        val crowded=prose+"\n"+(1..257).joinToString(" "){"@reader$it"}
        for(original in listOf(huge,crowded)){assertFalse(PostBodyDetails.split(original).hasDetails);assertEquals(original,PostBodyDetails.split(original).body)}
    }
}
