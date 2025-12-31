package dev.abdus.apps.shimmer.database

import android.content.Context
import android.net.Uri
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
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

class UriConverters {
    @TypeConverter
    fun fromString(value: String?): Uri? {
        return value?.let { Uri.parse(it) }
    }

    @TypeConverter
    fun toString(uri: Uri?): String? {
        return uri?.toString()
    }
}

@Entity(
    tableName = "folders",
    indices = [Index(value = ["uri"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: Uri,
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
    val uri: Uri,
    val lastShownAt: String? = null,
    val createdAt: String? = null,
    val favoriteRank: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val fileSize: Long? = null
)

data class FolderMetadata(
    val folderId: Long,
    val folderUri: Uri,
    val isEnabled: Boolean,
    val imageCount: Int,
    val thumbnailUri: Uri?
)

data class ImageEntry(
    val uri: Uri,
    val width: Int?,
    val height: Int?
)

data class ImageUriAndSize(
    val uri: Uri,
    val fileSize: Long?
)

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("SELECT id FROM folders WHERE uri = :uri LIMIT 1")
    suspend fun getFolderId(uri: Uri): Long?

    @Query("SELECT lastScannedAt FROM folders WHERE id = :id")
    suspend fun getFolderLastScanned(id: Long): String?

    @Query("UPDATE folders SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateFolderEnabled(id: Long, isEnabled: Boolean)

    @Query("UPDATE folders SET lastScannedAt = :lastScannedAt WHERE id = :id")
    suspend fun updateFolderLastScanned(id: Long, lastScannedAt: String)

    @Query("UPDATE folders SET lastPickedAt = :isoDate WHERE id = :id")
    suspend fun updateFolderLastPicked(id: Long, isoDate: String)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolderByUri(uri: Uri)

    @Query("SELECT * FROM folders")
    fun getAllFoldersFlow(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<ImageEntity>)

    @Query("DELETE FROM images WHERE folderId = :folderId AND uri NOT IN (:validUris)")
    suspend fun deleteInvalidImages(folderId: Long, validUris: List<Uri>)

    @Query("DELETE FROM images WHERE uri = :uri")
    suspend fun deleteImageByUri(uri: Uri)

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
    suspend fun updateLastShownByUri(uri: Uri, isoDate: String)

    @Query("UPDATE images SET favoriteRank = favoriteRank + 1 WHERE uri = :uri")
    suspend fun incrementFavoriteRank(uri: Uri)

    @Query("SELECT * FROM images WHERE uri = :uri LIMIT 1")
    suspend fun getImageByUri(uri: Uri): ImageEntity?

    @Query("""
        SELECT COUNT(*) > 0 FROM images i 
        INNER JOIN folders f ON i.folderId = f.id 
        WHERE i.uri = :uri AND f.isEnabled = 1
    """)
    suspend fun isImageManagedAndEnabled(uri: Uri): Boolean

    @Query("SELECT uri, fileSize FROM images WHERE folderId = :folderId")
    suspend fun getImageUrisAndSizesForFolder(folderId: Long): List<ImageUriAndSize>

    /**
     * Finds the next image in the round-robin cycle across all enabled folders.
     * 1. Prioritizes folders that haven't been picked from recently.
     * 2. Within a folder, prioritizes images that have never been shown.
     * 3. Picks randomly among the top candidates.
     */
    @Query("""
        SELECT images.* 
        FROM images 
        JOIN folders ON images.folderId = folders.id
        WHERE folders.isEnabled = 1
        ORDER BY 
            folders.lastPickedAt ASC,
            (images.lastShownAt IS NOT NULL) ASC,
            RANDOM()
        LIMIT 1
    """)
    suspend fun findNextCycleImage(): ImageEntity?

    @Query("SELECT COUNT(*) FROM folders WHERE isEnabled = 1")
    fun getEnabledFoldersCountFlow(): Flow<Int>

    @Query("""
        SELECT 
            f.id AS folderId,
            f.uri AS folderUri, 
            f.isEnabled AS isEnabled,
            (SELECT COUNT(*) FROM images WHERE folderId = f.id) AS imageCount,
            (SELECT uri FROM images WHERE folderId = f.id ORDER BY id DESC LIMIT 1) AS thumbnailUri
        FROM folders f;
""")
    fun getFoldersMetadataFlow(): Flow<List<FolderMetadata>>
}

@Database(entities = [FolderEntity::class, ImageEntity::class], version = 3, exportSchema = false)
@TypeConverters(UriConverters::class)
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