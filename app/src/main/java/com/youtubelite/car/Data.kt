package com.youtubelite.car

import android.content.Context
import androidx.room.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "Favorites")
data class Favorite(@PrimaryKey val videoId: String, val title: String, val channel: String, val thumb: String, val addedAt: Long)

@Entity(tableName = "History")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String, val title: String, val channel: String, val thumb: String, val watchedAt: Long
)

@Entity(tableName = "LocalPlaylists")
data class LocalPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "LocalPlaylistItems", primaryKeys = ["playlistId", "videoId"])
data class LocalPlaylistItem(
    val playlistId: Long, val videoId: String, val title: String, val channel: String, val thumb: String, val addedAt: Long
)

@Entity(tableName = "CachedSearchResults")
data class CachedSearch(@PrimaryKey val queryKey: String, val json: String, val ts: Long)

@Dao
interface AppDao {
    @Query("SELECT * FROM Favorites ORDER BY addedAt DESC") suspend fun favorites(): List<Favorite>
    @Query("SELECT COUNT(*) FROM Favorites WHERE videoId = :id") suspend fun isFav(id: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFav(f: Favorite)
    @Query("DELETE FROM Favorites WHERE videoId = :id") suspend fun delFav(id: String)

    @Insert suspend fun addHistory(h: HistoryItem)
    @Query("SELECT * FROM History ORDER BY watchedAt DESC LIMIT :n") suspend fun history(n: Int): List<HistoryItem>
    @Query("DELETE FROM History WHERE id = :id") suspend fun delHistory(id: Long)
    @Query("DELETE FROM History") suspend fun clearHistory()

    @Query("SELECT * FROM LocalPlaylists ORDER BY id") suspend fun playlists(): List<LocalPlaylist>
    @Insert suspend fun addPlaylist(p: LocalPlaylist): Long
    @Query("UPDATE LocalPlaylists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("DELETE FROM LocalPlaylists WHERE id = :id") suspend fun delPlaylist(id: Long)
    @Query("DELETE FROM LocalPlaylistItems WHERE playlistId = :id") suspend fun delPlaylistItems(id: Long)
    @Query("SELECT * FROM LocalPlaylistItems WHERE playlistId = :id ORDER BY addedAt") suspend fun playlistItems(id: Long): List<LocalPlaylistItem>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addPlaylistItem(i: LocalPlaylistItem)
    @Query("DELETE FROM LocalPlaylistItems WHERE playlistId = :p AND videoId = :v") suspend fun delPlaylistItem(p: Long, v: String)

    @Query("SELECT * FROM CachedSearchResults WHERE queryKey = :k") suspend fun cache(k: String): CachedSearch?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCache(c: CachedSearch)
    @Query("DELETE FROM CachedSearchResults WHERE ts < :t") suspend fun pruneCache(t: Long)
}

// عند تغيير البنية: ارفع version وأضف Migration (لا يوجد fallbackToDestructiveMigration عمداً)
@Database(
    entities = [Favorite::class, HistoryItem::class, LocalPlaylist::class, LocalPlaylistItem::class, CachedSearch::class],
    version = 1, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "ytlite.db").build().also { inst = it }
        }
    }
}

fun Favorite.toVid() = Vid(videoId, title, channel, thumb)
fun LocalPlaylistItem.toVid() = Vid(videoId, title, channel, thumb)
fun HistoryItem.toVid() = Vid(
    videoId, title, channel, thumb,
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(watchedAt)), extra = id.toString()
)
