/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase

/** Ordinary cache only: independent of subscriptions, saved records and their
 * schema migrations. No historical mention text or browsing log is stored. */
@Entity(tableName="mention_names",indices=[Index("lastViewedAt")])
internal data class MentionNameRow(
    @PrimaryKey val key: String,
    val platform: String,
    val authority: String,
    val username: String,
    val canonicalUrl: String,
    val displayName: String?,
    val checkedAt: Long,
    val lastViewedAt: Long,
    val viewCount: Long,
    val failures: Int,
    val retryAt: Long,
    val failurePolicy: String?,
    val bytes: Long
)

@Dao internal interface MentionNameDao {
    @Query("SELECT * FROM mention_names") suspend fun all(): List<MentionNameRow>
    @Upsert suspend fun put(rows: List<MentionNameRow>)
    @Query("DELETE FROM mention_names WHERE `key` IN (:keys)") suspend fun remove(keys: List<String>)
    @Query("DELETE FROM mention_names") suspend fun clear()
}

@Database(entities=[MentionNameRow::class],version=1,exportSchema=false)
internal abstract class MentionNameDatabase: RoomDatabase() {
    abstract fun names(): MentionNameDao
    companion object {
        fun open(context: Context,name: String="mention-names.db"): MentionNameDatabase = Room.databaseBuilder(
            context.applicationContext,MentionNameDatabase::class.java,name
        ).setJournalMode(JournalMode.TRUNCATE).addCallback(object: Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // A name lookup must not leave an ever-growing journal behind.
                // This assignment returns a row. Android 15 rejects execSQL
                // for result-bearing PRAGMAs, so consume and close its cursor.
                db.query("PRAGMA journal_size_limit=0").use {it.moveToFirst()}
            }
        }).build()
    }
}
