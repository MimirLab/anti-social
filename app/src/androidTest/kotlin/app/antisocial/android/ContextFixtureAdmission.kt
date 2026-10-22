/* AntiSocial, GPL-3.0-or-later. Explicit keep-all adapter for non-semantic fixtures. */
package app.antisocial.android

internal fun fixtureBriefAdmission(user:String):String? {
    val input=com.google.gson.JsonParser.parseString(user).asJsonObject
    val items=input.getAsJsonArray("items") ?: return null
    if(input["support_review"]?.asBoolean==true)return com.google.gson.JsonObject().apply {
        add("checks",com.google.gson.JsonArray().apply {items.forEach {item->add(com.google.gson.JsonObject().apply {
            addProperty("id",item.asJsonObject["id"].asInt);addProperty("basis","practical");addProperty("quote",item.asJsonObject["text"].asString.take(512))
        })}})
    }.toString()
    return com.google.gson.JsonObject().apply {add("reviews",com.google.gson.JsonArray().apply {
        items.forEach {item->add(com.google.gson.JsonObject().apply {addProperty("id",item.asJsonObject["id"].asInt);addProperty("on_topic",true);addProperty("adds_information",true)})}
    })}.toString()
}
