package dev.abdus.apps.shimmer.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "folders",
    indices = [Index(value = ["uri"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val isEnabled: Boolean = true,
    val lastScannedAt: String? = null,
    val createdAt: String? = null,
    val lastPickedAt: String? = null
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId"), Index("lastShownAt"), Index(value = ["uri"], unique = true)]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val uri: String,
    val lastShownAt: String? = null,
    val createdAt: String? = null,
    val favoriteRank: Int = 0,
    val width: Int? = null,
    val height: Int? = null
)

data class FolderMetadata(
    val folderId: Long,
    val folderUri: String,
    val isEnabled: Boolean,
    val imageCount: Int,
    val firstImageUri: String?
)

data class ImageEntry(
    val uri: String,
    val width: Int?,
    val height: Int?
)

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("SELECT id FROM folders WHERE uri = :uri LIMIT 1")
    suspend fun getFolderId(uri: String): Long?

    @Query("SELECT lastScannedAt FROM folders WHERE id = :id")
    suspend fun getFolderLastScanned(id: Long): String?

    @Query("UPDATE folders SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateFolderEnabled(id: Long, isEnabled: Boolean)

    @Query("UPDATE folders SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateFolderLastScanned(id: Long, lastScannedAt: String)

    @Query("UPDATE folders SET lastPickedAt = :isoDate WHERE id = :id")
    suspend fun updateFolderLastPicked(id: Long, isoDate: String)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolderByUri(uri: String)

    @Query("SELECT * FROM folders")
    fun getAllFoldersFlow(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<ImageEntity>)

    @Query("DELETE FROM images WHERE folderId = :folderId AND uri NOT IN (:validUris)")
    suspend fun deleteInvalidImages(folderId: Long, validUris: List<String>)

    /**
     * Finds the next folder in the round-robin cycle.
     */
    @Query("""
        SELECT * FROM folders
        WHERE isEnabled = 1
        ORDER BY lastPickedAt ASC, id ASC
        LIMIT 1
    """)
    suspend fun getNextRoundRobinFolder(): FolderEntity?

    /**
     * Picks the next image from a specific folder.
     */
    @Query("""
        SELECT * FROM images 
        WHERE folderId = :folderId 
        ORDER BY lastShownAt ASC, favoriteRank DESC, RANDOM() 
        LIMIT 1
    """)
    suspend fun getNextImageFromFolder(folderId: Long): ImageEntity?

    @Query("SELECT * FROM images ORDER BY lastShownAt DESC LIMIT 1")
    suspend fun getLatestShownImage(): ImageEntity?

    @Query("SELECT * FROM images ORDER BY lastShownAt DESC LIMIT 1")
    fun getLatestShownImageFlow(): Flow<ImageEntity?>

    @Query("SELECT uri, width, height FROM images WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun getImagesForFolderFlow(folderId: Long): Flow<List<ImageEntry>>

    @Query("UPDATE images SET lastShownAt = :isoDate WHERE id = :id")
    suspend fun updateLastShown(id: Long, isoDate: String)

    @Query("UPDATE images SET lastShownAt = :isoDate WHERE uri = :uri")
    suspend fun updateLastShownByUri(uri: String, isoDate: String)

    @Query("UPDATE images SET favoriteRank = favoriteRank + 1 WHERE uri = :uri")
    suspend fun incrementFavoriteRank(uri: String)

    @Query("""
        SELECT COUNT(*) > 0 FROM images i 
        INNER JOIN folders f ON i.folderId = f.id 
        WHERE i.uri = :uri AND f.isEnabled = 1
    """)
    suspend fun isImageManagedAndEnabled(uri: String): Boolean

    @Query("SELECT COUNT(*) FROM folders WHERE isEnabled = 1")
    fun getEnabledFoldersCountFlow(): Flow<Int>

    @Query("""
        SELECT 
            f.id as folderId,
            f.uri as folderUri, 
            f.isEnabled as isEnabled,
            COUNT(i.id) as imageCount, 
            MIN(i.uri) as firstImageUri 
        FROM folders f 
        LEFT JOIN images i ON f.id = i.folderId 
        GROUP BY f.id
    """)
    fun getFoldersMetadataFlow(): Flow<List<FolderMetadata>>
}

@Database(entities = [FolderEntity::class, ImageEntity::class], version = 2, exportSchema = false)
abstract class ShimmerDatabase : RoomDatabase() {
    abstract fun imageDao(): ImageDao

    companion object {
        @Volatile
        private var INSTANCE: ShimmerDatabase? = null

        fun getInstance(context: Context): ShimmerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ShimmerDatabase::class.java,
                    "shimmer_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}