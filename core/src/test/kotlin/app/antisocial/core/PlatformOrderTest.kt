package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class PlatformOrderTest {
    @Test fun displayPolicyCoversFeedSourcesWithoutChangingStoredEnumOrder() {
        assertEquals(ReaderScope.platforms.toSet(),PlatformOrder.popular.toSet())
        assertEquals(ReaderScope.platforms.size,PlatformOrder.popular.size)
        assertEquals(Platform.X,Platform.entries.first())
        assertEquals("twitter",Platform.X.id)
        assertEquals(Platform.FACEBOOK,PlatformOrder.popular.first())
    }
    @Test fun defaultsKeepLinkedVideoOutOfSocialTabs() {
        assertEquals(listOf(Platform.INSTAGRAM,Platform.TIKTOK,Platform.X,Platform.REDDIT),PlatformOrder.defaultSelected)
    }
    @Test fun explicitSortingRetainsOnlySelectedChoicesAndRemovesDuplicates() {
        val selected=listOf(Platform.PIXELFED,Platform.REDDIT,Platform.FACEBOOK,Platform.REDDIT)
        assertEquals(listOf(Platform.FACEBOOK,Platform.REDDIT,Platform.PIXELFED),PlatformOrder.sort(selected))
        assertEquals(listOf(Platform.PIXELFED,Platform.REDDIT,Platform.FACEBOOK,Platform.REDDIT),selected)
        assertTrue(PlatformOrder.sort(emptyList()).isEmpty())
    }
}
