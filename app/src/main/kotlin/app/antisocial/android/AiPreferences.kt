/* AntiSocial, GPL-3.0-or-later. Optional inference settings are never part of reader exports. */
package app.antisocial.android

import app.antisocial.core.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiLocation { DEVICE, SERVER, CLOUD }
data class AiProviderSettings(val enabled: Boolean=false,val location: AiLocation=AiLocation.CLOUD,
    val baseUrl: String="",val model: String="",val hasKey: Boolean=false,
    val id: String="custom",val preset: AiProviderPreset=AiProviderPreset.CUSTOM,val label: String="",
    val protocol: AiProtocol=AiProtocol.COMPATIBLE,val workspaceId: String="") {
    val displayName: String get()=label.ifBlank {if(location==AiLocation.SERVER)"Your server"else preset.label}
}
internal fun aiCredentialSlot(kind: String,id: String): String {
    require(kind in setOf("text","embedding")&&id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
    // Preserve the original custom endpoint's encrypted file and AAD in place.
    return if(id=="custom")kind else "$kind-$id"
}

/** Deliberately separate from Preferences: backup/pack import must not enable
 * inference, change a private server, or disclose a user's API credentials. */
class AiPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("ai-settings",Context.MODE_PRIVATE)
    private val gson=Gson()
    var enabled: Boolean get()=prefs.getBoolean("enabled",false);set(value){prefs.edit().putBoolean("enabled",value).commit()}
    var textProfile: String get()=prefs.getString("textProfile","standard").orEmpty();set(value){require(value in setOf("compact","standard","quality"));prefs.edit().putString("textProfile",value).commit()}
    val hasTextProfile: Boolean get()=prefs.contains("textProfile")
    fun localChosen(kind: String): Boolean=prefs.getBoolean("local:$kind",false)
    fun localChosen(kind: String,value: Boolean) {require(kind in setOf("text","embedding"));prefs.edit().putBoolean("local:$kind",value).commit()}
    private fun checkKind(kind: String){require(kind in setOf("text","embedding"))}
    fun providers(kind: String): List<AiProviderSettings> {
        checkKind(kind)
        val stored=prefs.getString("providers:$kind",null)
        if(stored!=null)return runCatching {gson.fromJson(stored,Array<AiProviderSettings>::class.java).toList().also {list->
            require(list.size<=32&&list.map {it.id}.distinct().size==list.size)
            list.forEach {aiCredentialSlot(kind,it.id);require(it.baseUrl.isNotBlank())}
        }}.getOrDefault(emptyList())
        // v0.32 single-provider configuration migrates only on an explicit edit.
        return runCatching {gson.fromJson(prefs.getString(kind,null),AiProviderSettings::class.java)}.getOrNull()?.takeIf {it.baseUrl.isNotBlank()}?.let {listOf(it)} ?: emptyList()
    }
    fun selectedId(kind: String): String? {checkKind(kind);return prefs.getString("selected:$kind",null) ?: if(!prefs.contains("providers:$kind"))providers(kind).firstOrNull()?.id else null}
    fun provider(kind: String): AiProviderSettings=providers(kind).firstOrNull {it.id==selectedId(kind)} ?: AiProviderSettings()
    fun provider(kind: String,id: String): AiProviderSettings?=providers(kind).firstOrNull {it.id==id}
    fun provider(kind: String,settings: AiProviderSettings) {
        aiCredentialSlot(kind,settings.id)
        val list=providers(kind).filterNot {it.id==settings.id}+settings
        require(list.size<=32){"You can save up to 32 providers per model type."}
        val editor=prefs.edit().putString("providers:$kind",gson.toJson(list)).remove(kind)
        if(settings.enabled&&selectedId(kind)==null)editor.putString("selected:$kind",settings.id)
        else if(!prefs.contains("providers:$kind"))selectedId(kind)?.let {editor.putString("selected:$kind",it)}
        editor.commit()
    }
    fun selectProvider(kind: String,id: String) {require(provider(kind,id)?.enabled==true){"Enable this provider before selecting it."};prefs.edit().putString("selected:$kind",id).commit()}
    fun removeProvider(kind: String,id: String) {
        aiCredentialSlot(kind,id)
        val editor=prefs.edit().putString("providers:$kind",gson.toJson(providers(kind).filterNot {it.id==id})).remove(kind)
        if(selectedId(kind)==id)editor.remove("selected:$kind") // Never silently select another paid service.
        editor.commit()
    }
    fun routing(kind: String): AiRouting {checkKind(kind);return runCatching {AiRouting.valueOf(prefs.getString("routing:$kind",null).orEmpty())}.getOrDefault(AiRouting.LOCAL_FIRST)}
    fun routing(kind: String,value: AiRouting) {checkKind(kind);prefs.edit().putString("routing:$kind",value.name).commit()}

}

/** AES/GCM keys remain in Android Keystore. Only encrypted credential bytes are
 * persisted in noBackupFilesDir; neither reader exports nor source ZIPs see them. */
internal class AiCredentialVault(context: Context) {
    private val directory=File(context.noBackupFilesDir,"ai-credentials").apply {mkdirs()}
    private val alias="antisocial-ai-provider-v1"
    private val aad="anti social optional AI credentials v1".toByteArray()
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        return store.getKey(alias,null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(kind: String): AtomicFile {require(kind.matches(Regex("(?:text|embedding)(?:-[a-z0-9][a-z0-9-]{0,63})?")));return AtomicFile(File(directory,kind))}
    @Synchronized fun read(kind: String): String? {
        val storage=file(kind)
        if(!storage.baseFile.exists())return null
        val bytes=storage.readFully();require(bytes.size in 30..16384&&bytes[0]==1.toByte()){"Stored provider credential is damaged. Replace it in AI settings."}
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)));updateAAD(aad+kind.toByteArray());String(doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8)
        }
    }
    @Synchronized fun write(kind: String,value: String?) {
        val storage=file(kind)
        if(value.isNullOrEmpty()){storage.delete();return}
        require(value.length<=8192&&value.all {it.code in 33..126}){"The API key contains unsupported characters."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key());updateAAD(aad+kind.toByteArray())}
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream=storage.startWrite()
        try {stream.write(bytes);storage.finishWrite(stream)}catch(e: Exception){storage.failWrite(stream);throw e}
    }
}
