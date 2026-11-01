package com.speakmylife.app.aphasia.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AcousticTemplateDao {
    @Query("SELECT * FROM acoustic_templates ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<AcousticTemplateEntity>>

    @Query("SELECT * FROM acoustic_templates")
    suspend fun getAll(): List<AcousticTemplateEntity>

    @Query("SELECT * FROM acoustic_templates WHERE id = :id")
    suspend fun getById(id: Long): AcousticTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AcousticTemplateEntity): Long

    @Update
    suspend fun update(entity: AcousticTemplateEntity)

    @Query("DELETE FROM acoustic_templates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM acoustic_templates")
    suspend fun count(): Int
}

@Dao
interface PhraseDao {
    @Query("SELECT * FROM phrases ORDER BY use_count DESC, last_used_at DESC")
    fun observeAll(): Flow<List<PhraseEntity>>

    @Query("SELECT * FROM phrases WHERE context = :context ORDER BY use_count DESC, last_used_at DESC")
    fun observeByContext(context: String): Flow<List<PhraseEntity>>

    @Query("SELECT * FROM phrases")
    suspend fun getAll(): List<PhraseEntity>

    /**
     * 只取檢索需要的欄位。整張表的文字內容在建索引時不需要載進記憶體，
     * 而語句庫可能累積到數千筆。
     */
    @Query("SELECT id, embedding, embedding_version FROM phrases WHERE embedding IS NOT NULL")
    suspend fun getEmbeddings(): List<EmbeddingRow>

    @Query("SELECT * FROM phrases WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PhraseEntity>

    /** 換編碼器之後需要重算的那些。 */
    @Query("SELECT * FROM phrases WHERE embedding IS NULL OR embedding_version != :version")
    suspend fun getNeedingEmbedding(version: Int): List<PhraseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhraseEntity): Long

    @Query("UPDATE phrases SET embedding = :blob, embedding_version = :version WHERE id = :id")
    suspend fun setEmbedding(id: Long, blob: ByteArray, version: Int)

    @Query("UPDATE phrases SET use_count = use_count + 1, last_used_at = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Query("DELETE FROM phrases WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM phrases")
    suspend fun count(): Int
}

/** [PhraseDao.getEmbeddings] 的投影結果。 */
data class EmbeddingRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "embedding") val embeddingBlob: ByteArray,
    @androidx.room.ColumnInfo(name = "embedding_version") val embeddingVersion: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EmbeddingRow && id == other.id &&
            embeddingVersion == other.embeddingVersion &&
            embeddingBlob.contentEquals(other.embeddingBlob))

    override fun hashCode(): Int = id.hashCode() * 31 + embeddingBlob.contentHashCode()
}
