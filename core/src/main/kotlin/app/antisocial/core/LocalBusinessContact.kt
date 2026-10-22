/* AntiSocial, GPL-3.0-or-later. Contact actions never accept arbitrary intent URLs. */
package app.antisocial.core

object LocalBusinessContact {
    fun phoneKey(value:String):String?=phone(value)?.filter(Char::isDigit)?.let {if(it.length==11&&it.startsWith('1'))it.drop(1)else it}
    fun displayPhone(value:String,country:String?=null):String {
        val original=value.removePrefix("tel:");val digits=phoneKey(original)
        if(digits?.length!=10||country !in setOf("US","CA")&&!original.startsWith("+1"))return original
        return (if(original.startsWith('+'))"+1 "else "")+"(${digits.take(3)}) ${digits.substring(3,6)}-${digits.takeLast(4)}"
    }
    fun phone(value:String):String? {
        if(value.length>100||value.any(Char::isISOControl))return null
        val literal=value.trim().removePrefix("tel:")
        if(!literal.matches(Regex("[+0-9() .-]+")))return null
        val digits=literal.filter(Char::isDigit)
        if(digits.length !in 7..15)return null
        return (if(literal.startsWith('+'))"+"else "")+digits
    }
    fun email(value:String):String? {
        val text=value.trim().removePrefix("mailto:").substringBefore('?')
        if(text.length !in 3..254||text.any(Char::isISOControl)||!text.matches(Regex("[A-Za-z0-9.!#$%&'*+/=_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")))return null
        return text
    }
    fun category(value:String)=value.replace(Regex("([a-z])([A-Z])"),"$1 $2").replace('_',' ').lowercase().replaceFirstChar(Char::titlecase)
}
