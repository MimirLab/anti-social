/* AntiSocial, GPL-3.0-or-later. Optional work preferences are local and never imported with reader packs. */
package app.antisocial.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ContextOptions(val ahead: Boolean=false,val saved: Boolean=false,val lookahead: Int=3,val refreshMinutes: Int=60,
    val mediaPolicy: String="charging",val unmetered: Boolean=true,val minimumBattery: Int=20,val sliceSeconds: Int=120,val remoteContextTokens: Int=0,val commentLinks: Int=24,val processingStyle: String="auto")
internal class ContextPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("context-options",Context.MODE_PRIVATE)
    /** Local scheduling position only; neither source payloads nor credentials. */
    internal var savedMaintenanceCursor: String
        get()=prefs.getString("savedCursor","").orEmpty().takeIf {it.toByteArray(Charsets.UTF_8).size<=MAX_SAVED_CURSOR} ?: ""
        set(value) {require(value.toByteArray(Charsets.UTF_8).size<=MAX_SAVED_CURSOR);prefs.edit().putString("savedCursor",value).commit()}
    val state=MutableStateFlow(ContextOptions(prefs.getBoolean("ahead",false),prefs.getBoolean("saved",false),prefs.getInt("lookahead",3).coerceIn(1,10),
        prefs.getInt("refresh",60).coerceIn(15,1440),prefs.getString("media","charging").orEmpty().takeIf {it in setOf("manual","charging","browsing")} ?: "charging",
        prefs.getBoolean("unmetered",true),prefs.getInt("battery",20).coerceIn(10,80),prefs.getInt("slice",120).coerceIn(30,600),prefs.getInt("window",0).takeIf {it==0||it in 1024..1_048_576} ?: 0,prefs.getInt("commentLinks",24).coerceIn(0,128),prefs.getString("processingStyle","auto").orEmpty().takeIf {it in setOf("auto","focused","balanced","extended")} ?: "auto"))
    fun update(value: ContextOptions) {
        require(value.lookahead in 1..10&&value.refreshMinutes in 15..1440&&value.minimumBattery in 10..80&&value.sliceSeconds in 30..600&&value.mediaPolicy in setOf("manual","charging","browsing"))
        require(value.remoteContextTokens==0||value.remoteContextTokens in 1024..1_048_576)
        require(value.commentLinks in 0..128)
        require(value.processingStyle in setOf("auto","focused","balanced","extended"))
        prefs.edit().putBoolean("ahead",value.ahead).putBoolean("saved",value.saved).putInt("lookahead",value.lookahead).putInt("refresh",value.refreshMinutes)
            .putString("media",value.mediaPolicy).putBoolean("unmetered",value.unmetered).putInt("battery",value.minimumBattery).putInt("slice",value.sliceSeconds).putInt("window",value.remoteContextTokens).putInt("commentLinks",value.commentLinks).putString("processingStyle",value.processingStyle).commit()
        state.value=value
    }
    companion object {
        // LocalLibrary permits 16K-character URLs. Cover their UTF-8 form plus
        // the platform prefix, and use the same byte bound in indexed key scans.
        const val MAX_SAVED_CURSOR=4*16*1024+128
    }
}
