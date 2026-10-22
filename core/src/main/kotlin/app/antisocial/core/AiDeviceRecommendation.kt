/* AntiSocial, GPL-3.0-or-later. Conservative admission guidance, not a speed benchmark. */
package app.antisocial.core

data class AiDeviceFacts(val abi: String,val totalMemoryBytes: Long,val availableMemoryBytes: Long,val availableStorageBytes: Long,val vulkanAdvertised: Boolean)
data class AiDeviceRecommendation(val profile: String?,val reason: String,val qualification: String="Hardware estimate · checked after download")

object AiDeviceRecommendations {
    private const val GIB=1024L*1024*1024
    fun recommend(device: AiDeviceFacts): AiDeviceRecommendation {
        if(device.abi !in setOf("arm64-v8a","x86_64"))return AiDeviceRecommendation(null,"Local text models need a supported 64-bit device. You can connect your own provider.")
        // Deliberately include reader/OS headroom and runtime scratch. Weight
        // download size is not a working-memory measurement or quality score.
        val candidates=listOf(
            Triple("quality",12*GIB,5*GIB),Triple("standard",6*GIB,5*GIB/2),Triple("compact",3*GIB,5*GIB/4))
        val candidate=candidates.firstOrNull {(_,total,free)->device.totalMemoryBytes>=total&&device.availableMemoryBytes>=free&&device.availableStorageBytes>=when(total){12*GIB->4*GIB;6*GIB->2*GIB;else->GIB}}
        return if(candidate==null)AiDeviceRecommendation(null,"Memory or storage is tight right now. Free some space or use your own provider.")
        else AiDeviceRecommendation(candidate.first,when(candidate.first){"quality"->"Enough memory headroom for the larger model. Speed still depends on your device.";"standard"->"A balanced starting point for this device's memory and storage.";else->"The smaller model leaves more memory for reading and other apps."})
    }
}
