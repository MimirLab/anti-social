/* anti social, GPL-3.0-or-later. Asset identity is not file authentication. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Qualified Meta CDN object names survive regional hosts and expiring tokens.
 * Never shares cryptographic validation or cached bytes between these URLs. */
object ImageAssetIdentity {
    private val path=Regex("/v/t[0-9.]{1,24}-[0-9]{1,4}/[0-9]{6,24}_[0-9]{6,24}_[0-9]{6,24}_[no]\\.(?:jpg|jpeg|png|webp)")
    private val resize=Regex("dst-(?:jpg|webp)_(?:e[0-9]{1,3}_)?[sp][0-9]{1,5}x[0-9]{1,5}(?:_tt[0-9]{1,3})?")
    private val parameters=setOf("stp","ccb","oh","oe","ig_cache_key","efg")
    fun key(value:String):String? {
        if(value.length>8192||value.any(Char::isISOControl)||!value.contains("fbcdn.net/",true)&&!value.contains("cdninstagram.com/",true))return null
        return CrossPostFusion.imageAssetKey(value) {
            val url=value.toHttpUrlOrNull() ?: return@imageAssetKey null
            if(!url.isHttps||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.fragment!=null||
                !(url.host.endsWith(".fbcdn.net")||url.host.endsWith(".cdninstagram.com"))||!path.matches(url.encodedPath)||
                url.queryParameterNames.any {it !in parameters&&!it.startsWith("_nc_")})return@imageAssetKey null
            val transforms=url.queryParameterValues("stp")
            if(transforms.size>1||transforms.any {it==null||!resize.matches(it)})return@imageAssetKey null
            "meta-image:"+url.encodedPath
        }
    }
    fun same(first:String,second:String)=key(first)?.let {it==key(second)}==true
    /** A cheap object-name match yields to contrary observations of the pixels. */
    fun same(first:Media,second:Media)=first.type=="image"&&second.type=="image"&&same(first.url,second.url)&&
        (first.visualFingerprint==null||second.visualFingerprint==null||VisualFrames.sameImage(first.visualFingerprint,second.visualFingerprint))
}
