/* AntiSocial, GPL-3.0-or-later. Acquisition facts, not model confidence or truth scores. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

enum class ContextTextObservation(val wire:String) {
    POST("post_text"),COMMENT("comment_text"),PAGE("page_text"),CAPTION("caption_text"),
    IMAGE_WORDS("recognized_image_text"),IMAGE_DESCRIPTION("image_description_text"),
    AUDIO("transcribed_audio"),DOCUMENT_WORDS("recognized_document_text"),DOCUMENT("document_text"),UNKNOWN("extracted_text")
}

data class ContextEvidenceScope(val observation:ContextTextObservation,val uncertain:Boolean=false,val truncated:Boolean=false,
    val automatic:Boolean=false,val originalKind:String="",val originalMedium:String="",val timecodes:Boolean=false) {
    val significant:Boolean get()=uncertain||truncated||automatic||observation !in setOf(ContextTextObservation.POST,ContextTextObservation.COMMENT,ContextTextObservation.PAGE,ContextTextObservation.UNKNOWN)
    val identity:String get()=listOf("observation-v1",observation.wire,uncertain,truncated,automatic,originalKind,originalMedium,timecodes).joinToString("\u001f")
    fun readerDescription():String? {
        if(!significant)return null
        val description=when(observation) {
            ContextTextObservation.CAPTION->if(automatic)"Automatic caption text; visual steps need the original video."else "Caption text; visual steps need the original video."
            ContextTextObservation.AUDIO->"Speech transcript; visual details need the original media."
            ContextTextObservation.IMAGE_WORDS->"Words recognized in the image."
            ContextTextObservation.IMAGE_DESCRIPTION->"Description supplied with the image."
            ContextTextObservation.DOCUMENT_WORDS->"Words recognized on the document page."
            ContextTextObservation.DOCUMENT->"Extracted document text."
            else->"Source text."
        }
        return listOfNotNull(description,"Recognition is marked uncertain.".takeIf {uncertain},"The text extract is incomplete.".takeIf {truncated}).joinToString(" ")
    }
    companion object {
        fun from(document:ContextDocument):ContextEvidenceScope {
            val medium=document.metadata["medium"].orEmpty().filterNot(Char::isISOControl).take(160)
            val kind=document.kind.lowercase(Locale.ROOT)
            val observation=when(medium.lowercase(Locale.ROOT)) {
                "published captions","automatically generated captions"->ContextTextObservation.CAPTION
                "local image text recognition"->ContextTextObservation.IMAGE_WORDS
                "source-supplied image description"->ContextTextObservation.IMAGE_DESCRIPTION
                "local speech transcription"->ContextTextObservation.AUDIO
                "recognized document page"->ContextTextObservation.DOCUMENT_WORDS
                "document text"->ContextTextObservation.DOCUMENT
                else->when(kind) {"post"->ContextTextObservation.POST;"comment","reply"->ContextTextObservation.COMMENT;"article"->ContextTextObservation.PAGE;else->ContextTextObservation.UNKNOWN}
            }
            val automatic=medium.lowercase(Locale.ROOT) in setOf("automatically generated captions","local image text recognition","local speech transcription","recognized document page")
            return ContextEvidenceScope(observation,document.metadata["uncertain"].equals("true",true),document.metadata["truncated"].equals("true",true),automatic,kind,medium,
                document.metadata["timestamps"].equals("true",true)&&observation in setOf(ContextTextObservation.CAPTION,ContextTextObservation.AUDIO))
        }
        /** Sets cover every observation type, without presenting copy counts as
         * independent witnesses or one uncertain extract as universal doubt. */
        fun write(target:JsonObject,scopes:kotlin.collections.Collection<ContextEvidenceScope>) {
            if(scopes.none {it.significant})return
            fun kinds(test:(ContextEvidenceScope)->Boolean)=JsonArray().apply {scopes.filter(test).map {it.observation.wire}.distinct().sorted().forEach {add(it)}}
            target.add("observation_scope",JsonObject().apply {
                add("text_kinds",kinds {true})
                if(scopes.any {it.uncertain})add("uncertain_text",kinds {it.uncertain})
                if(scopes.any {it.truncated})add("incomplete_text",kinds {it.truncated})
                if(scopes.any {it.automatic})add("automatic_text",kinds {it.automatic})
                if(scopes.any {it.timecodes})add("timecoded_text",kinds {it.timecodes})
            })
        }
    }
}
