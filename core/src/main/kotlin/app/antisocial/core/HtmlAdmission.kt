/* anti social, GPL-3.0-or-later. Admission scan, never a replacement extractor. */
package app.antisocial.core

/** Linear, quote/raw-text aware scan before DOM allocation. Ordinary documents
 * still go through exactly the same Jsoup + Readability extraction. Limits
 * admit far more structure than an ordinary article and avoid pathological
 * nesting and huge tag counts. This is not an HTML sanitizer or renderer. */
object HtmlAdmission {
    private val raw=setOf("script","style","textarea","title","xmp","iframe","noembed","noframes")
    private val empty=setOf("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr")
    private val implicit=setOf("p","li","dt","dd","tr","td","th","option","optgroup","thead","tbody","tfoot","rt","rp")
    fun check(html:String) {
        if(html.length>8*1024*1024)limited()
        val stack=ArrayList<String>();var tags=0;var at=0
        while(at<html.length) {
            val start=html.indexOf('<',at);if(start<0)return
            if(html.startsWith("<!--",start)){val end=html.indexOf("-->",start+4);if(end<0)return;at=end+3;continue}
            var i=start+1;val closing=html.getOrNull(i)=='/';if(closing)i++
            val nameStart=i
            while(i<html.length&&(html[i].isLetterOrDigit()||html[i]=='-'||html[i]==':'||html[i]=='_'))i++
            if(i==nameStart||!html[nameStart].isLetter()&&html[nameStart]!='_'){at=start+1;continue}
            if(i-nameStart>512)limited()
            val name=html.substring(nameStart,i).lowercase(java.util.Locale.ROOT)
            var quote='\u0000'
            while(i<html.length) {val c=html[i++];if(quote!='\u0000'){if(c==quote)quote='\u0000'}else if(c=='\''||c=='"')quote=c else if(c=='>')break}
            at=i
            if(closing){val index=stack.lastIndexOf(name);if(index>=0)while(stack.size>index)stack.removeAt(stack.lastIndex);continue}
            tags++;if(tags>60_000)limited()
            if(name in raw){var end=html.indexOf("</$name",at,ignoreCase=true)
                while(end>=0&&html.getOrNull(end+name.length+2)?.let {it !in " \t\r\n\u000c/>"}==true)end=html.indexOf("</$name",end+2,ignoreCase=true)
                if(end<0)return;at=end;continue}
            if(name in empty||html.getOrNull(i-2)=='/')continue
            if(name in implicit){val index=stack.lastIndexOf(name);if(index>=0)while(stack.size>index)stack.removeAt(stack.lastIndex)}
            stack.add(name);if(stack.size>512)limited()
        }
    }
    private fun limited():Nothing=throw SourceFailure(HealthStatus.UNSUPPORTED,"This document exceeds the safe extraction budget")
}
