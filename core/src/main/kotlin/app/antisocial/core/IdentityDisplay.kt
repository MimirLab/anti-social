/* anti social, GPL-3.0-or-later.
 * Small UTS #39 mixed-script observation subset: Unicode 18.0.0 confusables.txt.
 * Data © 2026 Unicode, Inc.; Unicode-3.0 license in third_party/UNICODE-LICENSE.txt.
 * Source https://unicode.org/Public/security/latest/confusables.txt
 * SHA256: 6ed3ee967c9dfdf6677d563c9985182fbc50a2efb7d6059cd57b2e2ce18f5b92
 * This observes lookalikes, not ownership, legitimacy or authenticity.
 */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object IdentityDisplay {
    private val lookalikes=mapOf(
        '\u03b1' to "a",
        '\u0430' to "a",
        '\u0391' to "A",
        '\u0410' to "A",
        '\u04d5' to "ae",
        '\u04d4' to "AE",
        '\u042c' to "b",
        '\u0392' to "B",
        '\u0412' to "B",
        '\u042b' to "bl",
        '\u03f2' to "c",
        '\u0441' to "c",
        '\u1c83' to "c",
        '\u03f9' to "C",
        '\u0421' to "C",
        '\u0501' to "d",
        '\u0435' to "e",
        '\u04bd' to "e",
        '\u0395' to "E",
        '\u0415' to "E",
        '\u03dc' to "F",
        '\u050c' to "G",
        '\u04bb' to "h",
        '\u04ba' to "h",
        '\u0397' to "H",
        '\u041d' to "H",
        '\u03b9' to "i",
        '\u0456' to "i",
        '\ua647' to "i",
        '\u03f3' to "j",
        '\u0458' to "j",
        '\u037f' to "J",
        '\u0408' to "J",
        '\u039a' to "K",
        '\u041a' to "K",
        '\u0399' to "l",
        '\u0406' to "l",
        '\u04cf' to "l",
        '\u04c0' to "l",
        '\u042e' to "lO",
        '\u039c' to "M",
        '\u03fa' to "M",
        '\u041c' to "M",
        '\u039d' to "N",
        '\u03bf' to "o",
        '\u03c3' to "o",
        '\u043e' to "o",
        '\u1c82' to "o",
        '\u039f' to "O",
        '\u041e' to "O",
        '\ua699' to "oo",
        '\ua698' to "OO",
        '\u0479' to "oy",
        '\u0478' to "Oy",
        '\u03c1' to "p",
        '\u03f1' to "p",
        '\u03f8' to "p",
        '\u0440' to "p",
        '\u03a1' to "P",
        '\u0420' to "P",
        '\u051b' to "q",
        '\u051a' to "Q",
        '\u1d26' to "r",
        '\u0433' to "r",
        '\u0455' to "s",
        '\u0405' to "S",
        '\u03a4' to "T",
        '\u0422' to "T",
        '\u03c5' to "u",
        '\u03bd' to "v",
        '\u0475' to "v",
        '\u0474' to "V",
        '\u0461' to "w",
        '\u0448' to "w",
        '\u051d' to "w",
        '\u051c' to "W",
        '\u0445' to "x",
        '\u03a7' to "X",
        '\u0425' to "X",
        '\u03b3' to "y",
        '\u0443' to "y",
        '\u04af' to "y",
        '\u03a5' to "Y",
        '\u03d2' to "Y",
        '\u0423' to "Y",
        '\u04ae' to "Y",
        '\u0396' to "Z"
    )
    /** Show directional overrides as visible replacement marks. Normal RTL,
     * combining marks, joiners and emoji stay intact; stored text is untouched.
     * One UTF-16 code unit stays one code unit, preserving mention hit ranges. */
    fun controls(value:String)=if(value.any {it in '\u202a'..'\u202e'})value.map {if(it in '\u202a'..'\u202e')'\ufffd'else it}.joinToString("")else value
    fun notice(value:String):String? {
        if(value.any {it in '\u202a'..'\u202e'})return "This name contains directional formatting controls. The original spelling remains in the source record."
        if(value.any {it in 'a'..'z'||it in 'A'..'Z'}&&value.any {it in lookalikes})return "This name mixes Latin letters with Greek or Cyrillic lookalikes. Check the linked account address."
        return null
    }
    /** OkHttp's IDNA host is ASCII. Separate domain labels always expose that
     * spelling, including punycode; they never inherit publisher directionality. */
    fun domain(value:String)=value.toHttpUrlOrNull()?.host ?: controls(value)
    fun destination(value:String)=value.toHttpUrlOrNull()?.toString()?.let(::controls) ?: controls(value)
}
