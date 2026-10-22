package app.antisocial.core
import org.junit.Test
import org.junit.Assert.*
class AiDeviceRecommendationTest {
    private val gib=1024L*1024*1024
    @Test fun memoryHeadroomSelectsAllThreeChoicesWithoutGuessingModelQuality() {
        fun choice(total: Long,free: Long)=AiDeviceRecommendations.recommend(AiDeviceFacts("arm64-v8a",total*gib,free*gib,10*gib,true)).profile
        assertEquals("compact",choice(4,2));assertEquals("standard",choice(8,4));assertEquals("quality",choice(16,8))
    }
    @Test fun transientPressureAndUnsupportedAbisNeverReceiveAFalseRecommendation() {
        assertNull(AiDeviceRecommendations.recommend(AiDeviceFacts("armeabi-v7a",16*gib,8*gib,20*gib,true)).profile)
        assertNull(AiDeviceRecommendations.recommend(AiDeviceFacts("arm64-v8a",16*gib,gib,20*gib,true)).profile)
        assertNull(AiDeviceRecommendations.recommend(AiDeviceFacts("arm64-v8a",16*gib,8*gib,gib/2,true)).profile)
    }
    @Test fun advertisedVulkanAloneIsNotAQualificationOrARamSubstitute() {
        val facts=AiDeviceFacts("arm64-v8a",8*gib,4*gib,8*gib,false)
        assertEquals(AiDeviceRecommendations.recommend(facts),AiDeviceRecommendations.recommend(facts.copy(vulkanAdvertised=true)))
    }
}
