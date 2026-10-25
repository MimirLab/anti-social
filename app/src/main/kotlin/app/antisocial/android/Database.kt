package app.antisocial.android

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow

private const val LIBRARY_SEARCH_SQL="SELECT d.* FROM local_documents d JOIN local_documents_fts ON d.rowid=local_documents_fts.rowid WHERE local_documents_fts MATCH :match AND ((:includeSaved AND d.kind='saved') OR (:includeHistory AND d.kind='history')) AND (:collectionId IS NULL OR (d.kind='saved' AND EXISTS (SELECT 1 FROM saved_collections c WHERE c.ownerKey=d.ownerKey AND c.collectionId=:collectionId))) GROUP BY d.kind,d.ownerKey ORDER BY d.sortAt DESC,d.ownerKey,d.entryKey LIMIT MIN(200,MAX(0,:limit))"
/** Only fixed SQL and parameter names enter this compiler. All words remain
 * bound MATCH arguments; original ownership/scope/collection limits are shared. */
internal fun librarySearchPaths(paths:List<String>,includeSaved:Boolean=true,includeHistory:Boolean=false,collectionId:String?=null,limit:Int=200,excluded:List<String> = emptyList()):SupportSQLiteQuery {
    require(paths.size<=8&&excluded.size<=8&&(paths+excluded).all {it.length in 1..8192})
    fun union(prefix:String,values:List<String>)=values.indices.joinToString(" UNION "){"SELECT rowid FROM local_documents_fts WHERE local_documents_fts MATCH :$prefix$it"}
    val positive=if(paths.isEmpty())"d.entryKey=d.ownerKey"else "d.rowid IN ("+union("path",paths)+")"
    val negative=if(excluded.isEmpty())""else " AND d.rowid NOT IN ("+union("excluded",excluded)+")"
    val sql=LIBRARY_SEARCH_SQL.replace(" JOIN local_documents_fts ON d.rowid=local_documents_fts.rowid","")
        .replace("local_documents_fts MATCH :match",positive+negative)
    val values=linkedMapOf<String,Any?>("includeSaved" to if(includeSaved)1 else 0,"includeHistory" to if(includeHistory)1 else 0,"collectionId" to collectionId,"limit" to limit)
    paths.forEachIndexed {i,path->values["path$i"]=path};excluded.forEachIndexed {i,path->values["excluded$i"]=path}
    val bindings=Regex(":([A-Za-z][A-Za-z0-9]*)").findAll(sql).map {it.groupValues[1]}.distinct().map {key->require(values.containsKey(key));values[key]}.toList().toTypedArray()
    return SimpleSQLiteQuery(sql,bindings)
}

@Entity(tableName="follows") data class FollowRow(@PrimaryKey val id: String,val platform: String,val payload: String)
@Entity(tableName="posts",indices=[Index("platform"),Index("publishedAt")]) data class PostRow(@PrimaryKey val key: String,val platform: String,val payload: String,val publishedAt: Long?,val createdAt: Long,val lastViewedAt: Long?=null,val bytes: Long,val followId: String?=null)
@Entity(tableName="post_follows",primaryKeys=["postKey","followId"],indices=[Index("followId")],
    foreignKeys=[ForeignKey(entity=PostRow::class,parentColumns=["key"],childColumns=["postKey"],onDelete=ForeignKey.CASCADE)])
data class PostFollowRow(val postKey: String,val followId: String)
@Entity(tableName="bookmarks") data class BookmarkRow(@PrimaryKey val key: String,val platform: String,val payload: String,val savedAt: Long)
@Entity(tableName="saved_details",foreignKeys=[ForeignKey(entity=BookmarkRow::class,parentColumns=["key"],childColumns=["key"],onDelete=ForeignKey.CASCADE)])
data class SavedDetailRow(@PrimaryKey val key: String,val payload: String)
@Entity(tableName="library_collections",indices=[Index(value=["name"],unique=true)])
data class LibraryCollectionRow(@PrimaryKey val id: String,@ColumnInfo(collate=ColumnInfo.NOCASE) val name: String,val createdAt: Long)
@Entity(tableName="saved_collections",primaryKeys=["ownerKey","collectionId"],indices=[Index("ownerKey"),Index("collectionId")],foreignKeys=[
    ForeignKey(entity=BookmarkRow::class,parentColumns=["key"],childColumns=["ownerKey"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=LibraryCollectionRow::class,parentColumns=["id"],childColumns=["collectionId"],onDelete=ForeignKey.CASCADE)])
data class SavedCollectionRow(val ownerKey: String,val collectionId: String)
@Entity(tableName="saved_assets",primaryKeys=["ownerKey","assetKey"],indices=[Index("ownerKey"),Index("status"),Index("type")],foreignKeys=[
    ForeignKey(entity=BookmarkRow::class,parentColumns=["key"],childColumns=["ownerKey"],onDelete=ForeignKey.CASCADE)])
data class SavedAssetRow(val ownerKey: String,val assetKey: String,val token: String,val url: String,val sourceUrl: String,val type: String,
    val status: String,val bytes: Long=0,val error: String?=null,val updatedAt: Long)
@Entity(tableName="retained_history",indices=[Index("viewedAt")])
data class RetainedHistoryRow(@PrimaryKey val key: String,val platform: String,val payload: String,val viewedAt: Long,val bytes: Long)
data class RetainedHistoryUsage(val key: String,val viewedAt: Long,val bytes: Long)
@Entity(tableName="local_documents",indices=[Index(value=["kind","ownerKey","entryKey"],unique=true),Index("ownerKey"),Index("kind"),Index("sortAt")])
data class LocalDocumentRow(@PrimaryKey(autoGenerate=true) val rowid: Long=0,val ownerKey: String,val entryKey: String,val kind: String,val platform: String,
    val title: String,val body: String,val author: String,val url: String,val sortAt: Long)
@Fts4(contentEntity=LocalDocumentRow::class,tokenizer="unicode61")
@Entity(tableName="local_documents_fts")
data class LocalDocumentFts(@PrimaryKey val rowid: Long,val title: String,val body: String,val author: String,val url: String)
@Entity(tableName="health") data class HealthRow(@PrimaryKey val key: String,val payload: String,val checkedAt: Long)
@Entity(tableName="media_cache") data class MediaRow(@PrimaryKey val key: String,val path: String,val bytes: Long,val createdAt: Long,val lastViewedAt: Long?=null,val saved: Boolean=false)
@Entity(tableName="history") data class HistoryRow(@PrimaryKey val key: String,val platform: String,val url: String,val title: String,val viewedAt: Long)
@Entity(tableName="seen") data class SeenRow(@PrimaryKey val key: String,val platform: String,val firstSeen: Long,val purged: Boolean=false)
data class PostCacheRow(val key: String,val bytes: Long,val createdAt: Long,val lastViewedAt: Long?)

@Dao interface AntiSocialDao {
    @Query("SELECT * FROM follows ORDER BY platform,id") fun follows(): Flow<List<FollowRow>>
    @Query("SELECT * FROM follows") suspend fun allFollows(): List<FollowRow>
    @Upsert suspend fun putFollow(row: FollowRow)
    @Transaction suspend fun removeFollow(id: String) {removePostFollows(id);clearLegacyFollow(id);deleteFollowRow(id)}
    @Query("DELETE FROM follows WHERE id=:id") suspend fun deleteFollowRow(id: String)
    @Query("DELETE FROM post_follows WHERE followId=:id") suspend fun removePostFollows(id: String)
    @Query("UPDATE posts SET followId=NULL WHERE followId=:id") suspend fun clearLegacyFollow(id: String)
    @Query("SELECT * FROM posts WHERE platform=:platform ORDER BY publishedAt IS NULL,publishedAt DESC,createdAt DESC LIMIT 1000") fun posts(platform: String): Flow<List<PostRow>>
    @Query("SELECT * FROM posts WHERE platform=:platform AND EXISTS (SELECT 1 FROM post_follows WHERE postKey=posts.`key` AND followId IN (:ids)) ORDER BY publishedAt IS NULL,publishedAt DESC,createdAt DESC LIMIT 1000") fun followedPosts(platform: String,ids: List<String>): Flow<List<PostRow>>
    @Query("SELECT * FROM posts WHERE EXISTS (SELECT 1 FROM post_follows WHERE postKey=posts.`key` AND followId IN (:ids)) ORDER BY publishedAt IS NULL,publishedAt DESC,createdAt DESC LIMIT 1000") fun followedPosts(ids: List<String>): Flow<List<PostRow>>
    @Query("SELECT * FROM posts WHERE platform=:platform ORDER BY publishedAt DESC LIMIT 1000") suspend fun platformPosts(platform: String): List<PostRow>
    @Query("SELECT * FROM posts") suspend fun allPosts(): List<PostRow>
    @Query("SELECT * FROM posts ORDER BY createdAt DESC LIMIT 256") suspend fun filterPreviewPosts(): List<PostRow>
    @Query("SELECT `key`,bytes,createdAt,lastViewedAt FROM posts") suspend fun postCacheRows(): List<PostCacheRow>
    @Query("SELECT COALESCE(SUM(bytes),0) FROM posts") suspend fun postBytes(): Long
    @Query("SELECT * FROM posts WHERE `key`=:key") suspend fun post(key: String): PostRow?
    @Query("SELECT * FROM posts WHERE `key` IN (:keys)") suspend fun postsByKeys(keys: List<String>): List<PostRow>
    @Query("SELECT * FROM post_follows WHERE postKey IN (:keys)") fun postMemberships(keys: List<String>): Flow<List<PostFollowRow>>
    @Transaction suspend fun putPost(row: PostRow) {upsertPost(row);row.followId?.let {putPostFollow(PostFollowRow(row.key,it))}}
    @Upsert suspend fun upsertPost(row: PostRow)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putPostFollow(row: PostFollowRow)
    @Query("DELETE FROM posts WHERE `key` IN (:keys)") suspend fun removePosts(keys: List<String>)
    @Query("UPDATE posts SET lastViewedAt=:time WHERE `key`=:key") suspend fun touchPost(key: String,time: Long)
    @Query("SELECT * FROM bookmarks ORDER BY savedAt DESC") fun bookmarks(): Flow<List<BookmarkRow>>
    @Query("SELECT `key` FROM bookmarks ORDER BY savedAt DESC") fun bookmarkKeys(): Flow<List<String>>
    @Query("SELECT `key` FROM bookmarks WHERE `key` > :afterKey AND LENGTH(CAST(`key` AS BLOB)) <= :maxKeyLength ORDER BY `key` LIMIT :limit") suspend fun bookmarkKeysAfter(afterKey: String,limit: Int,maxKeyLength: Int): List<String>
    @Query("SELECT `key` FROM bookmarks WHERE `key` <= :throughKey AND LENGTH(CAST(`key` AS BLOB)) <= :maxKeyLength ORDER BY `key` LIMIT :limit") suspend fun bookmarkKeysThrough(throughKey: String,limit: Int,maxKeyLength: Int): List<String>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<BookmarkRow>
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(payload AS BLOB))),0) FROM bookmarks") suspend fun bookmarkBytes(): Long
    @Query("SELECT * FROM bookmarks WHERE `key` IN (:keys)") suspend fun bookmarksByKeys(keys: List<String>): List<BookmarkRow>
    @Upsert suspend fun putBookmark(row: BookmarkRow)
    @Query("DELETE FROM bookmarks WHERE `key`=:key") suspend fun removeBookmark(key: String)
    @Query("SELECT * FROM saved_details") fun savedDetails(): Flow<List<SavedDetailRow>>
    @Query("SELECT * FROM saved_details WHERE `key`=:key") suspend fun savedDetail(key: String): SavedDetailRow?
    @Upsert suspend fun putSavedDetail(row: SavedDetailRow)
    @Query("SELECT * FROM saved_details") suspend fun allSavedDetails(): List<SavedDetailRow>
    @Query("SELECT `key` FROM saved_details") suspend fun savedDetailKeys(): List<String>
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(payload AS BLOB))),0) FROM saved_details") suspend fun savedDetailBytes(): Long
    @Query("SELECT * FROM library_collections ORDER BY name COLLATE NOCASE,id") fun collections(): Flow<List<LibraryCollectionRow>>
    @Query("SELECT * FROM library_collections ORDER BY name COLLATE NOCASE,id") suspend fun allCollections(): List<LibraryCollectionRow>
    @Query("SELECT * FROM library_collections WHERE id=:id") suspend fun collection(id: String): LibraryCollectionRow?
    @Query("SELECT * FROM library_collections WHERE name=:name COLLATE NOCASE") suspend fun collectionNamed(name: String): LibraryCollectionRow?
    @Upsert suspend fun putCollection(row: LibraryCollectionRow)
    @Query("DELETE FROM library_collections WHERE id=:id") suspend fun removeCollection(id: String)
    @Query("SELECT * FROM saved_collections") fun collectionLinks(): Flow<List<SavedCollectionRow>>
    @Query("SELECT * FROM saved_collections") suspend fun allCollectionLinks(): List<SavedCollectionRow>
    @Transaction suspend fun setCollectionLinks(owner: String,ids: List<String>) {deleteCollectionLinks(owner);ids.distinct().forEach {putCollectionLink(SavedCollectionRow(owner,it))}}
    @Query("DELETE FROM saved_collections WHERE ownerKey=:owner") suspend fun deleteCollectionLinks(owner: String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putCollectionLink(row: SavedCollectionRow)
    @Query("SELECT * FROM saved_assets WHERE ownerKey=:owner ORDER BY updatedAt,assetKey") fun assets(owner: String): Flow<List<SavedAssetRow>>
    @Query("SELECT * FROM saved_assets") suspend fun allSavedAssets(): List<SavedAssetRow>
    @Query("SELECT * FROM saved_assets WHERE ownerKey IN (:owners)") suspend fun savedAssetsByOwners(owners: List<String>): List<SavedAssetRow>
    @Query("SELECT EXISTS(SELECT 1 FROM saved_assets WHERE ownerKey=:owner AND token=:token)") suspend fun hasAssetToken(owner: String,token: String): Boolean
    @Query("SELECT * FROM saved_assets WHERE ownerKey=:owner AND assetKey=:assetKey") suspend fun asset(owner: String,assetKey: String): SavedAssetRow?
    @Upsert suspend fun putSavedAsset(row: SavedAssetRow)
    @Query("UPDATE saved_assets SET status=:status,bytes=:bytes,error=:error,updatedAt=:updatedAt WHERE ownerKey=:owner AND assetKey=:assetKey AND token=:token")
    suspend fun updateSavedAsset(owner: String,assetKey: String,token: String,status: String,bytes: Long,error: String?,updatedAt: Long): Int
    @Query("DELETE FROM saved_assets WHERE ownerKey=:owner") suspend fun deleteSavedAssets(owner: String)
    @Query("SELECT * FROM retained_history WHERE `key`=:key") suspend fun retainedHistory(key: String): RetainedHistoryRow?
    @Query("SELECT * FROM retained_history ORDER BY viewedAt DESC,`key`") suspend fun allRetainedHistory(): List<RetainedHistoryRow>
    @Query("SELECT * FROM retained_history WHERE `key` IN (:keys)") suspend fun retainedHistoryByKeys(keys: List<String>): List<RetainedHistoryRow>
    @Query("SELECT `key`,viewedAt,bytes FROM retained_history ORDER BY viewedAt DESC,`key`") suspend fun retainedHistoryUsage(): List<RetainedHistoryUsage>
    @Upsert suspend fun putRetainedHistory(row: RetainedHistoryRow)
    @Query("DELETE FROM retained_history WHERE viewedAt<:before") suspend fun pruneRetainedHistory(before: Long)
    @Transaction suspend fun removeRetainedHistory(keys: List<String>) {keys.chunked(400).forEach {deleteRetainedHistoryRows(it)}}
    @Query("DELETE FROM retained_history WHERE `key` IN (:keys)") suspend fun deleteRetainedHistoryRows(keys: List<String>)
    @Query("DELETE FROM retained_history") suspend fun clearRetainedHistory()
    @Transaction suspend fun putDocument(row: LocalDocumentRow) {
        val existing=document(row.ownerKey,row.kind,row.entryKey)
        upsertDocument(row.copy(rowid=existing?.rowid ?: 0))
    }
    @Upsert suspend fun upsertDocument(row: LocalDocumentRow)
    @Query("SELECT * FROM local_documents WHERE ownerKey=:owner AND kind=:kind AND entryKey=:entry") suspend fun document(owner: String,kind: String,entry: String): LocalDocumentRow?
    @Query("SELECT * FROM local_documents WHERE ownerKey=:owner AND kind=:kind ORDER BY sortAt DESC,entryKey") suspend fun documents(owner: String,kind: String): List<LocalDocumentRow>
    @Query("SELECT * FROM local_documents") suspend fun allDocuments(): List<LocalDocumentRow>
    @Query("SELECT * FROM local_documents WHERE kind=:kind") suspend fun documentsOfKind(kind: String): List<LocalDocumentRow>
    @Query("SELECT DISTINCT ownerKey FROM local_documents WHERE kind=:kind AND entryKey=ownerKey") suspend fun documentOwners(kind: String): List<String>
    @Query("DELETE FROM local_documents WHERE (kind='saved' AND NOT EXISTS (SELECT 1 FROM bookmarks b WHERE b.`key`=local_documents.ownerKey)) OR (kind='history' AND NOT EXISTS (SELECT 1 FROM retained_history h WHERE h.`key`=local_documents.ownerKey))") suspend fun pruneOrphanDocuments()
    @Query("DELETE FROM local_documents WHERE ownerKey=:owner AND kind=:kind") suspend fun deleteDocuments(owner: String,kind: String)
    @Query("DELETE FROM local_documents WHERE kind=:kind") suspend fun clearDocumentKind(kind: String)
    @Query(LIBRARY_SEARCH_SQL)
    fun searchDocuments(match: String,includeSaved: Boolean=true,includeHistory: Boolean=false,collectionId: String?=null,limit: Int=200): Flow<List<LocalDocumentRow>>
    @RawQuery(observedEntities=[LocalDocumentRow::class,LocalDocumentFts::class,SavedCollectionRow::class,BookmarkRow::class,RetainedHistoryRow::class])
    fun searchDocumentPaths(query:SupportSQLiteQuery):Flow<List<LocalDocumentRow>>
    @Query("SELECT d.* FROM local_documents d WHERE d.entryKey=d.ownerKey AND ((:includeSaved AND d.kind='saved') OR (:includeHistory AND d.kind='history')) AND (:collectionId IS NULL OR (d.kind='saved' AND EXISTS (SELECT 1 FROM saved_collections c WHERE c.ownerKey=d.ownerKey AND c.collectionId=:collectionId))) ORDER BY d.sortAt DESC,d.ownerKey,d.entryKey LIMIT MIN(200,MAX(0,:limit))")
    fun browseDocuments(includeSaved: Boolean=true,includeHistory: Boolean=false,collectionId: String?=null,limit: Int=200): Flow<List<LocalDocumentRow>>
    @Query("SELECT * FROM health") suspend fun allHealth(): List<HealthRow>
    @Upsert suspend fun putHealth(row: HealthRow)
    @Query("SELECT * FROM media_cache") suspend fun allMedia(): List<MediaRow>
    @Query("SELECT COALESCE(SUM(bytes),0) FROM media_cache WHERE saved=:saved") suspend fun mediaBytes(saved: Boolean): Long
    @Query("SELECT * FROM media_cache WHERE `key`=:key") suspend fun media(key: String): MediaRow?
    @Upsert suspend fun putMedia(row: MediaRow)
    @Transaction suspend fun removeMedia(keys: List<String>) {keys.chunked(400).forEach {deleteMediaRows(it)}}
    @Query("DELETE FROM media_cache WHERE `key` IN (:keys)") suspend fun deleteMediaRows(keys: List<String>)
    @Upsert suspend fun putHistory(row: HistoryRow)
    @Query("SELECT * FROM history ORDER BY viewedAt DESC LIMIT 500") fun history(): Flow<List<HistoryRow>>
    @Query("SELECT * FROM history") suspend fun allHistory(): List<HistoryRow>
    @Query("SELECT * FROM seen WHERE `key`=:key") suspend fun seen(key: String): SeenRow?
    @Upsert suspend fun putSeen(row: SeenRow)
    @Query("UPDATE seen SET purged=1 WHERE `key` IN (:keys)") suspend fun markPurged(keys: List<String>)
    @Query("DELETE FROM seen WHERE firstSeen<:before") suspend fun pruneSeen(before: Long)
    @Query("DELETE FROM history WHERE viewedAt<:before") suspend fun pruneHistory(before: Long)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM health WHERE checkedAt<:before") suspend fun pruneHealth(before: Long)
}
@Database(entities=[FollowRow::class,PostRow::class,PostFollowRow::class,BookmarkRow::class,SavedDetailRow::class,LibraryCollectionRow::class,SavedCollectionRow::class,SavedAssetRow::class,RetainedHistoryRow::class,LocalDocumentRow::class,LocalDocumentFts::class,HealthRow::class,MediaRow::class,HistoryRow::class,SeenRow::class],version=3,exportSchema=false)
abstract class AntiSocialDatabase: RoomDatabase() {
    abstract fun dao(): AntiSocialDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `post_follows` (`postKey` TEXT NOT NULL, `followId` TEXT NOT NULL, PRIMARY KEY(`postKey`, `followId`), FOREIGN KEY(`postKey`) REFERENCES `posts`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_follows_followId` ON `post_follows` (`followId`)")
                db.execSQL("INSERT OR IGNORE INTO `post_follows` (`postKey`, `followId`) SELECT posts.`key`, posts.followId FROM posts INNER JOIN follows ON follows.id=posts.followId WHERE posts.followId IS NOT NULL")
            }
        }
        val MIGRATION_2_3=object: Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_details` (`key` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`key`), FOREIGN KEY(`key`) REFERENCES `bookmarks`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `library_collections` (`id` TEXT NOT NULL, `name` TEXT COLLATE NOCASE NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_library_collections_name` ON `library_collections` (`name`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_collections` (`ownerKey` TEXT NOT NULL, `collectionId` TEXT NOT NULL, PRIMARY KEY(`ownerKey`, `collectionId`), FOREIGN KEY(`ownerKey`) REFERENCES `bookmarks`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`collectionId`) REFERENCES `library_collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_collections_ownerKey` ON `saved_collections` (`ownerKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_collections_collectionId` ON `saved_collections` (`collectionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_assets` (`ownerKey` TEXT NOT NULL, `assetKey` TEXT NOT NULL, `token` TEXT NOT NULL, `url` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `error` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerKey`, `assetKey`), FOREIGN KEY(`ownerKey`) REFERENCES `bookmarks`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_assets_ownerKey` ON `saved_assets` (`ownerKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_assets_status` ON `saved_assets` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_assets_type` ON `saved_assets` (`type`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `retained_history` (`key` TEXT NOT NULL, `platform` TEXT NOT NULL, `payload` TEXT NOT NULL, `viewedAt` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_retained_history_viewedAt` ON `retained_history` (`viewedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `local_documents` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ownerKey` TEXT NOT NULL, `entryKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `platform` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `author` TEXT NOT NULL, `url` TEXT NOT NULL, `sortAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_documents_kind_ownerKey_entryKey` ON `local_documents` (`kind`, `ownerKey`, `entryKey`)")
                for(column in listOf("ownerKey","kind","sortAt"))db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_documents_${column}` ON `local_documents` (`${column}`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `local_documents_fts` USING FTS4(`title` TEXT NOT NULL, `body` TEXT NOT NULL, `author` TEXT NOT NULL, `url` TEXT NOT NULL, content=`local_documents`, tokenize=unicode61)")
            }
        }
    }
}
