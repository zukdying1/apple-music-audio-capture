package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import android.util.Log
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
        private const val TAG = "AppleDecryptor"
        private const val DB_NAME = "appledecryptor_downloads.db"

        // Shared external path for cross-process access (rooted / permissioned devices).
        private const val EXTERNAL_DB_DIR = "/sdcard/Music/AppleDecryptor/db"

        @Volatile
        private var INSTANCE: DownloadDatabase? = null
        @Volatile
        var dbPathUsed: String = "uninitialized"
            private set

        /**
         * Opens a Room database instance.
         *
         * Strategy (fail-safe):
         * 1. Try external /sdcard/...  path (shared across processes)
         * 2. On SQLiteCantOpenDatabaseException (permission / scoped-storage),
         *    fall back to the app's internal data directory.
         *    Internal storage is per-process, so module UI and hook process
         *    will have separate databases.
         */
        fun getInstance(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context)
                    .also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): DownloadDatabase {
            val ctx = context.applicationContext

            // Strategy 1: external shared path
            val externalDb = tryExternalPath(ctx)
            if (externalDb != null) {
                dbPathUsed = externalDb.absolutePath
                Log.i(TAG, "Database on external shared path: ${externalDb.absolutePath}")
                return openDb(externalDb.absolutePath, ctx)
            }

            // Strategy 2: internal app-private storage (always works)
            val internalDir = File(ctx.filesDir, "appledecryptor_db")
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }
            val internalFile = File(internalDir, DB_NAME)
            dbPathUsed = internalFile.absolutePath
            Log.i(TAG, "Database on internal path: ${internalFile.absolutePath}")
            return openDb(internalFile.absolutePath, ctx)
        }

        private fun tryExternalPath(ctx: Context): File? {
            return try {
                val dir = File(EXTERNAL_DB_DIR)
                if (dir.exists() || dir.mkdirs()) {
                    val dbFile = File(dir, DB_NAME)
                    // Verify writable by attempting to create a test file
                    val testFile = File(dir, ".db_writable_test")
                    if (testFile.createNewFile()) {
                        testFile.delete()
                        dbFile
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "External DB path unavailable: ${e.message}")
                null
            }
        }

        private fun openDb(path: String, ctx: Context): DownloadDatabase {
            return Room.databaseBuilder(
                ctx,
                DownloadDatabase::class.java,
                path,
            )
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}