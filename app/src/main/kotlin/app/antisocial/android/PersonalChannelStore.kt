/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.ChannelContentType
import app.antisocial.core.PersonalChannel
import app.antisocial.core.PersonalChannels
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.StringReader

/** Explicit rules stay in the same private, local settings backup as subscriptions. */
class PersonalChannelStore(private val prefs: Preferences) {
    private val mutableChannels=MutableStateFlow<List<PersonalChannel>>(emptyList())
    private val mutableSelected=MutableStateFlow<String?>(null)
    private val mutableError=MutableStateFlow<String?>(null)
    private var rulesReadable=true
    val channels: StateFlow<List<PersonalChannel>> = mutableChannels.asStateFlow()
    val selected: StateFlow<String?> = mutableSelected.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    // SharedPreferences retains listeners weakly; keep this release closure alive.
    @Suppress("unused") private val observe=prefs.observePersonalChannels(::refresh)

    init {refresh()}

    @Synchronized fun refresh() {
        val (stored,selection)=prefs.personalChannelSnapshot()
        val selected=if(selection==null)null else (selection as? String)?.takeIf {it.isNotEmpty()}
        // A malformed selection is deliberately non-null: the feed must not fall
        // back to All merely because a stored rule cannot be read.
        mutableSelected.value=if(selection!=null&&selection !is String)INVALID_SELECTION else selected
        try {
            require(selection==null||selection is String){"The saved channel selection could not be read."}
            val channels=if(stored==null)emptyList()else {
                require(stored is String){"Saved channel rules could not be read."}
                PersonalChannelCodec.decode(stored)
            }
            mutableChannels.value=channels
            rulesReadable=true
            mutableError.value=if(selected!=null&&channels.none {it.id==selected})"The selected channel is unavailable. Choose a channel or All subscriptions."else null
        }catch(_: Exception) {
            rulesReadable=false
            mutableChannels.value=emptyList()
            mutableError.value="Saved channel rules could not be read. Restore a valid backup, or reset channels to start again."
        }
    }

    @Synchronized fun save(channel: PersonalChannel): PersonalChannel {
        check(rulesReadable){"Repair or reset saved channels before changing their rules."}
        val inputErrors=PersonalChannels.validate(channel)
        require(inputErrors.isEmpty()){inputErrors.joinToString("\n")}
        val prior=mutableChannels.value.firstOrNull {it.id==channel.id}
        val clean=PersonalChannels.normalize(channel).copy(revision=if(prior==null)1 else {
            check(prior.revision<Long.MAX_VALUE){"This channel can no longer be edited."};prior.revision+1
        })
        val errors=PersonalChannels.validate(clean)
        require(errors.isEmpty()){errors.joinToString("\n")}
        val updated=if(prior==null) {
            require(mutableChannels.value.size<PersonalChannels.MAX_CHANNELS){"You can keep up to ${PersonalChannels.MAX_CHANNELS} channels."}
            mutableChannels.value+clean
        }else mutableChannels.value.map {if(it.id==clean.id)clean else it}
        prefs.writePersonalChannels(PersonalChannelCodec.encode(updated),mutableSelected.value)
        refresh()
        return clean
    }

    @Synchronized fun select(id: String?) {
        if(id!=null) {
            check(rulesReadable){"Saved channel rules could not be read."}
            require(mutableChannels.value.any {it.id==id}){"This channel no longer exists."}
        }
        prefs.writePersonalChannelSelection(id)
        refresh()
    }

    @Synchronized fun delete(id: String) {
        check(rulesReadable){"Repair or reset saved channels before deleting them."}
        val channels=mutableChannels.value.filterNot {it.id==id}
        prefs.writePersonalChannels(PersonalChannelCodec.encode(channels),mutableSelected.value.takeUnless {it==id})
        refresh()
    }

    /** The UI asks explicitly before discarding unreadable local rules. */
    @Synchronized fun reset() {
        prefs.writePersonalChannels(PersonalChannelCodec.encode(emptyList()),null)
        refresh()
    }

    private companion object {const val INVALID_SELECTION="!unreadable-channel-selection"}
}

/** Strict decoding prevents a damaged or incompatible rule from becoming a broader one. */
internal object PersonalChannelCodec {
    private const val MAX_DOCUMENT_LENGTH=2_000_000
    private val channelKeys=setOf("id","name","subscriptionIds","keywords","hashtags","contentTypes","excludeKeywords","excludeHashtags","excludeSubscriptionIds","minVideoSeconds","revision","excludeContentTypes")
    fun encode(channels: List<PersonalChannel>): String=JSONObject().put("version",1).put("channels",JSONArray().apply {
        channels.forEach {channel->put(JSONObject().apply {
            put("id",channel.id);put("name",channel.name)
            put("subscriptionIds",JSONArray(channel.subscriptionIds.sorted()))
            put("keywords",JSONArray(channel.keywords));put("hashtags",JSONArray(channel.hashtags))
            put("contentTypes",JSONArray(channel.contentTypes.map {it.name}.sorted()))
            put("excludeContentTypes",JSONArray(channel.excludeContentTypes.map {it.name}.sorted()))
            put("excludeKeywords",JSONArray(channel.excludeKeywords));put("excludeHashtags",JSONArray(channel.excludeHashtags))
            put("excludeSubscriptionIds",JSONArray(channel.excludeSubscriptionIds.sorted()))
            put("minVideoSeconds",channel.minVideoSeconds ?: JSONObject.NULL);put("revision",channel.revision)
        })}
    }).toString().also {require(it.length<=MAX_DOCUMENT_LENGTH){"Saved channel rules are too large."}}

    fun decode(value: String): List<PersonalChannel> {
        require(value.length<=MAX_DOCUMENT_LENGTH){"Saved channel rules are too large."}
        strictJson(value)
        val tokens=JSONTokener(value)
        val root=tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException("Invalid channel storage format.")
        require(tokens.nextClean()=='\u0000'){"Unexpected data after saved channel rules."}
        require(root.keys().asSequence().toSet()==setOf("version","channels")){"Invalid channel storage format."}
        require(root.get("version") is Int&&root.getInt("version")==1){"Unsupported channel storage version."}
        val array=root.get("channels") as? JSONArray ?: throw IllegalArgumentException("Invalid channel list.")
        require(array.length()<=PersonalChannels.MAX_CHANNELS){"Too many channels."}
        val result=(0 until array.length()).map {i->
            val item=array.get(i) as? JSONObject ?: throw IllegalArgumentException("Invalid channel.")
            require(item.keys().asSequence().toSet()==channelKeys){"Invalid channel fields."}
            fun text(key: String)=item.get(key) as? String ?: throw IllegalArgumentException("Invalid channel $key.")
            fun strings(key: String,limit: Int): List<String> {
                val list=item.get(key) as? JSONArray ?: throw IllegalArgumentException("Invalid channel $key.")
                require(list.length()<=limit){"Too many entries in $key."}
                return (0 until list.length()).map {list.get(it) as? String ?: throw IllegalArgumentException("Invalid channel $key.")}.also {
                    require(it.size==it.toSet().size){"Repeated channel $key."}
                }
            }
            fun integer(key: String): Long=when(val n=item.get(key)) {
                is Int->n.toLong();is Long->n;else->throw IllegalArgumentException("Invalid channel $key.")
            }
            val channel=PersonalChannel(
                id=text("id"),name=text("name"),
                subscriptionIds=strings("subscriptionIds",PersonalChannels.MAX_SUBSCRIPTIONS).toSet(),
                keywords=strings("keywords",PersonalChannels.MAX_TERMS),hashtags=strings("hashtags",PersonalChannels.MAX_TERMS),
                contentTypes=strings("contentTypes",ChannelContentType.entries.size).map {name->
                    ChannelContentType.entries.firstOrNull {it.name==name} ?: throw IllegalArgumentException("Unknown channel content type.")
                }.toSet(),
                excludeKeywords=strings("excludeKeywords",PersonalChannels.MAX_TERMS),excludeHashtags=strings("excludeHashtags",PersonalChannels.MAX_TERMS),
                excludeSubscriptionIds=strings("excludeSubscriptionIds",PersonalChannels.MAX_SUBSCRIPTIONS).toSet(),
                minVideoSeconds=if(item.isNull("minVideoSeconds"))null else integer("minVideoSeconds"),revision=integer("revision"),
                excludeContentTypes=strings("excludeContentTypes",ChannelContentType.entries.size).map {name->
                    ChannelContentType.entries.firstOrNull {it.name==name} ?: throw IllegalArgumentException("Unknown excluded channel content type.")
                }.toSet()
            )
            require(PersonalChannels.validate(channel).isEmpty()){"Invalid saved channel rules."}
            channel
        }
        require(result.map {it.id}.distinct().size==result.size){"Repeated channel ID."}
        return result
    }

    private fun strictJson(value: String) {
        // JSONObject alone accepts duplicate keys, unquoted strings and trailing
        // input. None may silently replace a narrower rule during restoration.
        try {JsonReader(StringReader(value)).use {reader->
            reader.strictness=Strictness.STRICT
            fun read(depth: Int) {
                require(depth<=6){"Channel rules are nested too deeply."}
                when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT->{
                        reader.beginObject();val names=mutableSetOf<String>()
                        while(reader.hasNext()){require(names.add(reader.nextName())){"Repeated channel field."};read(depth+1)}
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY->{reader.beginArray();while(reader.hasNext())read(depth+1);reader.endArray()}
                    JsonToken.STRING,JsonToken.NUMBER->reader.nextString()
                    JsonToken.BOOLEAN->reader.nextBoolean()
                    JsonToken.NULL->reader.nextNull()
                    else->throw IllegalArgumentException("Invalid channel storage format.")
                }
            }
            read(0);require(reader.peek()==JsonToken.END_DOCUMENT){"Unexpected data after saved channel rules."}
        }}catch(e: IllegalArgumentException){throw e}catch(e: Exception){throw IllegalArgumentException("Invalid channel storage format.",e)}
    }
}
