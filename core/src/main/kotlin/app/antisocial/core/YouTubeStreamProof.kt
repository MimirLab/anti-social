/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

/** Reject login/error HTML before handing an alleged stream to the player. */
object YouTubeStreamProof {
    fun recognized(bytes: ByteArray,audio: Boolean=false): Boolean {
        fun ascii(offset: Int,value: String)=bytes.size>=offset+value.length&&value.indices.all {bytes[offset+it].toInt() and 255==value[it].code}
        val iso=listOf("ftyp","styp","moov","moof","mdat").any {ascii(4,it)}
        val webm=bytes.size>=4&&bytes.take(4).map {it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3)
        return iso||webm||VideoFormats.sniff(bytes)!=null||audio&&(ascii(0,"ID3")||ascii(0,"OggS")||bytes.size>1&&bytes[0].toInt() and 255==255&&bytes[1].toInt() and 224==224)
    }
    suspend fun check(media: Media,sourceUrl: String,gateway: NetworkGateway) {
        require(recognized(gateway.sample(media.url,sourceUrl,limit=4096))){"This source returned a page instead of playable video"}
        media.audioUrl?.let {require(recognized(gateway.sample(it,sourceUrl,limit=4096),audio=true)){"This source returned unavailable audio"}}
    }
}
