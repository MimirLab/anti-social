/* anti social, GPL-3.0-or-later. Format references: Stable Diffusion Prompt Reader (MIT).
 * https://github.com/receyuki/stable-diffusion-prompt-reader ; independently bounded parser. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

object GenerationRecipes {
    const val MAX_TEXT=128*1024
    fun parse(fields:Map<String,String>):List<GenerationRecipe> = buildList {
        fields["parameters"]?.let {a1111(it)?.let(::add)}
        fields["prompt"]?.let {comfy(it)?.let(::add)}
        if(fields["Software"]=="NovelAI")novel(fields)?.let(::add)
        fields["invokeai_metadata"]?.let {value->obj(value)?.let {o->
            if(o.string("positive_prompt")!=null&&o.number("seed")!=null&&o.number("steps")!=null&&o.string("scheduler")!=null)
                add(GenerationRecipe("InvokeAI",AiUse.UNSPECIFIED,o.string("positive_prompt"),o.string("negative_prompt"),
                    o.getAsJsonObjectSafe("model")?.string("name"),params(o,"seed","steps","scheduler","cfg_scale")))
        }}
        // Easy Diffusion's multi-field PNG record, not arbitrary caption prose.
        if(fields["prompt"]!=null&&fields["seed"]?.toLongOrNull()!=null&&fields["steps"]?.toIntOrNull() in 1..10000&&fields["sampler_name"]!=null)
            add(GenerationRecipe("Easy Diffusion",AiUse.UNSPECIFIED,clean(fields["prompt"],3000),clean(fields["negative_prompt"],1500),
                clean(fields["stable_diffusion_model"],160),fields.filterKeys {it in setOf("seed","steps","sampler_name","guidance_scale")}.mapValues {clean(it.value,160).orEmpty()}))
    }.distinct().take(8)

    internal fun novel(fields:Map<String,String>,carrier:String="File metadata"):GenerationRecipe? {
        val c=fields["Comment"]?.let(::obj) ?: return null
        if(c.number("seed")==null||c.number("steps")==null||c.string("sampler")==null||c.number("scale")==null)return null
        val prompt=c.string("prompt") ?: clean(fields["Description"],3000) ?: return null
        return GenerationRecipe("NovelAI",AiUse.UNSPECIFIED,prompt,c.string("uc"),clean(fields["Source"],160),
            params(c,"seed","steps","sampler","scale"),if(c.string("signed_hash")!=null)RecipeSignature.UNCHECKED else RecipeSignature.UNSIGNED,carrier)
    }
    private fun a1111(raw:String):GenerationRecipe? {
        if(raw.length>MAX_TEXT)return null
        val match=Regex("(?m)^Steps: ([0-9]{1,5}), (.+)$").find(raw) ?: return null
        val steps=match.groupValues[1].toIntOrNull()?.takeIf {it in 1..10000} ?: return null
        val settings=("Steps: $steps, "+match.groupValues[2]).split(Regex(", (?=[A-Za-z][A-Za-z0-9 _-]{0,40}: )"))
            .take(32).mapNotNull {p->val i=p.indexOf(": ");if(i<1)null else p.take(i) to p.substring(i+2)}.toMap()
        if(settings["Seed"]?.toLongOrNull()==null||settings["CFG scale"]?.toDoubleOrNull()?.isFinite()!=true||
            settings["Sampler"].isNullOrBlank()||settings["Size"]?.matches(Regex("[0-9]{1,5}x[0-9]{1,5}"))!=true)return null
        val prose=raw.take(match.range.first).trim();if(prose.isBlank())return null
        return GenerationRecipe("Stable Diffusion WebUI",AiUse.UNSPECIFIED,clean(prose.substringBefore("\nNegative prompt:"),3000),
            prose.substringAfter("\nNegative prompt:","").let {clean(it,1500)},clean(settings["Model"],160),
            settings.filterKeys {it in setOf("Steps","Sampler","CFG scale","Seed","Size","Model hash","Denoising strength")}.mapValues {clean(it.value,160).orEmpty()})
    }
    private fun comfy(raw:String):GenerationRecipe? {
        val graph=obj(raw) ?: return null
        if(graph.size() !in 3..256)return null
        val nodes=graph.entrySet().mapNotNull {(id,v)->v.takeIf {it.isJsonObject}?.asJsonObject?.let {id to it}}.toMap()
        val pending=ArrayDeque(nodes.filterValues {it.string("class_type") in setOf("SaveImage","PreviewImage")}.keys)
        val reached=linkedSetOf<String>()
        while(pending.isNotEmpty()&&reached.size<256) {
            val id=pending.removeFirst();if(!reached.add(id))continue
            nodes[id]?.getAsJsonObjectSafe("inputs")?.entrySet()?.take(64)?.forEach {(_,v)->
                if(v.isJsonArray&&v.asJsonArray.size()==2) v.asJsonArray[0].takeIf {it.isJsonPrimitive}?.asString?.takeIf {nodes.containsKey(it)}?.let(pending::add)
            }
        }
        val used=reached.mapNotNull(nodes::get)
        val sample=used.firstOrNull {it.string("class_type") in setOf("KSampler","KSamplerAdvanced")}?.getAsJsonObjectSafe("inputs") ?: return null
        if(sample.number("steps")==null||sample.string("sampler_name")==null)return null
        val model=used.firstOrNull {it.string("class_type") in setOf("CheckpointLoaderSimple","CheckpointLoader")}?.getAsJsonObjectSafe("inputs")?.string("ckpt_name")
        fun textInput(name:String):String? {
            val link=sample[name]?.takeIf {it.isJsonArray&&it.asJsonArray.size()==2}?.asJsonArray?.get(0)?.takeIf {it.isJsonPrimitive}?.asString ?: return null
            val node=nodes[link]?.takeIf {it.string("class_type")=="CLIPTextEncode"} ?: return null
            return node.getAsJsonObjectSafe("inputs")?.string("text")
        }
        val prompt=textInput("positive") ?: return null
        return GenerationRecipe("ComfyUI",AiUse.UNSPECIFIED,prompt,textInput("negative"),model=model,
            parameters=params(sample,"seed","noise_seed","steps","sampler_name","scheduler","cfg","denoise"))
    }
    /** Preflight depth/token budgets before Gson builds an untrusted tree. */
    internal fun obj(raw:String):JsonObject?=runCatching {
        require(raw.length<=MAX_TEXT)
        JsonReader(raw.reader()).use {r->var depth=0;var tokens=0
            while(r.peek()!=JsonToken.END_DOCUMENT) {
                require(++tokens<=12000)
                when(r.peek()) {
                    JsonToken.BEGIN_OBJECT->{require(++depth<=16);r.beginObject()}
                    JsonToken.BEGIN_ARRAY->{require(++depth<=16);r.beginArray()}
                    JsonToken.END_OBJECT->{r.endObject();depth--}
                    JsonToken.END_ARRAY->{r.endArray();depth--}
                    JsonToken.NAME->r.nextName()
                    JsonToken.STRING,JsonToken.NUMBER->r.nextString()
                    JsonToken.BOOLEAN->r.nextBoolean()
                    JsonToken.NULL->r.nextNull()
                    else->error("Invalid recipe")
                }
            };require(depth==0)
        }
        JsonParser.parseString(raw).takeIf {it.isJsonObject}?.asJsonObject
    }.getOrNull()
    internal fun JsonObject.string(key:String):String?=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString?.let {clean(it,3000)}
    private fun JsonObject.number(key:String):String?=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString?.takeIf {it.length<=80&&it.toDoubleOrNull()?.isFinite()==true}
    private fun JsonObject.getAsJsonObjectSafe(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun params(o:JsonObject,vararg names:String)=names.mapNotNull {k->(o.string(k) ?: o.number(k))?.let {k to it.take(160)}}.toMap()
    private fun clean(s:String?,n:Int)=AiMetadataValues.text(s,n)
}
