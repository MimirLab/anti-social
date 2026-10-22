/* AntiSocial, GPL-3.0-or-later. Saved discovery text and field evidence, never query history. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.*

internal data class PortableLocalDiscovery(val businesses:List<LocalBusiness> = emptyList(),val events:List<LocalEvent> = emptyList())
internal object LocalDiscoveryPortability {
    private val gson=Gson()
    fun encode(store:LocalDiscoveryStore):JsonObject=JsonObject().apply {addProperty("version",1);add("businesses",gson.toJsonTree(store.businesses(null,true)));add("events",gson.toJsonTree(store.events(true)))}
    fun decode(value:JsonElement):PortableLocalDiscovery {
        require(value.isJsonObject&&value.toString().length<=32*1024*1024)
        val o=value.asJsonObject;require(o.keySet()==setOf("version","businesses","events")&&o["version"].isJsonPrimitive&&o["version"].asJsonPrimitive.isNumber&&o["version"].asString=="1")
        fun <T> rows(key:String,clazz:Class<T>,check:(T)->Unit):List<T> {val array=o[key];require(array.isJsonArray&&array.asJsonArray.size()<=2000);return array.asJsonArray.map {v->require(v.isJsonObject&&v.toString().length<=256*1024);strictSavedModel(v.asJsonObject,clazz);gson.fromJson(v,clazz).also(check)}}
        fun url(value:String)=LocalDiscoveryIdentity.url(value)!=null
        fun evidence(values:List<LocalFieldEvidence>) {require(values.size<=96&&values.all {it.field.length<=100&&it.value.length<=16000&&it.excerpt.length<=500&&it.checkedAt>=0&&url(it.sourceUrl)&&(it.license?.length ?: 0)<=1000&&(it.region?.let {r->url(r.imageUrl)&&r.sha256.matches(Regex("[a-f0-9]{64}"))&&r.width in 1..120000&&r.height in 1..120000&&r.left>=0&&r.top>=0&&r.right in r.left..r.width&&r.bottom in r.top..r.height} ?: true)})}
        val businesses=rows("businesses",LocalBusiness::class.java) {b->require(b.id.length in 1..256&&b.name.length in 1..400&&b.category.length<=400&&b.description.length<=8000&&b.address.length<=2000&&b.locality.length<=300&&(b.point?.valid() ?: true)&&b.websites.size<=12&&b.socials.size<=24&&(b.websites+b.socials).all(::url)&&b.phones.size<=8&&b.phones.all {it.length<=100}&&b.emails.orEmpty().size<=8&&b.emails.orEmpty().all {LocalBusinessContact.email(it)==it}&&b.related.orEmpty().size<=16&&b.related.orEmpty().all {it.name.length in 1..300&&it.role in setOf("owner","operator","founder","source publisher")&&it.accounts.size<=24&&it.accounts.all(::url)&&url(it.sourceUrl)}&&b.hours.length<=8000&&b.status.length<=1000&&b.sourceIds.size<=32&&b.sourceIds.all {it.length<=16384}&&b.keywords.size<=64&&b.keywords.all {it.length<=1000}&&b.checkedAt>=0);evidence(b.evidence)}
        val events=rows("events",LocalEvent::class.java) {e->require(e.id.length in 1..256&&e.title.length in 1..400&&e.start.valid()&&(e.end?.valid() ?: true)&&e.description.length<=8000&&e.venue.length<=1000&&e.address.length<=2000&&e.locality.length<=300&&(e.point?.valid() ?: true)&&e.organizer.length<=300&&e.urls.size<=24&&e.urls.all(::url)&&listOfNotNull(e.organizerUrl,e.registrationUrl,e.image).all(::url)&&e.price.length<=1000&&e.age.length<=100&&e.status in setOf("scheduled","cancelled","tentative","rescheduled","postponed")&&e.sourceIds.size<=32&&e.sourceIds.all {it.length<=16384}&&e.checkedAt>=0&&e.sequence>=0&&(e.modifiedAt ?: 0)>=0&&(e.occurrence?.length ?: 0)<=100);evidence(e.evidence)}
        require(businesses.all {it.menus.orEmpty().size<=4&&it.menus.orEmpty().sumOf {m->m.items.size}<=LocalMenus.MAX_ITEMS&&it.menus.orEmpty().all(LocalMenus::valid)})
        require(businesses.size+events.size<=2000&&(businesses.map {it.id}+events.map {it.id}).distinct().size==businesses.size+events.size)
        return PortableLocalDiscovery(businesses,events)
    }
}
