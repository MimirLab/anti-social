/* anti social, GPL-3.0-or-later. Read complete source panels without losing their originals. */
package app.antisocial.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.antisocial.core.ImagePanelLayout

/** All files remain individually fetched through ImageLoader and NetworkGateway. */
@Composable internal fun ImagePanelRow(layout:ImagePanelLayout,source:String,repo:Repository,modifier:Modifier,postKey:String?=null,sources:Map<String,String> = emptyMap(),maxEdge:Int=1600) {
    Row(modifier) {
        layout.images.forEach {image->
            val tag=Modifier.testTag(postKey?.let {"Post image $it ${image.url}"} ?: "Image panel ${image.url}")
            RemoteImage(image.url,sources[image.url] ?: source,repo,
                Modifier.weight(image.sourceLayout!!.width.toFloat()).fillMaxHeight().then(tag),image.description,ContentScale.Fit,maxEdge)
        }
    }
}

@Composable internal fun ImagePanelViewer(layout:ImagePanelLayout,source:String,repo:Repository,sources:Map<String,String>,onIndividuals:()->Unit,onClose:()->Unit) {
    var zoom by remember(layout){mutableFloatStateOf(1f)}
    var offset by remember(layout){mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState {scale,pan,_->zoom=(zoom*scale).coerceIn(1f,5f);offset=if(zoom==1f)Offset.Zero else offset+pan}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().testTag("Image panel viewer")) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onClose){Icon(Icons.Rounded.Close,"Close image panels",tint=Color.White)}
                Text("${layout.images.size} image panels",color=Color.White,modifier=Modifier.weight(1f))
                Text("Pinch to zoom",color=Color.LightGray,fontSize=12.sp,modifier=Modifier.padding(end=16.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("Image panel zoom viewport").semantics {stateDescription="Zoom ${(zoom*100).toInt()}%"}.transformable(transform,canPan={zoom>1f}).pointerInput(layout){detectTapGestures(onDoubleTap={zoom=if(zoom==1f)2f else 1f;offset=Offset.Zero})}) {
                val size=if(maxWidth/maxHeight>layout.aspectRatio)Modifier.height(maxHeight).width(maxHeight*layout.aspectRatio)
                    else Modifier.width(maxWidth).height(maxWidth/layout.aspectRatio)
                ImagePanelRow(layout,source,repo,size.align(Alignment.Center).drawWithContent {
                    withTransform({translate(offset.x,offset.y);scale(zoom,zoom,center)}) {this@drawWithContent.drawContent()}
                // Reuse the bounded feed decodes. Individual inspection keeps
                // the existing higher-resolution original-image viewer.
                },sources=sources,maxEdge=1600)
            }
            TextButton(onClick=onIndividuals,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Inspect individual panels",color=Color.White)}
        }
    }
}
