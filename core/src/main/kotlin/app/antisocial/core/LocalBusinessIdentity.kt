/* anti social, GPL-3.0-or-later. Bounded batch identity preparation; matching
 * rules remain identical for prepared and individual comparisons. */
package app.antisocial.core

object LocalBusinessIdentity {
    class Prepared internal constructor(
        internal val value:LocalBusiness,
        internal val sources:Set<String>,val name:String,internal val brand:List<String>,
        internal val address:String,val street:String?,internal val number:String?,
        internal val unit:String?,internal val locality:String,internal val phones:Set<String>,val links:Set<String>
    ) {
        internal val house=number?.takeWhile(Char::isDigit)
        internal val endsInDigit=number?.lastOrNull()?.isDigit()==true
        val cells=LocalDiscoveryMerge.locationKeys(value.point)
    }
    private val unit=Regex("\\b(?:suite|ste|unit|#)\\s*([a-z0-9-]+)",RegexOption.IGNORE_CASE)
    private val number=Regex("^\\s*(\\d+[a-zA-Z]?)\\b")
    fun prepare(value:LocalBusiness):Prepared {
        val street=LocalDiscoveryMerge.street(value.address)
        val words=LocalBusinessOwnership.nameWords(value.name)
        return Prepared(value,value.sourceIds.toSet(),LocalDiscoveryMerge.businessName(words),LocalBusinessOwnership.brandWords(words),
            LocalDiscoveryIdentity.name(value.address),street,street?.substringBefore(' ') ?: number.find(value.address)?.groupValues?.get(1),
            unit.find(value.address)?.groupValues?.get(1),LocalDiscoveryIdentity.name(value.locality),value.phones.mapNotNull(LocalBusinessContact::phoneKey).toSet(),
            (value.websites+value.socials).mapNotNull(LocalDiscoveryIdentity::url).toSet())
    }
    fun same(a:LocalBusiness,b:LocalBusiness):Boolean {
        if(a.sourceIds.intersect(b.sourceIds.toSet()).isNotEmpty())return true
        return same(prepare(a),prepare(b))
    }
    fun same(a:Prepared,b:Prepared):Boolean {
        if(a.sources.any(b.sources::contains))return true
        if(a.unit!=null&&b.unit!=null&&!a.unit.equals(b.unit,true))return false
        val differentNumber=a.number!=null&&b.number!=null&&!a.number.equals(b.number,true)
        if(differentNumber&&(a.house!=b.house||a.endsInDigit==b.endsInDigit))return false
        val sameName=a.name==b.name||LocalBusinessOwnership.sameBrand(a.brand,b.brand)
        val shared=a.links.any(b.links::contains)
        // Both original positive branches require a compatible name or a
        // shared destination. Geography alone never establishes identity.
        if(!sameName&&!shared)return false
        val distance=if(a.value.point!=null&&b.value.point!=null)a.value.point.distanceKm(b.value.point)else null
        val sameAddress=a.value.address.isNotBlank()&&a.address==b.address
        var sameStreet=a.street!=null&&a.street==b.street
        val sameLocality=a.value.locality.isNotBlank()&&a.locality==b.locality
        val phone=a.phones.any(b.phones::contains)
        if(differentNumber) {
            if(!(sameName&&(distance!=null&&distance<.10||sameLocality&&phone)))return false
            if(a.street!=null&&b.street!=null&&a.street.substringAfter(' ')==b.street.substringAfter(' '))sameStreet=true
        }
        if(distance!=null&&distance>.20&&!(distance<=5.0&&sameName&&sameStreet&&sameLocality&&phone))return false
        val addressMatch=sameAddress||sameStreet&&(sameLocality||distance!=null&&distance<.15||phone)
        return sameName&&(addressMatch||distance!=null&&distance<.10)||(shared&&(addressMatch||distance!=null&&distance<.15))
    }
}
