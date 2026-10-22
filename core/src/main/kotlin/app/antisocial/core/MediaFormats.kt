package app.antisocial.core

/** Container signatures distinguish media from a successful HTML error page. */
object MediaFormats {
    fun type(bytes: ByteArray): String? {
        fun prefix(vararg values: Int)=bytes.size>=values.size&&values.indices.all {bytes[it].toInt() and 255==values[it]}
        val head=bytes.take(96).toByteArray().toString(Charsets.ISO_8859_1)
        return when {
            prefix(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)||prefix(0xff,0xd8,0xff)||head.startsWith("GIF87a")||head.startsWith("GIF89a")||head.startsWith("RIFF")&&head.drop(8).startsWith("WEBP") -> "image"
            head.drop(4).startsWith("ftyp")&&(head.contains("avif")||head.contains("avis")||head.contains("heic")) -> "image"
            head.drop(4).startsWith("ftyp")||prefix(0x1a,0x45,0xdf,0xa3)||head.trimStart('\uFEFF',' ','\n','\r').startsWith("#EXTM3U") -> "video"
            else -> null
        }
    }
}
