/* anti social, GPL-3.0-or-later. Geography is an admission rule, not a ranking hint. */
package app.antisocial.core

import java.util.Locale

object LocalGeography {
    private val countries by lazy {
        buildMap<String,String> {
            Locale.getISOCountries().forEach {code->
                val locale=Locale.forLanguageTag("und-$code")
                listOf(code,locale.getISO3Country(),locale.getDisplayCountry(Locale.ENGLISH)).forEach {put(LocalDiscoveryIdentity.name(it),code)}
            }
            mapOf("uk" to "GB","united states of america" to "US","unitedstates" to "US","great britain" to "GB").forEach {put(it.key,it.value)}
        }
    }
    private val states="AL:Alabama;AK:Alaska;AZ:Arizona;AR:Arkansas;CA:California;CO:Colorado;CT:Connecticut;DE:Delaware;FL:Florida;GA:Georgia;HI:Hawaii;ID:Idaho;IL:Illinois;IN:Indiana;IA:Iowa;KS:Kansas;KY:Kentucky;LA:Louisiana;ME:Maine;MD:Maryland;MA:Massachusetts;MI:Michigan;MN:Minnesota;MS:Mississippi;MO:Missouri;MT:Montana;NE:Nebraska;NV:Nevada;NH:New Hampshire;NJ:New Jersey;NM:New Mexico;NY:New York;NC:North Carolina;ND:North Dakota;OH:Ohio;OK:Oklahoma;OR:Oregon;PA:Pennsylvania;RI:Rhode Island;SC:South Carolina;SD:South Dakota;TN:Tennessee;TX:Texas;UT:Utah;VT:Vermont;VA:Virginia;WA:Washington;WV:West Virginia;WI:Wisconsin;WY:Wyoming;DC:District of Columbia".split(';').flatMap {entry->val (code,name)=entry.split(':');listOf(LocalDiscoveryIdentity.name(code) to code,LocalDiscoveryIdentity.name(name) to code)}.toMap()
    fun country(value:String):String?=countries[LocalDiscoveryIdentity.name(value)]
    fun country(area:LocalArea):String?=area.countryCode?.let(::country) ?: area.label.split(',').lastOrNull()?.let(::country)

    /** Explicit conflicting country/state evidence defeats a same-named town.
     * Missing metadata remains unknown; it never supplies coordinates. */
    fun compatible(area:LocalArea,address:String,evidence:List<LocalFieldEvidence> = emptyList()):Boolean {
        val expected=country(area) ?: return true
        fun observed(field:String)=evidence.filter {it.field==field}.maxByOrNull {it.checkedAt}?.value?.takeIf(String::isNotBlank)
        val parts=address.split(Regex("[,;\\n]")).map(String::trim).filter(String::isNotBlank)
        val declared=observed("country")
        val actual=declared?.let(::country) ?: parts.lastOrNull()?.takeUnless {it.length==2&&it.uppercase() in states.values}?.let(::country)
        if(actual!=null&&actual!=expected)return false
        if(declared!=null&&actual==null)return false
        if(expected=="US") {
            val region=area.label.split(',').map {states[LocalDiscoveryIdentity.name(it)]}.firstOrNull {it!=null}
            val stated=observed("region")?.let {states[LocalDiscoveryIdentity.name(it)]}
                ?:parts.drop(1).mapNotNull {part->states[LocalDiscoveryIdentity.name(part)] ?: Regex("^([A-Z]{2})(?:\\s+\\d{5}(?:-\\d{4})?)?$").matchEntire(part)?.groupValues?.get(1)?.takeIf {it in states.values}}.lastOrNull()
            if(region!=null&&stated!=null&&region!=stated)return false
        }
        return true
    }
    fun inside(area:LocalArea,point:LocalPoint?,locality:String,address:String,evidence:List<LocalFieldEvidence> = emptyList()):Boolean =
        compatible(area,address,evidence)&&(point?.let {it.valid()&&area.point.distanceKm(it)<=area.radiusKm} ?: LocalDiscoveryFilter.locality(area,locality))
}
