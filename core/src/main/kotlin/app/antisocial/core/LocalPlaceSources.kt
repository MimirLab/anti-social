/* AntiSocial, GPL-3.0-or-later. Small public geographic responses; all transport belongs to NetworkGateway. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

object LocalPlaceSources {
    /** Public Fused Overture example, geographically clipped, not a full data
     * download. The provider can change independently of GERS identities. */
    fun overtureUrl(point:LocalPoint,endpoint:String="https://www.fused.io/server/v1/realtime-shared/UDF_Overture_Maps_Example/run/file"):String {
        require(point.valid())
        val lat=Math.rint(point.latitude*1000)/1000;val lon=Math.rint(point.longitude*1000)/1000
        val delta=.001 / kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(.15)
        val bounds=JsonArray().apply {add((lon-delta).coerceAtLeast(-180.0));add((lat-.001).coerceAtLeast(-90.0));add((lon+delta).coerceAtMost(180.0));add((lat+.001).coerceAtMost(90.0))}
        return requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder().addQueryParameter("format","geojson").addQueryParameter("theme","places")
            .addQueryParameter("overture_type","place").addQueryParameter("bounds",bounds.toString()).addQueryParameter("clip","true")
            .addQueryParameter("use_columns",JsonArray().apply {listOf("id","names","categories","websites","socials","phones","addresses","confidence","geometry","sources").forEach(::add)}.toString()).build().toString()
    }
    private fun s(o:JsonObject,key:String)=o[key]?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
    fun geocodeUrl(query:String,endpoint:String="https://geocoding-api.open-meteo.com/v1/search"):String {
        require(query.trim().length in 2..160)
        return requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder().addQueryParameter("name",query.trim()).addQueryParameter("count","8")
            .addQueryParameter("language","en").addQueryParameter("format","json").build().toString()
    }
    fun areas(body:String):List<LocalArea> {
        require(body.length<=256*1024)
        val root=JsonParser.parseString(body).asJsonObject
        return root["results"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty().take(12).mapNotNull {item->
            val o=item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val point=LocalPoint(s(o,"latitude").toDoubleOrNull() ?: return@mapNotNull null,s(o,"longitude").toDoubleOrNull() ?: return@mapNotNull null)
            val name=s(o,"name");val label=listOf(name,s(o,"admin1"),s(o,"country")).filter(String::isNotBlank).distinct().joinToString(", ")
            val zone=s(o,"timezone").takeIf(String::isNotBlank)
            LocalArea("geonames:"+s(o,"id"),name,label,point,zoneId=zone,countryCode=s(o,"country_code"),aliases=listOf(name)).takeIf(LocalArea::valid)
        }.distinctBy {it.id}
    }
    /** One bounded regional batch. The query is code-generated; entered words
     * never become Overpass QL. It grows a directory, rather than one request per result. */
    fun overpassUrl(area:LocalArea,endpoint:String="https://overpass-api.de/api/interpreter",hubs:Boolean=false,topic:String=""):String {
        require(area.valid())
        val latitude=String.format(Locale.ROOT,"%.5f",area.point.latitude);val longitude=String.format(Locale.ROOT,"%.5f",area.point.longitude)
        val radius=area.radiusKm*1000
        val around="(around:$radius,$latitude,$longitude)"
        val words=LocalDiscoveryIdentity.words(LocalDiscoveryFilter.normalizedQuery(topic)).toSet()
        val interest=when {"organic" in words->"[organic~\"^(yes|only)$\"]";"coffee" in words||"cafe" in words->"[amenity=cafe]";"taco" in words->"[cuisine~\"mexican|tex.mex|taco\",i]";"burger" in words->"[cuisine~\"burger\",i]";"trampoline" in words->"[leisure=trampoline_park]";else->null}
        val shops=if(interest!=null)"nwr$interest[name]$around;"else if(hubs)""else "nwr[shop][name]$around;"
        val amenities=if(hubs)"library|community_centre|arts_centre|theatre|cinema|place_of_worship|marketplace|college|school|social_centre|townhall"else "cafe|restaurant|bar|pub|library|community_centre|arts_centre|theatre|cinema|place_of_worship|marketplace|college|school|social_centre"
        val meaningful=words.filterNot {it in setOf("near","me","in","at","the","and","for")}.take(8)
        // Words contain only Unicode letters/numbers. No user punctuation or
        // Overpass syntax can enter this generated expression.
        val named=if(hubs||meaningful.isEmpty())""else listOf("name","brand","alt_name").joinToString("") {key->"nwr[$key~\"${meaningful.joinToString(".*")}\",i]$around;"}
        val others=if(!hubs&&interest!=null)""else if(!hubs&&meaningful.isNotEmpty())""else "nwr[amenity~\"^($amenities)$\"][name]$around;nwr[tourism~\"^(museum|gallery|information|attraction)$\"][name]$around;nwr[leisure~\"^(trampoline_park|sports_centre|fitness_centre|bowling_alley)$\"][name]$around;"
        val selectedShops=if(meaningful.isNotEmpty()&&interest==null)""else shops
        // Overpass maxsize is server working RAM, not response size. Keep
        // transfer/storage capped separately at 2 MiB in NetworkGateway.
        val query="[out:json][timeout:18][maxsize:33554432];($named $selectedShops $others);out center tags 350;"
        return requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder().addQueryParameter("data",query).build().toString()
    }
    /** One explicit, cached request. Photon supplies OSM name/address/coordinates;
     * it is an additional route, not an independent corroborating data set. */
    fun photonUrl(query:LocalDiscoveryQuery,endpoint:String="https://photon.komoot.io/api/"):String {
        require(query.area.valid()&&query.text.trim().length in 2..256)
        val p=query.area.point;val d=query.area.radiusKm/110.0;val lon=d/kotlin.math.cos(Math.toRadians(p.latitude)).coerceAtLeast(.1)
        val builder=requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder().addQueryParameter("q",LocalDiscoveryFilter.normalizedQuery(query.text))
            .addQueryParameter("lat",p.latitude.toString()).addQueryParameter("lon",p.longitude.toString()).addQueryParameter("limit","20").addQueryParameter("lang","en")
        if(p.longitude-lon>= -180&&p.longitude+lon<=180)builder.addQueryParameter("bbox",listOf(p.longitude-lon,(p.latitude-d).coerceAtLeast(-90.0),p.longitude+lon,(p.latitude+d).coerceAtMost(90.0)).joinToString(","))
        return builder.build().toString()
    }
    fun photon(body:String,at:Long):LocalExtraction {
        require(body.length<=512*1024)
        val rows=JsonParser.parseString(body).asJsonObject["features"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: throw SourceFailure(HealthStatus.EMPTY,"No matching public places were returned")
        val places=rows.take(40).mapNotNull {item->
            val f=item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val p=f["properties"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            if(s(p,"osm_key") !in setOf("amenity","shop","tourism","leisure","office","craft","healthcare"))return@mapNotNull null
            val coordinates=f["geometry"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
            if(coordinates.size()!=2)return@mapNotNull null
            val point=runCatching {LocalPoint(coordinates[1].asDouble,coordinates[0].asDouble)}.getOrNull()?.takeIf(LocalPoint::valid) ?: return@mapNotNull null
            val type=when(s(p,"osm_type")){"N"->"node";"W"->"way";"R"->"relation";else->return@mapNotNull null}
            val id=s(p,"osm_id").takeIf {it.matches(Regex("[0-9]{1,20}"))} ?: return@mapNotNull null
            val name=s(p,"name").take(300);if(name.isBlank())return@mapNotNull null
            val city=s(p,"city").ifBlank {s(p,"town")}.take(200)
            val street=listOf(s(p,"housenumber"),s(p,"street")).filter(String::isNotBlank).joinToString(" ")
            val address=listOf(street,city,s(p,"state"),s(p,"postcode"),s(p,"country")).filter(String::isNotBlank).joinToString(", ").take(1000)
            val source="https://www.openstreetmap.org/$type/$id";val identity="osm:$type/$id";val category=s(p,"osm_value").replace('_',' ')
            LocalBusiness(identity,name,category,address=address,locality=city,point=point,sourceIds=listOf(identity),checkedAt=at,
                evidence=mapOf("name" to name,"address" to address,"locality" to city,"location" to "${point.latitude},${point.longitude}").filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,source,at,LocalEvidenceKind.DIRECTORY,"© OpenStreetMap contributors · ODbL (via Photon)")})
        }
        return LocalExtraction(businesses=places)
    }
    fun overpass(body:String,sourceUrl:String,at:Long):LocalExtraction {
        require(body.length<=2*1024*1024)
        val root=JsonParser.parseString(body).asJsonObject
        if(root.has("remark"))throw SourceFailure(HealthStatus.UNAVAILABLE,"The place directory reached its query allowance; cached places remain available")
        val entries=root["elements"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: throw SourceFailure(HealthStatus.EMPTY,"The place directory supplied no records")
        val places=entries.take(500).mapNotNull {item->
            val row=item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val tags=row["tags"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val name=s(tags,"name");if(name.isBlank()||s(tags,"access")=="private")return@mapNotNull null
            val center=row["center"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: row
            val geo=LocalPoint(s(center,"lat").toDoubleOrNull() ?: return@mapNotNull null,s(center,"lon").toDoubleOrNull() ?: return@mapNotNull null).takeIf(LocalPoint::valid) ?: return@mapNotNull null
            val identity="osm:"+s(row,"type")+"/"+s(row,"id");val url="https://www.openstreetmap.org/"+s(row,"type")+"/"+s(row,"id")
            val street=listOf(s(tags,"addr:housenumber").take(100),s(tags,"addr:street").take(300)).filter(String::isNotBlank).joinToString(" ")
            val address=listOf(street,s(tags,"addr:city").take(200),s(tags,"addr:state").take(200),s(tags,"addr:postcode").take(100)).filter(String::isNotBlank).joinToString(", ")
            val category=listOf("shop","amenity","tourism","leisure","office","craft","healthcare").firstNotNullOfOrNull {key->s(tags,key).takeIf(String::isNotBlank)}.orEmpty().replace('_',' ')
            val websites=listOf("website","contact:website","url").flatMap {key->s(tags,key).split(Regex("[;\\n]|,\\s+(?=https?://)",RegexOption.IGNORE_CASE))}.mapNotNull {LocalDiscoveryIdentity.url(it.trim())}.distinct()
            val socials=(LocalBusinessSocial.tagged {s(tags,it)}+websites.filter(LocalStructuredData::accountLink)).distinct().take(24)
            val hours=s(tags,"opening_hours").take(8000);val status=when {s(tags,"disused")=="yes"->"Reported closed";s(tags,"opening_hours")=="closed"->"Reported closed";else->""}
            val fields=mapOf("name" to name,"category" to category,"address" to address,"locality" to s(tags,"addr:city"),"hours" to hours,"socials" to socials.joinToString(" · "),"websites" to websites.joinToString(" · "),"status" to status,"location" to "${geo.latitude},${geo.longitude}")
            LocalBusiness(identity,name.take(300),category,s(tags,"description").take(4000),address,s(tags,"addr:city").take(200),geo,websites.filterNot(LocalStructuredData::accountLink),socials,
                listOf(s(tags,"phone").take(100),s(tags,"contact:phone").take(100)).filter(String::isNotBlank).distinct(),hours,status,listOf(identity),
                fields.filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.DIRECTORY,"© OpenStreetMap contributors · ODbL")}+
                    listOf("website:menu","contact:menu").flatMap {LocalDiscoveryIdentity.urls(s(tags,it))}.distinct().take(3).map {LocalDiscoveryIdentity.evidence("menu",it,url,at,LocalEvidenceKind.DIRECTORY,"© OpenStreetMap contributors · ODbL")},at,
                tags.entrySet().filter {it.key in setOf("cuisine","organic","diet:vegan","diet:vegetarian","wheelchair","brand","operator")}.flatMap {listOf(it.key,it.value.asString)}.take(32))
        }
        return LocalExtraction(businesses=places,notice=if(entries.size()>=350)"This regional batch is limited. Search publishers or narrow the area for more coverage."else null)
    }
}
