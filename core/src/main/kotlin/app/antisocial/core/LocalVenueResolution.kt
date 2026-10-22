/* anti social, GPL-3.0-or-later. Resolve only explicit venue addresses inside the selected area. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalVenueResolution {
    fun eligible(query:LocalDiscoveryQuery,event:LocalEvent,at:Long=System.currentTimeMillis()):Boolean {
        if(query.mode!=LocalSearchMode.EVENTS||event.point!=null||event.online||!LocalGeography.compatible(query.area,event.address,event.evidence)||LocalDiscoveryMerge.street(event.address)==null)return false
        if(!LocalDiscoveryFilter.matches(LocalDiscoveryFilter.eventQuery(query.text),event.title+" "+event.description+" "+event.venue))return false
        // Reuse the date filter with a location witness solely to check dates;
        // no such witness is ever copied into the real event or store.
        return LocalDiscoveryFilter.event(query,event.copy(point=query.area.point),at)
    }
    fun known(area:LocalArea,event:LocalEvent,places:List<LocalBusiness>):LocalEvent? {
        val street=LocalDiscoveryMerge.street(event.address) ?: return null
        val words=LocalDiscoveryIdentity.words(event.address)
        val candidates=places.filter {place->
            val city=LocalDiscoveryIdentity.words(place.locality)
            place.point!=null&&LocalGeography.compatible(area,event.address,event.evidence)&&LocalGeography.inside(area,place.point,place.locality,place.address,place.evidence)&&LocalDiscoveryMerge.street(place.address)==street&&
                (city.isNotEmpty()&&words.windowed(city.size).any {it==city})
        }
        val place=candidates.firstOrNull() ?: return null
        if(candidates.any {it.point!!.distanceKm(place.point!!)>0.15})return null
        return event.copy(point=place.point,locality=event.locality.ifBlank {place.locality},evidence=event.evidence+place.evidence.filter {it.field=="location"}.take(1))
    }
    fun request(query:LocalDiscoveryQuery,event:LocalEvent):String {
        require(event.point==null&&LocalDiscoveryMerge.street(event.address)!=null&&LocalGeography.compatible(query.area,event.address,event.evidence))
        return LocalPlaceSources.photonUrl(query.copy(text=event.address.take(256))).toHttpUrlOrNull()!!.newBuilder().setQueryParameter("q",event.address.take(256)).setQueryParameter("limit","5").build().toString()
    }
    fun parse(body:String,url:String,at:Long,area:LocalArea,event:LocalEvent):LocalEvent? {
        require(body.length<=256*1024)
        val street=LocalDiscoveryMerge.street(event.address) ?: return null
        val root=JsonParser.parseString(body).asJsonObject
        fun s(o:JsonObject,key:String)=o[key]?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val rows=root["features"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(10).orEmpty()
        val candidates=rows.mapNotNull {raw->
            val row=raw.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val p=row["properties"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val coords=row["geometry"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
            if(coords.size()!=2)return@mapNotNull null
            val geo=runCatching {LocalPoint(coords[1].asDouble,coords[0].asDouble).takeIf(LocalPoint::valid)}.getOrNull() ?: return@mapNotNull null
            val address=listOf(s(p,"housenumber"),s(p,"street")).filter(String::isNotBlank).joinToString(" ")
            if(LocalDiscoveryMerge.street(address)!=street||area.point.distanceKm(geo)>area.radiusKm)return@mapNotNull null
            val city=s(p,"city").ifBlank {s(p,"town")}
            val addressWords=LocalDiscoveryIdentity.words(event.address)
            val cityWords=LocalDiscoveryIdentity.words(city)
            if(cityWords.isEmpty()||addressWords.windowed(cityWords.size).none {it==cityWords})return@mapNotNull null
            val postal=Regex("\\b\\d{5}(?:-\\d{4})?\\b").findAll(event.address).lastOrNull()?.value?.take(5)
            if(postal!=null&&s(p,"postcode").isNotBlank()&&s(p,"postcode").take(5)!=postal)return@mapNotNull null
            val evidence=listOfNotNull(s(p,"countrycode").takeIf(String::isNotBlank)?.let {LocalDiscoveryIdentity.evidence("country",it,url,at,LocalEvidenceKind.DIRECTORY)})
            if(!LocalGeography.compatible(area,"",evidence))return@mapNotNull null
            city to geo
        }
        val selected=candidates.firstOrNull() ?: return null
        if(candidates.any {it.second.distanceKm(selected.second)>0.15})return null
        return event.copy(point=selected.second,locality=selected.first,evidence=event.evidence+LocalDiscoveryIdentity.evidence("location","${selected.second.latitude},${selected.second.longitude}",url,at,LocalEvidenceKind.DIRECTORY,"© OpenStreetMap contributors · ODbL (via Photon)"))
    }
}
