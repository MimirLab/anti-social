/* AntiSocial, GPL-3.0-or-later. Native menus; no ordering widget or browser execution. */
package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.antisocial.core.*
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RestaurantMenuSheet(business:LocalBusiness,loading:Boolean,onRefresh:(()->Unit)?,onDismiss:()->Unit,onRead:(String)->Unit) {
    val menus=business.menus.orEmpty()
    var chosen by remember(business.id){mutableStateOf<String?>(null)}
    fun key(menu:LocalMenu)=menu.sourceUrl+"|"+menu.title+"|"+menu.channel
    val menu=menus.firstOrNull {key(it)==chosen} ?: menus.firstOrNull()
    var query by remember(business.id){mutableStateOf("")}
    var otherMenus by remember(business.id){mutableStateOf(false)}
    val otherSources=LocalMenuRouting.known(business).filter {source->menus.none {it.sourceUrl==source.url}}
    val dishes=remember(menu,query){menu?.items.orEmpty().filter {query.isBlank()||LocalDiscoveryFilter.matches(query,it.name+" "+it.section+" "+it.description)}}
    LocalDetailSheet(onDismiss) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("Restaurant menu"),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(business.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                Text("Menu",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                if(business.address.isNotBlank())Text(business.address,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
            if(loading)item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reading the published menu…",style=MaterialTheme.typography.bodySmall)}
            if(menus.size>1)item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                menus.forEach {m->FilterChip(selected=m==menu,onClick={chosen=key(m)},label={Text(listOf(m.channel.takeUnless {it=="Published menu"}.orEmpty(),m.title.takeUnless {it==m.channel}.orEmpty()).filter(String::isNotBlank).joinToString(" · ").ifBlank {"Menu"})})}
            }}
            if(menu!=null) {
                item {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(listOf(menu.channel,menu.title.takeUnless {it.equals("menu",true)||it==menu.channel}.orEmpty()).filter(String::isNotBlank).joinToString(" · "),style=MaterialTheme.typography.titleSmall)
                    Text("Checked "+Instant.ofEpochMilli(menu.checkedAt).atZone(ZoneId.systemDefault()).toLocalDate()+(if(menu.partial)" · Published excerpt"else ""),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(menu.location=="Location prices unconfirmed")Text(menu.location,style=MaterialTheme.typography.bodySmall)
                }}
                if(menu.items.isNotEmpty())item {OutlinedTextField(query,{query=it.take(120)},singleLine=true,label={Text("Find a dish")},leadingIcon={Icon(Icons.Rounded.Search,null)},modifier=Modifier.fillMaxWidth().testTag("Find menu dish"))}
                dishes.forEachIndexed {index,dish->
                    if(dish.section.isNotBlank()&&(index==0||dishes[index-1].section!=dish.section))item {Text(dish.section,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=10.dp))}
                    item {
                        Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                Text(dish.name,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                                if(dish.prices.isNotEmpty())Text(dish.prices.joinToString("\n"){price->listOf(price.label,price.currency,price.amount).filter(String::isNotBlank).joinToString(" ")},style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
                            }
                            if(dish.description.isNotBlank())Text(dish.description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            val details=listOf(dish.availability.takeIf {it=="Unavailable"}.orEmpty(),dish.dietary.joinToString(" · "),dish.allergens.takeIf {it.isNotEmpty()}?.joinToString(", ",prefix="Listed allergens: ").orEmpty(),dish.options.joinToString(" · ")).filter(String::isNotBlank)
                            if(details.isNotEmpty())Text(details.joinToString("\n"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.padding(top=14.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    }
                }
                if(query.isNotBlank()&&dishes.isEmpty())item {Text("No matching dishes in this published menu.",style=MaterialTheme.typography.bodyMedium)}
                if(menu.text.isNotBlank())item {Text(menu.text,style=MaterialTheme.typography.bodyMedium)}
                item {TextButton(onClick={onRead(menu.sourceUrl)}){Text("Original menu")}}
            }else if(!loading)item {Text("A readable menu has not been found yet.",style=MaterialTheme.typography.bodyMedium)}
            if(menu==null)items(LocalMenuRouting.known(business).take(4)){source->TextButton(onClick={onRead(source.url)}){Text("Read original menu")}}
            if(menu!=null&&otherSources.isNotEmpty())item {
                Column {
                    TextButton(onClick={otherMenus=!otherMenus}){Text(if(otherMenus)"Hide other menus"else "Other published menus")}
                    if(otherMenus)otherSources.take(6).forEachIndexed {index,source->
                        val label=LocalMenuRouting.provider(source.url)?.let {"$it menu"} ?: if(source.url.substringBefore('?').endsWith(".pdf",true))"Menu document ${index+1}"else "Published menu ${index+1}"
                        TextButton(onClick={onRead(source.url)}){Icon(Icons.Rounded.Description,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(label)}
                    }
                }
            }
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onDismiss){Text("Back to business")}
                if(onRefresh!=null)TextButton(onClick=onRefresh,enabled=!loading){Text("Refresh menu")}
            }}
        }
    }
}
