package com.pnprojects.tarangfx.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** PRD §6.1 entities. */
@Entity(tableName = "outputs", indices = [Index("category"), Index("createdAt")])
data class OutputAudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,       // relative path within MediaStore (e.g. Music/TarangFX/)
    val contentUri: String,
    val category: String,       // OutputCategory.name
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String,
    val sampleRate: Int,
    val bitrate: Int,
    val createdAt: Long,
)

@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isBuiltIn: Boolean,
    val bandGainsJson: String,  // JSON array of 10 floats (dB)
)

@Entity(tableName = "project_drafts")
data class ProjectDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val toolType: String,
    val serializedGraphJson: String,
    val updatedAt: Long,
)

@Dao
interface OutputDao {
    @Query("SELECT * FROM outputs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OutputAudioEntity>>

    @Query("SELECT * FROM outputs ORDER BY createdAt DESC")
    suspend fun getAll(): List<OutputAudioEntity>

    @Insert
    suspend fun insert(entity: OutputAudioEntity): Long

    @Update
    suspend fun update(entity: OutputAudioEntity)

    @Query("DELETE FROM outputs WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM outputs WHERE contentUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): OutputAudioEntity?
}

@Dao
interface EqPresetDao {
    @Query("SELECT * FROM eq_presets ORDER BY name")
    fun observeAll(): Flow<List<EqPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EqPresetEntity): Long

    @Query("DELETE FROM eq_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun delete(id: Long)
}

@Dao
interface ProjectDraftDao {
    @Query("SELECT * FROM project_drafts WHERE toolType = :tool ORDER BY updatedAt DESC")
    fun observeByTool(tool: String): Flow<List<ProjectDraftEntity>>

    @Query("SELECT * FROM project_drafts WHERE id = :id")
    suspend fun get(id: Long): ProjectDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectDraftEntity): Long

    @Query("DELETE FROM project_drafts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [OutputAudioEntity::class, EqPresetEntity::class, ProjectDraftEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outputDao(): OutputDao
    abstract fun eqPresetDao(): EqPresetDao
    abstract fun projectDraftDao(): ProjectDraftDao
}
