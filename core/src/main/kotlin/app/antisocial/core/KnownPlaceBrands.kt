/* anti social, GPL-3.0-or-later. NSI contributors, BSD-3-Clause;
 * see place-brands.json, licenses/name-suggestion-index.txt and its pinned compiler. */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Offline lookup hints for reading groups. Never modifies a location record,
 * supplies business facts or proves control of a website/social account. */
internal class PlaceBrandIndex(text:String) {
    data class Brand(val id:String,val name:String,val aliases:List<String>,val include:List<JsonElement>,val exclude:List<JsonElement>,val key:String,val value:String)
    private val byName:Map<String,List<Brand>>
    init {
        require(text.length<=2*1024*1024)
        val data=JsonParser.parseString(text).asJsonObject;require(data["schema"].asInt==1)
        val rows=data.getAsJsonArray("brands");require(rows.size()<=20000)
        val brands=rows.map {raw->val row=raw.asJsonArray;require(row.size()==7)
            Brand(row[0].asString.also {require(it.matches(Regex("Q[1-9][0-9]{0,18}")))},row[1].asString,
                row[2].asJsonArray.map {it.asString},row[3].asJsonArray.toList(),row[4].asJsonArray.toList(),row[5].asString,row[6].asString)}
        byName=buildMap<String,MutableList<Brand>> {brands.forEach {b->b.aliases.distinct().filter {it.length>=3}.forEach {getOrPut(it){mutableListOf()}.add(b)}}}
    }
    private fun scope(value:JsonElement,country:String?,point:LocalPoint?):Boolean? {
        if(value.isJsonPrimitive) {
            val code=value.asString
            return if(code=="001")true else country?.equals(code,true)
        }
        val circle=value.asJsonArray;require(circle.size() in 2..3)
        return point?.let {it.distanceKm(LocalPoint(circle[1].asDouble,circle[0].asDouble))<=if(circle.size()==3)circle[2].asDouble else 25.0}
    }
    private val food=setOf("restaurant","fast_food","cafe","bar","pub","ice_cream","food_court")
    fun match(name:String,category:String,country:String?,point:LocalPoint?):Brand? {
        val candidates=byName[LocalDiscoveryMerge.businessName(name)].orEmpty()
        if(candidates.isEmpty())return null
        val classification=SearchText.normalized(LocalBusinessContact.category(category))
        val concepts=if(classification.isBlank())emptyList()else PlaceSearchVocabulary.parse(classification).concepts
        val tags=concepts.flatMap {it.tags}
        val matches=candidates.filter {b->
            b.include.any {scope(it,country,point)==true}&&b.exclude.all {scope(it,country,point)==false}&&
                (classification.isBlank()||classification==SearchText.normalized(b.value.replace('_',' '))||b.key=="amenity"&&b.value in food&&PlaceCategoryFamilies.food(classification,concepts)||tags.any {it[b.key]==b.value})
        }
        if(matches.map {it.id}.distinct().size!=1)return null
        return matches.firstOrNull()
    }
}

internal object PlaceCategoryFamilies {
    private val amenities=setOf("restaurant","fast_food","cafe","bar","pub","ice_cream","food_court")
    private val shops=setOf("beverages","alcohol","bakery","coffee","confectionery","deli","pastry","tea","food")
    fun food(name:String,concepts:List<PlaceConcept>)=name in setOf("beverage supplier","food supplier","food wholesaler")||
        concepts.any {it.cuisine||it.tags.any {tag->tag["amenity"] in amenities||tag["shop"] in shops}}
}

internal object KnownPlaceBrands {
    private val index by lazy {PlaceBrandIndex(requireNotNull(javaClass.getResourceAsStream("/place-brands.json")).bufferedReader().use {it.readText()})}
    fun match(name:String,category:String,country:String?,point:LocalPoint?)=index.match(name,category,country,point)
}
