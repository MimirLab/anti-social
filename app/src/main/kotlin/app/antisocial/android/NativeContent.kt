package app.antisocial.android

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import app.antisocial.core.*
import kotlinx.coroutines.delay

@Composable internal fun PageIntro(vm: AntiSocialViewModel) {
    val state by vm.screen.collectAsState()
    val page=state.page ?: return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        page.notice?.let {Text(vm.repo.publicText(it),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        page.profile?.let {profile->
            var subscribe by remember(profile.target){mutableStateOf(false)}
            var accountDetails by remember(profile.target){mutableStateOf(false)}
            if(accountDetails)SourceDetailsSheet(vm.repo,listOf(profile.target.canonicalUrl)+profile.links.orEmpty(),profile,onOpen={vm.openLink(it)},onDismiss={accountDetails=false})
            if(subscribe)SubscriptionDialog(vm,profile.target.platform,autoDiscover=true,initialTarget=profile.target,onDismiss={subscribe=false})
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                profile.avatar?.let {RemoteImage(it,profile.target.canonicalUrl,vm.repo,Modifier.size(72.dp).clip(CircleShape),"${profile.name} profile picture",maxEdge=256)}
                Column(Modifier.weight(1f).padding(start=16.dp)) {
                    MentionText(profile.name,vm.repo,profile.target.platform,profile.target.canonicalUrl,accountLabel=true,explicitProfileLinks=profile.links.orEmpty(),fontSize=23.sp,fontWeight=FontWeight.Bold)
                    Text(profile.target.platform.label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    profile.followers?.let {Text("$it followers",fontSize=12.sp)}
                }
            }
            if(profile.description.isNotBlank())MentionText(profile.description,vm.repo,profile.target.platform,profile.target.canonicalUrl,explicitProfileLinks=profile.links.orEmpty(),fontSize=14.sp,lineHeight=21.sp)
            if(profile.persistentId!=null||!profile.verifiedWebsites.isNullOrEmpty())TextButton(onClick={accountDetails=true},modifier=Modifier.testTag("Account details")){Text("Account details")}
            if(ReaderScope.isFeedSource(profile.target.platform))FilledTonalButton(onClick={subscribe=true},modifier=Modifier.fillMaxWidth()) {Text("Subscribe locally")}
        }
        page.collections.forEach {collection->
            Surface(onClick={vm.open(collection.target)},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    collection.image?.let {RemoteImage(it,collection.target.canonicalUrl,vm.repo,Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),collection.name,maxEdge=256)}
                    Column(Modifier.weight(1f).padding(start=if(collection.image!=null)12.dp else 0.dp)) {
                        Text(collection.name,fontWeight=FontWeight.SemiBold)
                        if(collection.detail.isNotBlank())Text(vm.repo.publicText(collection.detail,collection.target.platform),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›",fontSize=24.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable internal fun InstagramProfile(vm: AntiSocialViewModel,posts: List<Post>) {
    val state by vm.screen.collectAsState()
    val repo=vm.repo
    LazyVerticalGrid(columns=GridCells.Fixed(3),modifier=Modifier.fillMaxSize().testTag("Instagram profile grid"),contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Column(Modifier.padding(bottom=16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){MentionText(state.target?.identity.orEmpty(),repo,state.platform,state.target?.canonicalUrl,accountLabel=true,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={vm.refresh()}){Icon(Icons.Rounded.Refresh,"Refresh")}}
                PageIntro(vm)
                Text("${posts.size} public posts",Modifier.padding(top=16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(posts.distinctBy {repo.key(it)},key={repo.key(it)}) {post->
            val media=post.media.firstOrNull()
            val image=media?.let {if(it.type=="image")it.url else it.preview}
            DisposableEffect(repo.key(post)){repo.lease(repo.key(post),true);onDispose {repo.lease(repo.key(post),false)}}
            LaunchedEffect(repo.key(post)){delay(600);repo.viewed(post)}
            val tile=Modifier.aspectRatio(1f).clickable {repo.routes.recognize(post.canonicalUrl)?.let {vm.open(it)}}
            Box(tile,contentAlignment=Alignment.BottomEnd) {
                if(image!=null)RemoteImage(image,post.contentSourceUrl,repo,Modifier.fillMaxSize(),repo.publicText(post.body.ifBlank {"Public Instagram post"},post.platform),maxEdge=512)
                else MentionText(post.body,repo,post.platform,post.canonicalUrl,explicitProfileLinks=post.links.orEmpty(),modifier=Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),maxLines=5,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                if(media?.type=="video"||post.media.size>1)Text(if(media?.type=="video")"▶"else "▣",Modifier.padding(8.dp),color=Color.White,fontSize=20.sp)
            }
        }
        if(state.page?.next!=null)item(span={GridItemSpan(maxLineSpan)}) {OutlinedButton(onClick={vm.loadMore()},modifier=Modifier.fillMaxWidth()){Text("Load more")}}
    }
}

@Composable internal fun MediaViewer(media: List<Media>,initial: Int,source: String,repo: Repository,sources: Map<String,String> = emptyMap(),onClose: ()->Unit) {
    val pager=rememberPagerState(initialPage=initial.coerceIn(0,media.lastIndex)){media.size}
    val search=LocalImageSearch.current
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onClose){Icon(Icons.Rounded.Close,"Close image",tint=Color.White)}
                Text("${pager.currentPage+1} / ${media.size}",color=Color.White,modifier=Modifier.weight(1f))
                if(search!=null)IconButton(onClick={val selected=media[pager.currentPage];onClose();search(selected,sources[selected.url] ?: source)},modifier=Modifier.testTag("Search selected image")){Icon(Icons.Rounded.ImageSearch,"Search image",tint=Color.White)}
                Text("Pinch to zoom",fontSize=12.sp,color=Color.LightGray,modifier=Modifier.padding(end=16.dp))
            }
            HorizontalPager(pager,modifier=Modifier.weight(1f),key={media[it].url}) {index->
                var zoom by remember(index){mutableFloatStateOf(1f)}
                var offset by remember(index){mutableStateOf(Offset.Zero)}
                val transform=rememberTransformableState {scale,pan,_->zoom=(zoom*scale).coerceIn(1f,5f);offset=if(zoom==1f)Offset.Zero else offset+pan}
                Box(Modifier.fillMaxSize().transformable(transform,canPan={zoom>1f}).pointerInput(index){detectTapGestures(onDoubleTap={zoom=if(zoom==1f)2f else 1f;offset=Offset.Zero})}) {
                    RemoteImage(media[index].url,sources[media[index].url] ?: source,repo,Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y},repo.publicText(media[index].description),ContentScale.Fit,maxEdge=2048)
                }
            }
            media[pager.currentPage].description.takeIf {it.isNotBlank()}?.let {Text(repo.publicText(it),color=Color.White,fontSize=13.sp,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(16.dp))}
        }
    }
}
