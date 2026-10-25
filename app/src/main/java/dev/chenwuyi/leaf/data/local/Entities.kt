package dev.chenwuyi.leaf.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A scribble. [blockId] is null until the outbox has appended it to the wall page. */
@Entity(tableName = "scribbles", indices = [Index("blockId", unique = true)])
data class ScribbleEntity(
    @PrimaryKey val id: String,
    val blockId: String?,
    val text: String,
    val color: String,
    val createdAt: Long,
    /** A photo taken on this device, copied into app storage (file:// URI) until it is uploaded. */
    val localPhoto: String? = null,
    /** Notion's signed image URL; it expires after about an hour and is refreshed on every pull. */
    val remotePhoto: String? = null,
    /** One of the bundled sample artworks (demo data only). */
    val samplePhoto: String? = null,
    val sample: Boolean = false,
)

@Entity(tableName = "essays", indices = [Index("pageId", unique = true)])
data class EssayEntity(
    @PrimaryKey val id: String,
    val pageId: String?,
    val title: String,
    val color: String,
    val pinned: Boolean,
    val editedAt: Long,
    val coverUrl: String? = null,
    val sampleCover: String? = null,
    /** First lines of the body for the card, kept even before the body itself is loaded. */
    val preview: String = "",
    val bodyLoaded: Boolean = false,
    val sample: Boolean = false,
    /** The 标签 multi-select, one tag per line (a tag never contains a newline). */
    @ColumnInfo(defaultValue = "''")
    val tags: String = "",
    /** Notion's created_time, epoch millis; null until a pull fills it in. */
    val createdAt: Long? = null,
)

/**
 * A top-level block of an essay. [type] is Notion's block type ("paragraph", "heading_2", …), or
 * "scribble" for a synced scribble that only exists in sample data so far.
 */
@Entity(tableName = "essay_blocks", indices = [Index("essayId")])
data class EssayBlockEntity(
    @PrimaryKey val id: String,
    val essayId: String,
    val position: Int,
    val blockId: String?,
    val type: String,
    val text: String,
    val color: String,
    val scribbleId: String? = null,
    /** Image blocks only: file:// for a photo still to upload, https:// for one already in Notion. */
    val photo: String? = null,
)

/** One change waiting to reach Notion. Processed strictly in [seq] order so creations precede edits. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: String,
    val targetId: String,
    val attempts: Int = 0,
)

object OutboxOp {
    const val SaveScribble = "save_scribble"
    const val DeleteScribble = "delete_scribble"
    const val SaveEssay = "save_essay"
    const val SaveBlock = "save_block"
    /** Removes an essay block from Notion; [OutboxEntity.targetId] is the Notion block ID. */
    const val DeleteBlock = "delete_block"
    /** Sets or clears an essay's cover from its row's coverUrl (file:// uploads, null clears). Target: the essay's local ID. */
    const val SaveCover = "save_cover"
    /** Moves an essay page to Notion's trash; [OutboxEntity.targetId] is the Notion page ID. */
    const val DeleteEssay = "delete_essay"
}
