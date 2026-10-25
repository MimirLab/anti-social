/* anti social, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

/** The existing runtime's `[\s\p{Z}]+` -> ASCII-space contract, without a
 * matcher on each input. Android's Unicode-aware \s includes NEXT LINE while
 * desktop Java's default does not. Character.isWhitespace would also consume
 * other controls that this expression deliberately leaves intact. */
internal object TextWhitespace {
    private val nextLineSpace=Regex("\\s").matches("\u0085")
    private fun width(value:String,index:Int):Int {
        val char=value[index]
        if(char==' '||char in '\t'..'\r'||char=='\u0085'&&nextLineSpace)return 1
        if(char.code<128)return 0
        val point=Character.codePointAt(value,index)
        return when(Character.getType(point)) {
            Character.SPACE_SEPARATOR.toInt(),Character.LINE_SEPARATOR.toInt(),Character.PARAGRAPH_SEPARATOR.toInt()->Character.charCount(point)
            else->0
        }
    }
    fun collapse(value:String):String {
        var output:StringBuilder?=null
        var copied=0
        var index=0
        while(index<value.length) {
            val size=width(value,index)
            if(size==0){index++;continue}
            val start=index
            index+=size
            while(index<value.length) {
                val next=width(value,index)
                if(next==0)break
                index+=next
            }
            if(index==start+1&&value[start]==' ')continue
            val builder=output ?: StringBuilder(value.length).also {output=it}
            builder.append(value,copied,start).append(' ')
            copied=index
        }
        return output?.append(value,copied,value.length)?.toString() ?: value
    }
}
