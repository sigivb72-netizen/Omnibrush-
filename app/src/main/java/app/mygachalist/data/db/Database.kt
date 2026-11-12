package app.mygachalist.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Cached copy of one games.json entry. [json] holds the full entry so schema additions need no migration. */
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val adult: Boolean,
    val json: String,
)

@Entity(tableName = "news")
data class NewsEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val source: String,
    val lang: String,
    val published: String,
    @ColumnInfo(name = "game_ids") val gameIds: String,
)

@Entity(tableName = "list_entries")
data class ListEntryEntity(
    @PrimaryKey @ColumnInfo(name = "game_id") val gameId: String,
    val title: String,
    val status: String,
    val score: Int,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "quit_date") val quitDate: String,
    val region: String,
    val server: String,
    @ColumnInfo(name = "play_amount") val playAmount: Double,
    @ColumnInfo(name = "play_unit") val playUnit: String,
    val spent: Double,
    val currency: String,
    val notes: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Remembers which alerts were already shown so nobody is notified twice. */
@Entity(tableName = "notified")
data class NotifiedEntity(@PrimaryKey val key: String, val at: Long)

@Dao
interface GameDao {
    @Query("SELECT * FROM games")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<GameEntity>)

    @Query("DELETE FROM games")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<GameEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface NewsDao {
    @Query("SELECT * FROM news ORDER BY published DESC LIMIT 200")
    fun observeAll(): Flow<List<NewsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NewsEntity>)

    @Query("DELETE FROM news")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<NewsEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface ListDao {
    @Query("SELECT * FROM list_entries")
    fun observeAll(): Flow<List<ListEntryEntity>>

    @Query("SELECT * FROM list_entries")
    suspend fun getAll(): List<ListEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ListEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ListEntryEntity>)

    @Query("DELETE FROM list_entries WHERE game_id = :gameId")
    suspend fun delete(gameId: String)

    @Query("DELETE FROM list_entries")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<ListEntryEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface NotifiedDao {
    @Query("SELECT EXISTS(SELECT 1 FROM notified WHERE `key` = :key)")
    suspend fun has(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(item: NotifiedEntity)
}

@Database(
    entities = [GameEntity::class, NewsEntity::class, ListEntryEntity::class, NotifiedEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun news(): NewsDao
    abstract fun list(): ListDao
    abstract fun notified(): NotifiedDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mygachalist.db").build()
    }
}
