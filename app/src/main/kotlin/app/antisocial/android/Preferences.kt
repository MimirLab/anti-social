package app.antisocial.android

import android.content.Context
import app.antisocial.core.Platform
import app.antisocial.core.PlatformOrder
import app.antisocial.core.ReaderScope
import app.antisocial.core.SearchProvider
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class Preferences(private val context: Context) {
    private val prefs=context.getSharedPreferences("antisocial",Context.MODE_PRIVATE)
    internal var localAreas:String get()=prefs.getString("localAreas","[]").orEmpty();set(value){app.antisocial.core.LocalAreaCodec.decode(value);prefs.edit().putString("localAreas",value).apply()}
    internal var localArea:String get()=prefs.getString("localArea","").orEmpty();set(value){require(value.length<=256&&!value.any(Char::isISOControl));prefs.edit().putString("localArea",value).apply()}
    internal var localFlyerReading:Boolean get()=prefs.getBoolean("localFlyerReading",true);set(value){prefs.edit().putBoolean("localFlyerReading",value).apply()}
    internal var localDistanceUnit:app.antisocial.core.LocalDistanceUnit
        get()=app.antisocial.core.LocalDistanceUnit.fromId(prefs.getString("localDistanceUnit","").orEmpty()) ?: if(context.resources.configuration.locales[0].country=="US")app.antisocial.core.LocalDistanceUnit.MILES else app.antisocial.core.LocalDistanceUnit.KILOMETRES
        set(value){prefs.edit().putString("localDistanceUnit",value.id).apply()}
    internal fun observeLocalAreas(onChange:()->Unit):()->Unit {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==null||key=="localAreas"||key=="localArea"||key=="localDistanceUnit")onChange()}
        prefs.registerOnSharedPreferenceChangeListener(listener);return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    var tor: Boolean get()=prefs.getBoolean("tor",false);set(v){prefs.edit().putBoolean("tor",v).apply()}
    var communityComments: Boolean get()=prefs.getBoolean("communityComments",true);set(v){prefs.edit().putBoolean("communityComments",v).apply()}
    var archiveChecks: Boolean get()=prefs.getBoolean("archiveChecks",true);set(v){prefs.edit().putBoolean("archiveChecks",v).apply()}
    var archiveRecovery: Boolean get()=prefs.getBoolean("archiveRecovery",true);set(v){prefs.edit().putBoolean("archiveRecovery",v).apply()}
    var originalFallback: Boolean get()=prefs.getBoolean("originalFallback",true);set(v){prefs.edit().putBoolean("originalFallback",v).apply()}
    var searchProvider: SearchProvider get()=SearchProvider.entries.firstOrNull {it.id==prefs.getString("searchProvider","duckduckgo")} ?: SearchProvider.DUCKDUCKGO;set(v){prefs.edit().putString("searchProvider",v.id).apply()}
    var intervalMinutes: Int get()=prefs.getInt("interval",60);set(v){prefs.edit().putInt("interval",v.coerceAtLeast(0)).apply()}
    var wifiOnly: Boolean get()=prefs.getBoolean("wifiOnly",false);set(v){prefs.edit().putBoolean("wifiOnly",v).apply()}
    var cacheMb: Int get()=prefs.getInt("cacheMb",500);set(v){prefs.edit().putInt("cacheMb",v.coerceIn(50,5000)).apply()}
    var videoCacheMb: Int get()=prefs.getInt("videoCacheMb",250);set(v){prefs.edit().putInt("videoCacheMb",v.coerceIn(0,5000)).apply()}
    var videoCacheDays: Int get()=prefs.getInt("videoCacheDays",7);set(v){prefs.edit().putInt("videoCacheDays",v.coerceIn(1,365)).apply()}
    var videoDownloadLimitMb: Int get()=prefs.getInt("videoDownloadLimitMb",2048);set(v){prefs.edit().putInt("videoDownloadLimitMb",v.coerceIn(0,100_000)).apply()}
    var videoDownloadWifiOnly: Boolean get()=prefs.getBoolean("videoDownloadWifiOnly",true);set(v){prefs.edit().putBoolean("videoDownloadWifiOnly",v).apply()}
    var sponsorBlockEnabled: Boolean get()=prefs.getBoolean("sponsorBlockEnabled",true);set(v){prefs.edit().putBoolean("sponsorBlockEnabled",v).apply()}
    var sponsorBlockCategories: Set<String> get()=prefs.getStringSet("sponsorBlockCategories",null)?.toSet()?.filter {id->app.antisocial.core.SponsorCategory.fromId(id)!=null}?.toSet() ?: app.antisocial.core.SponsorCategory.entries.map {it.id}.toSet();set(v){
        require(v.all {app.antisocial.core.SponsorCategory.fromId(it)!=null}){"Invalid SponsorBlock category"}
        prefs.edit().putStringSet("sponsorBlockCategories",v.toSet()).apply()
    }
    internal fun observeSponsorBlock(onChange: ()->Unit): ()->Unit {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==null||key=="sponsorBlockEnabled"||key=="sponsorBlockCategories")onChange()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    var readableMentions: Boolean get()=prefs.getBoolean("readableMentions",true);set(v){prefs.edit().putBoolean("readableMentions",v).apply()}
    var mentionLookups: Boolean get()=prefs.getBoolean("mentionLookups",true);set(v){prefs.edit().putBoolean("mentionLookups",v).apply()}
    var historyFullText: Boolean get()=prefs.getBoolean("historyFullText",false);set(v){prefs.edit().putBoolean("historyFullText",v).apply()}
    var historyTextMb: Int get()=prefs.getInt("historyTextMb",50);set(v){prefs.edit().putInt("historyTextMb",v.coerceIn(5,500)).apply()}
    var unseenDays: Int get()=prefs.getInt("unseenDays",7);set(v){prefs.edit().putInt("unseenDays",v.coerceIn(1,365)).apply()}
    var viewedDays: Int get()=prefs.getInt("viewedDays",30);set(v){prefs.edit().putInt("viewedDays",v.coerceIn(1,365)).apply()}
    var historyDays: Int get()=prefs.getInt("historyDays",90);set(v){prefs.edit().putInt("historyDays",v.coerceIn(1,3650)).apply()}
    var externalBrowser: String get()=prefs.getString("browser","").orEmpty();set(v){prefs.edit().putString("browser",v).apply()}
    var theme: String get()=prefs.getString("theme","system").orEmpty();set(v){prefs.edit().putString("theme",v).apply()}
    var onboarding: Boolean get()=prefs.getBoolean("onboarding",false);set(v){prefs.edit().putBoolean("onboarding",v).apply()}
    // A stored order is an explicit choice. Only absent/corrupt selections use the display default.
    var selected: List<Platform> get()=prefs.getString("platforms",null).orEmpty().split(',').mapNotNull { id->Platform.entries.firstOrNull { it.id==id&&ReaderScope.isFeedSource(it) } }.distinct().ifEmpty {PlatformOrder.defaultSelected};set(v){
        val choices=v.filter(ReaderScope::isFeedSource).distinct()
        require(choices.isNotEmpty()){"Choose at least one social source."}
        // An unstored active tab falls back to the first selected site. Preserve
        // that choice before changing its order, including a fresh installation.
        val active=activePlatform.takeIf {it in choices} ?: choices.first()
        prefs.edit().putString("platforms",choices.joinToString(","){it.id}).putString("active",active.id).apply();LinkOpening.sync(context,choices)
    }
    var activePlatform: Platform get()=selected.firstOrNull { it.id==prefs.getString("active",null) } ?: selected.first();set(v){require(ReaderScope.isFeedSource(v));prefs.edit().putString("active",v.id).apply()}
    var readingView: String get()=prefs.getString("readingView","").orEmpty().takeIf {it in readingViews}.orEmpty();set(v){require(v in readingViews);prefs.edit().putString("readingView",v).apply()}
    var readingPerson: String get()=prefs.getString("readingPerson","").orEmpty().takeIf {validReadingText(it,512)}.orEmpty();set(v){require(validReadingText(v,512));prefs.edit().putString("readingPerson",v).apply()}
    fun readingGroup(platform: Platform): String=prefs.getString("readingGroup:${platform.id}","").orEmpty()
    fun readingGroup(platform: Platform,value: String) {prefs.edit().putString("readingGroup:${platform.id}",value).apply()}
    fun disabledLists(): Set<String> = prefs.getStringSet("disabledLists",emptySet()).orEmpty().toSet()
    fun setList(id: String,enabled: Boolean) { val ids=disabledLists().toMutableSet();if(enabled)ids.remove(id) else ids.add(id);prefs.edit().putStringSet("disabledLists",ids).apply() }
    fun customLists(): Set<String> = prefs.getStringSet("customLists",emptySet()).orEmpty().toSet()
    fun addList(url: String): Boolean {
        val parsed=url.trim().toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return false
        prefs.edit().putStringSet("customLists",customLists()+parsed.newBuilder().fragment(null).build().toString()).apply();return true
    }
    fun removeList(url: String){prefs.edit().putStringSet("customLists",customLists()-url).apply()}
    fun scroll(platform: Platform): Pair<Int,Int> = prefs.getInt("scroll:${platform.id}",0) to prefs.getInt("offset:${platform.id}",0)
    fun scroll(platform: Platform,index: Int,offset: Int) {prefs.edit().putInt("scroll:${platform.id}",index).putInt("offset:${platform.id}",offset).apply()}
    fun lastUrl(platform: Platform): String = prefs.getString("url:${platform.id}","").orEmpty()
    fun lastUrl(platform: Platform,url: String) {prefs.edit().putString("url:${platform.id}",url).apply()}
    internal fun personalChannelSnapshot(): Pair<Any?,Any?> = prefs.all.let {it["personalChannels"] to it["personalChannel"]}
    internal fun contentRuleSnapshot(): Any? = prefs.all["contentRules"]
    internal fun writeContentRules(value: String) {prefs.edit().putString("contentRules",value).apply()}
    internal fun observeContentRules(onChange: ()->Unit): ()->Unit {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==null||key=="contentRules")onChange()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    internal fun writePersonalChannels(rules: String,selection: String?) {
        // Rules and selection become visible together, including deleting the
        // active channel. No transient widened feed is published to observers.
        prefs.edit().putString("personalChannels",rules).putString("personalChannel",selection.orEmpty()).apply()
    }
    internal fun writePersonalChannelSelection(selection: String?) {prefs.edit().putString("personalChannel",selection.orEmpty()).apply()}
    internal fun observePersonalChannels(onChange: ()->Unit): ()->Unit {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
            if(key==null||key=="personalChannels"||key=="personalChannel")onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    internal fun observePlatforms(onChange: ()->Unit): ()->Unit {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==null||key=="platforms")onChange()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    fun export(): JSONObject = JSONObject().apply {prefs.all.forEach { (k,v)->put(k,if(v is Set<*>)org.json.JSONArray(v.toList())else v)} }
    fun validate(data: JSONObject) {
        // JSONObject's typed getters coerce values such as "true" and "500".
        // SharedPreferences does not: validate the actual types before any import
        // writes, including fields from earlier versions and per-platform state.
        val booleans=setOf("localFlyerReading","tor","communityComments","archiveChecks","archiveRecovery","originalFallback","wifiOnly","onboarding","videoDownloadWifiOnly","historyFullText","sponsorBlockEnabled","readableMentions","mentionLookups")
        val integers=mapOf("cacheMb" to 50..5000,"videoCacheMb" to 0..5000,"videoCacheDays" to 1..365,"videoDownloadLimitMb" to 0..100_000,"historyTextMb" to 5..500,"interval" to 0..10080,"unseenDays" to 1..365,"viewedDays" to 1..365,"historyDays" to 1..3650)
        val strings=setOf("searchProvider","browser","theme","platforms","active","readingView","readingPerson","personalChannels","personalChannel","contentRules","localAreas","localArea","localDistanceUnit")
        val sets=setOf("disabledLists","customLists","sponsorBlockCategories")
        val platforms=Platform.entries.map {it.id}.toSet()
        data.keys().forEach {key->
            val value=data.get(key)
            val prefix=key.substringBefore(':');val suffix=key.substringAfter(':',"")
            val dynamic=suffix in platforms&&':' in key
            when {
                key in booleans->require(value is Boolean){"Invalid type for $key"}
                key in integers->require(value is Int&&value in integers.getValue(key)){"Invalid $key"}
                key in strings||dynamic&&prefix in setOf("url","readingGroup")->require(value is String){"Invalid type for $key"}
                dynamic&&prefix in setOf("scroll","offset")->require(value is Int){"Invalid type for $key"}
                key in sets->{
                    require(value is org.json.JSONArray){"Invalid type for $key"}
                    require((0 until value.length()).all {value.get(it) is String}){"Invalid string set for $key"}
                }
                else->throw IllegalArgumentException("Unsupported setting: $key")
            }
        }
        if(data.has("platforms"))require(data.getString("platforms").split(',').all {id->Platform.entries.any {it.id==id}}){"Invalid platform selection"}
        if(data.has("theme"))require(data.getString("theme") in setOf("system","light","dark")){"Invalid theme"}
        if(data.has("searchProvider"))require(SearchProvider.entries.any {it.id==data.getString("searchProvider")}){"Invalid search provider"}
        if(data.has("readingView"))require(data.getString("readingView") in readingViews){"Invalid reading view"}
        if(data.has("readingPerson"))require(validReadingText(data.getString("readingPerson"),512)){"Invalid saved subscription"}
        if(data.has("localAreas"))app.antisocial.core.LocalAreaCodec.decode(data.getString("localAreas"))
        if(data.has("localArea"))require(validReadingText(data.getString("localArea"),256)){"Invalid selected area"}
        if(data.has("localDistanceUnit"))require(app.antisocial.core.LocalDistanceUnit.fromId(data.getString("localDistanceUnit"))!=null){"Invalid distance unit"}
        if(data.has("sponsorBlockCategories"))require((0 until data.getJSONArray("sponsorBlockCategories").length()).all {app.antisocial.core.SponsorCategory.fromId(data.getJSONArray("sponsorBlockCategories").getString(it))!=null}){"Invalid SponsorBlock category"}
        // Parse the complete rule document before the first settings mutation.
        // A partial restore must not retain an active ID with unrelated rules.
        val channels=if(data.has("personalChannels"))PersonalChannelCodec.decode(data.getString("personalChannels"))else null
        if(data.has("contentRules"))app.antisocial.core.ContentRuleCodec.decode(data.getString("contentRules"))
        if(data.has("personalChannel")) {
            val id=data.getString("personalChannel")
            require(id.isEmpty()||validReadingText(id,160)){"Invalid selected channel"}
            if(id.isNotEmpty()) {
                val available=channels ?: personalChannelSnapshot().first?.let {raw->
                    require(raw is String){"Invalid saved channel rules"};PersonalChannelCodec.decode(raw)
                }.orEmpty()
                require(available.any {it.id==id}){"Selected channel is missing from the backup"}
            }
        }else if(channels!=null) {
            val selected=personalChannelSnapshot().second
            require(selected==null||selected is String){"Invalid saved channel selection"}
            require(selected==null||selected==""||channels.any {it.id==selected}){"Selected channel is missing from the backup"}
        }
    }
    fun restore(data: JSONObject) {
        validate(data)
        val edit=prefs.edit()
        data.keys().forEach { key->when(val v=data.get(key)) {is String->edit.putString(key,v);is Boolean->edit.putBoolean(key,v);is Int->edit.putInt(key,v);is org.json.JSONArray->edit.putStringSet(key,(0 until v.length()).map { v.getString(it) }.toSet())} }
        edit.apply()
        LinkOpening.sync(context,selected)
    }
    private fun validReadingText(value: String,limit: Int)=value.length<=limit&&!value.any(Char::isISOControl)
    private val readingViews=setOf("","following","all","person","target")
}
