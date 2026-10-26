/* anti social, GPL-3.0-or-later. Private runtime inputs for opt-in source checks. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class LocalLiveBusinessCase(val query:String,val uniqueName:String?,val uniqueAddress:String?,val excludedAccount:String?,val groupedName:String?)
internal data class LocalLiveEventEdition(val url:String,val title:String)

/** Source-specific locations/businesses are supplied privately by the operator.
 * These test arguments are never defaults or production application settings. */
internal object LocalLiveInput {
    private val args get()=InstrumentationRegistry.getArguments()
    fun text(key:String,max:Int=2048):String {
        require(args.getString("antisocialIsolatedFixtureDevice")=="true") {"A separate fixture device is required"}
        return requireNotNull(args.getString(key)) {"Supply $key privately at execution"}.also {require(it.isNotBlank()&&it.length<=max)}
    }
    private fun field(value:JsonObject,key:String):String?=value[key]?.takeUnless {it.isJsonNull}?.let {v->
        require(v.isJsonPrimitive&&v.asJsonPrimitive.isString){"Invalid fixture field $key"}
        v.asString.also {require(it.length<=2048)}.takeIf(String::isNotBlank)
    }
    private fun list(value:JsonObject,key:String):List<String> = value[key]?.takeUnless {it.isJsonNull}?.let {v->
        require(v.isJsonArray&&v.asJsonArray.size()<=8)
        v.asJsonArray.map {item->require(item.isJsonPrimitive&&item.asJsonPrimitive.isString);item.asString.also {require(it.length<=2048)}}
    }.orEmpty()
    fun texts(key:String):List<String> {
        val value=JsonParser.parseString(text(key,16*1024));require(value.isJsonArray&&value.asJsonArray.size() in 1..8)
        return value.asJsonArray.map {v->require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);v.asString.also {require(it.isNotBlank()&&it.length<=2048)}}
    }
    fun area():LocalArea=Gson().fromJson(text("antisocialLiveArea",16*1024),LocalArea::class.java).also {require(it.valid())}
    fun business(key:String):LocalBusiness {
        val value=JsonParser.parseString(text(key,32*1024));require(value.isJsonObject)
        val o=value.asJsonObject
        val point=o["point"]?.takeUnless {it.isJsonNull}?.let {Gson().fromJson(it,LocalPoint::class.java).also {p->require(p.valid())}}
        return LocalBusiness(field(o,"id") ?: "private-live-business",requireNotNull(field(o,"name")),
            category=field(o,"category").orEmpty(),address=field(o,"address").orEmpty(),locality=field(o,"locality").orEmpty(),point=point,
            websites=list(o,"websites").onEach {require(LocalDiscoveryIdentity.url(it)!=null)},
            socials=list(o,"socials"),phones=list(o,"phones"),sourceIds=list(o,"sourceIds"))
    }
    fun businessCases():List<LocalLiveBusinessCase> {
        val value=JsonParser.parseString(text("antisocialBusinessCases",32*1024));require(value.isJsonArray&&value.asJsonArray.size() in 1..8)
        return value.asJsonArray.map {v->val o=v.asJsonObject
            LocalLiveBusinessCase(requireNotNull(field(o,"query")).also {require(it.length<=256)},field(o,"uniqueName"),field(o,"uniqueAddress"),field(o,"excludedAccount"),field(o,"groupedName"))}
    }
    fun eventEditions():List<LocalLiveEventEdition> {
        val value=JsonParser.parseString(text("antisocialEventEditions",16*1024));require(value.isJsonArray&&value.asJsonArray.size() in 1..4)
        return value.asJsonArray.map {v->val o=v.asJsonObject
            LocalLiveEventEdition(requireNotNull(field(o,"url")).also {require(LocalDiscoveryIdentity.url(it)!=null)},requireNotNull(field(o,"title")))}
    }
}
