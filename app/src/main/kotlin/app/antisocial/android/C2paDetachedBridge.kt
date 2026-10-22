/* anti social, GPL-3.0-or-later. SDK 0.0.14 ABI adapter; no network resolver here. */
package app.antisocial.android

import org.contentauth.c2pa.C2PAContext
import org.contentauth.c2pa.Stream
import org.contentauth.c2pa.Reader

internal object C2paDetachedBridge {
    init {System.loadLibrary("antisocial_c2pa_bridge")}
    @JvmStatic external fun create(context:C2PAContext,stream:Stream,mime:String,manifest:ByteArray):Reader?
}
