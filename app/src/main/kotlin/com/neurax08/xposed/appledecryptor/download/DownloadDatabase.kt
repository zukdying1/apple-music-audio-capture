package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "download_queue")
data class DownloadQueueItem(
    @PrimaryKey val adamId: String,
    val title: String = "",
    val artist: String = "",
    val status: String = "QUEUED", // QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELED
    val progress: Int = 0,
    val filePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String = "",
    val hlsUrl: String = "",
    val totalSegments: Int = 0,
    val completedSegments: Int = 0,
)

@Dao
interface DownloadQueueDao {
    @Query("SELECT * FROM download_queue ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<DownloadQueueItem>>

    @Query("SELECT * FROM download_queue ORDER BY createdAt DESC")
    suspend fun getAllItemsOnce(): List<DownloadQueueItem>

    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY createdAt ASC")
    fun getItemsByStatus(status: String): Flow<List<DownloadQueueItem>>

    @Query("SELECT * FROM download_queue WHERE adamId = :adamId")
    suspend fun getItem(adamId: String): DownloadQueueItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadQueueItem)

    @Update
    suspend fun update(item: DownloadQueueItem)

    @Delete
    suspend fun delete(item: DownloadQueueItem)

    @Query("DELETE FROM download_queue WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    fun countByStatus(status: String): Flow<Int>
}

@Database(entities = [DownloadQueueItem::class], version = 1, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadQueueDao(): DownloadQueueDao

    companion object {
        private const val SHARED_DB_DIR = "/sdcard/Music/AppleDecryptor/db"
        private const val SHARED_DB_NAME = "appledecryptor_downloads.db"

        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        /**
         * Open a Room DB on a shared external path so the module UI process and the
         * Apple Music hook process observe the same download queue.
         *
         * Note: SQLite multi-process is best-effort. Journal mode TRUNCATE reduces concurrent writers.
         */
        fun getInstance(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): DownloadDatabase {
            val dir = File(SHARED_DB_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val dbFile = File(dir, SHARED_DB_NAME)
            return Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                dbFile.absolutePath,
            )
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
