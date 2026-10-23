package dev.chenwuyi.leaf.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LeafDao {
    @Query("SELECT * FROM scribbles")
    fun scribbles(): Flow<List<ScribbleEntity>>

    @Query("SELECT * FROM scribbles")
    suspend fun allScribbles(): List<ScribbleEntity>

    @Query("SELECT * FROM scribbles WHERE id = :id")
    suspend fun scribble(id: String): ScribbleEntity?

    @Query("SELECT * FROM scribbles WHERE id IN (:ids)")
    suspend fun scribbles(ids: Collection<String>): List<ScribbleEntity>

    @Upsert
    suspend fun upsertScribbles(items: List<ScribbleEntity>)

    @Query("DELETE FROM scribbles WHERE id IN (:ids)")
    suspend fun deleteScribbles(ids: Collection<String>)

    @Query("SELECT * FROM essays")
    fun essays(): Flow<List<EssayEntity>>

    @Query("SELECT * FROM essays")
    suspend fun allEssays(): List<EssayEntity>

    @Query("SELECT * FROM essays WHERE id = :id")
    suspend fun essay(id: String): EssayEntity?

    @Upsert
    suspend fun upsertEssays(items: List<EssayEntity>)

    @Query("DELETE FROM essays WHERE id IN (:ids)")
    suspend fun deleteEssays(ids: Collection<String>)

    @Query("SELECT * FROM essay_blocks ORDER BY essayId, position")
    fun blocks(): Flow<List<EssayBlockEntity>>

    @Query("SELECT * FROM essay_blocks WHERE essayId = :essayId ORDER BY position")
    suspend fun blocksOf(essayId: String): List<EssayBlockEntity>

    @Query("SELECT * FROM essay_blocks WHERE id = :id")
    suspend fun block(id: String): EssayBlockEntity?

    @Upsert
    suspend fun upsertBlocks(items: List<EssayBlockEntity>)

    @Query("DELETE FROM essay_blocks WHERE essayId IN (:essayIds)")
    suspend fun deleteBlocksOf(essayIds: Collection<String>)

    @Transaction
    suspend fun replaceBlocks(essayId: String, items: List<EssayBlockEntity>) {
        deleteBlocksOf(listOf(essayId))
        upsertBlocks(items)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT 1")
    suspend fun nextOp(): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun pendingOps(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun done(seq: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq = :seq")
    suspend fun failedOnce(seq: Long)

    @Query("DELETE FROM outbox")
    suspend fun clearOutbox()

    @Query("DELETE FROM scribbles WHERE sample = 1")
    suspend fun deleteSampleScribbles()

    @Query("DELETE FROM essay_blocks WHERE essayId IN (SELECT id FROM essays WHERE sample = 1)")
    suspend fun deleteSampleBlocks()

    @Query("DELETE FROM essays WHERE sample = 1")
    suspend fun deleteSampleEssays()

    @Transaction
    suspend fun clearSamples() {
        deleteSampleScribbles()
        deleteSampleBlocks()
        deleteSampleEssays()
    }

    @Query("DELETE FROM scribbles")
    suspend fun deleteAllScribbles()

    @Query("DELETE FROM essays")
    suspend fun deleteAllEssays()

    @Query("DELETE FROM essay_blocks")
    suspend fun deleteAllBlocks()

    @Transaction
    suspend fun clearAll() {
        deleteAllScribbles()
        deleteAllBlocks()
        deleteAllEssays()
        clearOutbox()
    }
}
