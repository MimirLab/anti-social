/* anti social, GPL-3.0-or-later. The signed APK pins every installation byte. */
package app.antisocial.android

import android.content.Context
import android.os.Build
import app.antisocial.core.*
import java.io.File

internal object LocalTextRuntime {
    fun pack(context: Context): NativeRuntimePack?=NativeRuntimePack.read(
        context.assets.open("native-runtime/catalog.json").use {it.readBytes()},Build.SUPPORTED_ABIS.first())
    fun installer(context: Context,gateway: NetworkGateway?=null): NativeRuntimeInstaller=NativeRuntimeInstaller(
        File(context.noBackupFilesDir,"local-text-runtime"),requireNotNull(pack(context)){"Local text is unavailable for this architecture."},
        {context.assets.open("native-runtime/${pack(context)!!.payload}")},gateway)
}
