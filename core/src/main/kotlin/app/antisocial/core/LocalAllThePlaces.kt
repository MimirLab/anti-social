/* AntiSocial, GPL-3.0-or-later. Consume published AllThePlaces CC0 slices; never run spiders. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader

data class LocalPlaceSlice(val spider:String,val features:Int)
object LocalAllThePlaces {
    const val INFO="https://data.alltheplaces.xyz/runs/latest/info_embed.html"
    const val MAX_BYTES=8L*1024*1024
    fun root(body:String):String? {
        val url=Jsoup.parse(body,INFO).select("a[href]").mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("href"))}.firstOrNull {
            val p=it.toHttpUrlOrNull();p?.host=="alltheplaces-data.openaddresses.io"&&Regex("/runs/[0-9-]+/output\\.zip").matches(p.encodedPath)
        } ?: return null
        return url.substringBeforeLast('/')+"/"
    }
    fun catalog(body:String):List<LocalPlaceSlice> {
        require(body.length<=2*1024*1024)
        val rows=JsonParser.parseString(body).asJsonObject["results"].asJsonArray
        return rows.take(10000).mapNotNull {raw->runCatching {
            val o=raw.asJsonObject;val name=o["spider"].asString;val n=o["features"].asInt
            if(!name.matches(Regex("[a-z0-9_]{2,120}"))||n !in 1..6000)null else LocalPlaceSlice(name,n)
        }.getOrNull()}
    }
    fun select(catalog:List<LocalPlaceSlice>,businesses:List<LocalBusiness>,area:LocalArea):List<LocalPlaceSlice> {
        val country=area.countryCode?.lowercase()
        val generic=setOf("restaurant","restaurants","cafe","coffee","library","store","shop","bar","park","hotel","company","co","inc","llc","the","trampoline","grill")
        val names=businesses.take(12).map {LocalDiscoveryIdentity.words(it.name).filterNot(generic::contains)}.filter {it.isNotEmpty()}
        return catalog.mapNotNull {row->
            val raw=row.spider.split('_');val suffix=raw.last();val region=if(suffix.length==2)suffix else null
            if(region!=null&&country!=null&&region!=country)return@mapNotNull null
            val words=(if(region!=null)raw.dropLast(1)else raw).filterNot(generic::contains)
            if(words.isEmpty()||words.size==1&&words.single().length<4)return@mapNotNull null
            val rank=names.indexOfFirst {name->words.all {it in name}||words.joinToString("")==name.joinToString("")}
            if(rank<0)null else row to rank
        }.sortedWith(compareBy<Pair<LocalPlaceSlice,Int>> {it.second}.thenBy {it.first.features}).distinctBy {it.first.spider}.take(2).map {it.first}
    }
    fun parse(body:String,url:String,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=MAX_BYTES)
        val places=mutableListOf<LocalBusiness>();var seen=0
        fun feature(raw:JsonElement) {
            if(++seen>10000)return
            val o=raw.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return
            val p=o["properties"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return
            fun s(key:String)=p[key]?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty().take(2000)
            if(area.countryCode!=null&&s("addr:country").isNotBlank()&&!area.countryCode.equals(s("addr:country"),true))return
            val name=s("name").ifBlank {s("brand")};val spider=s("@spider");if(name.isBlank()||spider.isBlank())return
            val geometry=o["geometry"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val coords=geometry?.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            val point=if(geometry?.get("type")?.asString=="Point"&&coords?.size()==2)runCatching {LocalPoint(coords[1].asDouble,coords[0].asDouble).takeIf(LocalPoint::valid)}.getOrNull()else null
            if(point!=null&&area.point.distanceKm(point)>area.radiusKm)return
            val address=s("addr:full").ifBlank {listOf(s("addr:street_address").ifBlank {listOf(s("addr:housenumber"),s("addr:street")).filter(String::isNotBlank).joinToString(" ")},s("addr:unit").takeIf(String::isNotBlank)?.let {"Unit $it"}.orEmpty(),s("addr:city"),s("addr:state"),s("addr:postcode"),s("addr:country")).filter(String::isNotBlank).joinToString(", ")}
            val ref=s("ref").ifBlank {o["id"]?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()}.ifBlank {name+address}
            val id="atp:"+LocalDiscoveryIdentity.hash(spider+"|"+ref)
            val websites=LocalDiscoveryIdentity.urls(s("website"));val phone=s("phone").split(';').filter {LocalBusinessContact.phone(it)!=null}.take(8)
            val email=s("email").split(';').mapNotNull(LocalBusinessContact::email).distinct().take(8)
            val socials=(LocalBusinessSocial.tagged(::s)+websites.filter(LocalStructuredData::accountLink)).distinct().take(24)
            val category=s("cuisine").ifBlank {listOf("amenity","shop","leisure","tourism").firstNotNullOfOrNull {s(it).takeIf(String::isNotBlank)}.orEmpty()}.replace('_',' ')
            val lineage=("AllThePlaces · CC0 · spider $spider"+s("@source_uri").takeIf(String::isNotBlank)?.let {" · upstream $it"}.orEmpty()).take(1000)
            val fields=linkedMapOf("name" to name,"category" to category,"address" to address,"locality" to s("addr:city"),"phones" to phone.joinToString(" · "),"emails" to email.joinToString(" · "),"websites" to websites.joinToString(" · "),"socials" to socials.joinToString(" · "),"hours" to s("opening_hours"),"cuisine" to s("cuisine"),"brand id" to s("brand:wikidata"))
            point?.let {fields["location"]="${it.latitude},${it.longitude}"}
            val place=LocalBusiness(id,name.take(300),category.take(400),address=address.take(2000),locality=s("addr:city").take(200),point=point,websites=websites.filterNot(LocalStructuredData::accountLink).take(12),socials=socials,phones=phone.map {it.take(100)},hours=s("opening_hours"),sourceIds=listOf(id),checkedAt=at,
                keywords=listOf(s("brand"),s("branch"),s("brand:wikidata")).filter(String::isNotBlank).map {it.take(1000)},emails=email.takeIf {it.isNotEmpty()},related=s("operator").takeIf(String::isNotBlank)?.let {listOf(LocalRelatedEntity(it.take(300),"operator",emptyList(),url))},
                evidence=fields.filterValues(String::isNotBlank).map {(k,v)->LocalDiscoveryIdentity.evidence(k,v,url,at,LocalEvidenceKind.DIRECTORY,lineage)})
            if(LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),place))places+=place
        }
        JsonReader(StringReader(body)).use {reader->
            reader.beginObject()
            while(reader.hasNext()) {
                if(reader.nextName()=="features") {reader.beginArray();while(reader.hasNext()){if(seen>=10000){reader.skipValue();continue};feature(JsonParser.parseReader(reader))};reader.endArray()}
                else reader.skipValue()
            };reader.endObject()
        }
        return LocalExtraction(businesses=places.take(2000))
    }
}
