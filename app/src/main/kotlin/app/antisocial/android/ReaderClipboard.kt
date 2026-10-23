/* anti social, GPL-3.0-or-later. Copies keep their original value and paste behavior. */
package app.antisocial.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ReaderClipboard {
    private val secretKeys=setOf("token","access_token","api_key","apikey","key","auth","authkey","signature","sig","x-amz-signature","x-goog-signature","code","ticket")
    fun sensitiveLink(value:String):Boolean=value.toHttpUrlOrNull()?.let {url->
        url.username.isNotEmpty()||url.password.isNotEmpty()||url.queryParameterNames.any {it.lowercase(java.util.Locale.ROOT) in secretKeys}
    } ?: false
    fun clip(label:String,value:String,sensitive:Boolean=sensitiveLink(value))=ClipData.newPlainText(label,value).apply {
        if(sensitive)description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
    }
    fun copy(context:Context,label:String,value:String,sensitive:Boolean=sensitiveLink(value)) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip(label,value,sensitive))
    }
}
