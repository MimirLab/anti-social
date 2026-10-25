/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.Post
import app.antisocial.core.AnywherePage

/** Captures only replies the reader actually obtained, never an implied complete thread. */
data class CapturedThread(val root: Post,val replies: List<Post>,val order: String="CHRONOLOGICAL",val capturedAt: Long,val hasMore: Boolean=false,val commentAnywhere: AnywherePage?=null) {
    val count get()=commentAnywhere?.flattened()?.size ?: replies.size
}
data class SelectedAsset(val key: String,val url: String,val sourceUrl: String,val type: String,val description: String="",val preview: String?=null,val postKey: String,val edition: String="current")
data class SavedCopyDetails(val token: String,val capturedAt: Long,val threads: List<CapturedThread> = emptyList(),val selectedAssets: List<SelectedAsset> = emptyList(),val fusionRoots: List<Post>?=null)
data class LibraryEntry(val key: String,val kind: String,val post: Post,val savedAt: Long,val collections: List<LibraryCollectionRow> = emptyList(),val details: SavedCopyDetails?=null,val assets: List<SavedAssetRow> = emptyList(),val matchedPost: Post?=null,val relatedMatch:Boolean=false)
