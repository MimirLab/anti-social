/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antisocial.core.CrossPosts
import app.antisocial.core.Platform
import app.antisocial.core.PostFusion
import app.antisocial.core.Post
import app.antisocial.core.PostBodyDetails
import app.antisocial.core.PostPresentation

/** The shared passage appears once; extra source wording stays visibly attributed. */
@Composable internal fun FusedPostText(fusion: PostFusion,onOpen: (()->Unit)?=null,roots: List<Post> = emptyList(),repo: Repository?=null,showDetails: Boolean=true,networkAllowed: Boolean=true) {
    val attributions=remember(roots){roots.mapNotNull {root->PostPresentation.text(root).attribution?.let {it to root}}.distinctBy {it.first}}
    val substantiveShared=remember(fusion){fusion.segments.any {it.shared&&PostBodyDetails.split(it.text).body.any(Char::isLetter)}}
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).testTag("Fused post text"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(attributions.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            attributions.forEach {(value,root)->PostAttribution(value,repo=repo,platform=root.platform,sourceUrl=root.canonicalUrl,networkAllowed=networkAllowed)}
        }
        fusion.segments.forEachIndexed {index,segment->
            if(!showDetails&&PostBodyDetails.split(segment.text,allowStandalone=!segment.shared&&substantiveShared).body.isBlank())return@forEachIndexed
            Column {
                if(!segment.shared) {
                    val originals=roots.filter {CrossPosts.originalKey(it) in segment.originalKeys}
                    val platforms=segment.platforms.joinToString(" · "){it.label}
                    if(originals.isEmpty())Text(platforms,fontSize=11.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=4.dp))
                    else Column(Modifier.padding(bottom=4.dp).semantics(mergeDescendants=true){}) {
                        originals.distinctBy {CrossPosts.originalAccount(it) ?: CrossPosts.originalKey(it)}.forEach {root->MentionText(root.author.ifBlank {"Author not supplied"},repo,root.platform,root.canonicalUrl,accountLabel=true,networkAllowed=networkAllowed,explicitProfileLinks=root.links.orEmpty(),fontSize=11.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        Text(platforms,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val segmentRoots=roots.filter {CrossPosts.originalKey(it) in segment.originalKeys}
                val context=fusionMentionContext(segmentRoots,segment.text,repo)
                PostBodyText(segment.text,repo,context?.platform,onOpen=onOpen,tag=if(segment.shared)"Fusion shared text"else "Fusion context $index",allowStandaloneDetails=!segment.shared&&substantiveShared,showDetails=showDetails,sourceUrl=context?.sourceUrl,networkAllowed=networkAllowed,explicitProfileLinks=context?.explicitProfileLinks.orEmpty())
            }
        }
    }
}

/** Presentation only: source captions, copying and local saved records retain the full text. */
@Composable internal fun PostBodyText(value: String,repo: Repository?=null,platform: Platform?=null,onOpen: (()->Unit)?=null,modifier: Modifier=Modifier,tag: String?=null,allowStandaloneDetails: Boolean=false,showDetails: Boolean=true,sourceUrl: String?=null,networkAllowed: Boolean=true,explicitProfileLinks: List<String> = emptyList()) {
    val split=remember(value,allowStandaloneDetails){PostBodyDetails.split(value,allowStandalone=allowStandaloneDetails)}
    var expanded by rememberSaveable(value,allowStandaloneDetails){mutableStateOf(false)}
    if(split.body.isNotBlank())MentionText(split.body,repo,platform,sourceUrl,fontSize=15.sp,lineHeight=23.sp,networkAllowed=networkAllowed,explicitProfileLinks=explicitProfileLinks,onOpen=onOpen,modifier=modifier.then(if(tag==null)Modifier else Modifier.testTag(tag)))
    if(showDetails&&split.hasDetails)Column(modifier.then(if(tag==null)Modifier else Modifier.testTag("$tag references"))) {
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.testTag("${tag ?: "Post"} details toggle")) {
            Text(if(expanded)"Hide details"else if('@' in split.details||split.details.contains("http",true)||split.details.contains("www.",true))"Mentions & links"else "Details",fontSize=12.sp)
            Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.padding(start=4.dp).size(17.dp))
        }
        if(expanded)SelectionContainer {
            MentionText(split.details,repo,platform,sourceUrl,fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,networkAllowed=networkAllowed,explicitProfileLinks=explicitProfileLinks,modifier=Modifier.padding(bottom=8.dp).testTag("${tag ?: "Post"} details text"))
        }
    }
}

internal data class PostDetailSection(val value: String,val tag: String,val attribution: String?=null,val platform: Platform?=null,val sourceUrl: String?=null,val explicitProfileLinks: List<String> = emptyList(),val authors: List<Post> = emptyList())

/** Resolve a fused passage in one actual source edition. Its public name is scoped to
 * that edition's platform and origin; a matching handle elsewhere is never assumed related. */
internal data class FusionMentionContext(val platform: Platform,val sourceUrl: String,val explicitProfileLinks: List<String>)
internal fun fusionMentionContext(roots: List<Post>,text: String,repo: Repository?): FusionMentionContext? {
    if(roots.isEmpty()||repo==null)return null
    val containing=roots.filter {PostPresentation.content(it).contains(text.trim())}
    val candidates=containing+roots.filter {it !in containing}
    for(root in candidates) {
        val url=repo.publicUrl(root.canonicalUrl,root.platform)
        val links=root.links.orEmpty()
        val matches=app.antisocial.core.MentionNames.find(text,root.platform,url,repo.routes,links)
        if(matches.isEmpty())continue
        val source=app.antisocial.core.MentionNames.find(PostPresentation.content(root),root.platform,url,repo.routes,links)
        if(matches.all {match->source.any {it.token==match.token&&it.key==match.key}})return FusionMentionContext(root.platform,url,links)
    }
    return null
}

/** The card has one quiet inventory for top/bottom reference lists, link-only
 * headings and extracted attachments. All source wording remains selectable. */
internal fun postDetailSections(caption: Post,fusion: PostFusion?,roots: List<Post>,repo: Repository): List<PostDetailSection> {
    if(fusion==null) {
        val split=PostBodyDetails.split(PostPresentation.content(caption))
        return if(split.hasDetails)listOf(PostDetailSection(repo.publicText(split.details,caption.platform),"Post body ${repo.key(caption)}",platform=caption.platform,sourceUrl=caption.canonicalUrl,explicitProfileLinks=caption.links.orEmpty()))else emptyList()
    }
    val shared=fusion.segments.any {it.shared&&PostBodyDetails.split(it.text).body.any(Char::isLetter)}
    return fusion.segments.mapIndexedNotNull {index,segment->
        val split=PostBodyDetails.split(segment.text,allowStandalone=!segment.shared&&shared)
        if(!split.hasDetails)null else {
            val originals=roots.filter {CrossPosts.originalKey(it) in segment.originalKeys}
            val attribution=if(segment.shared)null else originals.distinctBy {CrossPosts.originalAccount(it) ?: CrossPosts.originalKey(it)}.joinToString(" · "){it.author.ifBlank {it.platform.label}}.ifBlank {segment.platforms.joinToString(" · "){it.label}}
            val context=fusionMentionContext(originals,segment.text,repo)
            PostDetailSection(repo.publicText(split.details,context?.platform),if(segment.shared)"Fusion shared text"else "Fusion context $index",attribution,context?.platform,context?.sourceUrl,context?.explicitProfileLinks.orEmpty(),if(segment.shared)emptyList()else originals.distinctBy {CrossPosts.originalAccount(it) ?: CrossPosts.originalKey(it)})
        }
    }
}

@Composable internal fun PostDetailsInventory(sections: List<PostDetailSection>,links: List<String>,postKey: String,onOpen: (String)->Unit,onCopy: (String)->Unit,repo: Repository?=null,networkAllowed: Boolean=true) {
    if(sections.isEmpty()&&links.isEmpty())return
    var expanded by rememberSaveable(postKey){mutableStateOf(false)}
    val toggleTag=if(links.isNotEmpty())"Post extra links $postKey"else "${sections.first().tag} details toggle"
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("Post inventory $postKey")) {
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.testTag(toggleTag)) {
            Text(if(expanded)"Hide details"else if(sections.isNotEmpty())"Mentions & links"else "${links.size} ${if(links.size==1)"link"else "links"}",fontSize=12.sp)
            Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null,Modifier.padding(start=4.dp).size(17.dp))
        }
        if(expanded) {
            sections.forEach {section->
                if(section.authors.isNotEmpty())section.authors.forEach {root->MentionText(root.author.ifBlank {root.platform.label},repo,root.platform,root.canonicalUrl,accountLabel=true,networkAllowed=networkAllowed,explicitProfileLinks=root.links.orEmpty(),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=4.dp))}
                else section.attribution?.let {PostAttribution(it,Modifier.padding(bottom=4.dp),repo,section.platform,section.sourceUrl,networkAllowed)}
                SelectionContainer {MentionText(section.value,repo,section.platform,section.sourceUrl,fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,networkAllowed=networkAllowed,explicitProfileLinks=section.explicitProfileLinks,modifier=Modifier.padding(bottom=8.dp).testTag("${section.tag} details text"))}
            }
            links.forEach {link->
                Row(Modifier.fillMaxWidth().combinedClickable(role=Role.Button,onClick={onOpen(link)},onLongClickLabel="Copy link",onLongClick={onCopy(link)}).padding(vertical=12.dp).testTag("Post link $link"),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    val account=repo?.routes?.recognize(link)?.takeIf {it.kind==app.antisocial.core.TargetKind.PROFILE}
                    if(account!=null)MentionText(account.identity,repo,account.platform,account.canonicalUrl,accountLabel=true,networkAllowed=networkAllowed,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.primary)
                    else Text(link.removePrefix("https://"),maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable internal fun PostAttribution(value: String,modifier: Modifier=Modifier,repo: Repository?=null,platform: Platform?=null,sourceUrl: String?=null,networkAllowed: Boolean=true) {
    MentionText(value,repo,platform,sourceUrl,networkAllowed=networkAllowed,fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=modifier.testTag("Post attribution"))
}
