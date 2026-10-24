package dev.chenwuyi.leaf.ui.essay

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble

/** What the editor's 插入 menu can add. [type] is the Notion block type it becomes. */
enum class InsertKind(val label: String, val type: String) {
    Paragraph("段落", "paragraph"),
    Heading("标题", "heading_2"),
    Bullet("列表", "bulleted_list_item"),
    Numbered("编号", "numbered_list_item"),
    Quote("引用", "quote"),
    Photo("照片", "image"),
    Scribble("涂鸦", "paragraph"),
}

/** Pure block edits behind the editor, kept apart from Compose so they can be tested. */
object EditorEdits {
    /** List items continue as the same kind on Enter; everything else continues as a paragraph. */
    private val Continuing = setOf("bulleted_list_item", "numbered_list_item", "to_do")

    /** [blocks] after the block [anchorId], or at the end when there is none (nothing focused). */
    fun insertAfter(essay: Essay, anchorId: String?, blocks: List<EssayBlock>): Essay {
        val at = essay.blocks.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 }?.plus(1) ?: essay.blocks.size
        return essay.copy(blocks = essay.blocks.take(at) + blocks + essay.blocks.drop(at))
    }

    /**
     * New text for a text block. An Enter typed into it splits the block there, like Craft and Notion:
     * the result's second value is the new block to focus, or null when no split happened. Enter on an
     * empty list item ends the list instead, turning it into a paragraph.
     */
    fun typed(essay: Essay, blockId: String, text: String, newId: () -> String): Pair<Essay, String?> {
        val block = essay.blocks.firstOrNull { it.id == blockId } as? EssayBlock.Paragraph ?: return essay to null
        val enter = text.indexOf('\n')
        if (enter < 0) return essay.replace(block.copy(text = text)) to null
        val head = text.substring(0, enter)
        val tail = text.substring(enter + 1).replace("\n", "")
        if (head.isEmpty() && tail.isEmpty() && block.type in Continuing) {
            return essay.replace(block.copy(text = "", type = "paragraph")) to null
        }
        val next = EssayBlock.Paragraph(newId(), tail, if (block.type in Continuing) block.type else "paragraph")
        return insertAfter(essay.replace(block.copy(text = head)), block.id, listOf(next)) to next.id
    }

    /** A block for a picked 插入 kind; [Photo] and [Scribble] are built from what was picked instead. */
    fun blank(kind: InsertKind, newId: () -> String): EssayBlock.Paragraph = EssayBlock.Paragraph(newId(), "", kind.type)

    /**
     * A scribble put into an essay: a copy in its colour, the same as 复制到随笔 (synced blocks are M3).
     * A photo scribble brings its picture only while it is still a local file Leaf can upload.
     */
    fun fromScribble(scribble: Scribble, newId: () -> String): EssayBlock? = when (val photo = scribble.photo) {
        null -> EssayBlock.Paragraph(newId(), scribble.text, color = scribble.color)
        is Picture.Local -> EssayBlock.Photo(newId(), photo, scribble.text)
        else -> scribble.text.takeIf { it.isNotBlank() }?.let { EssayBlock.Paragraph(newId(), it, color = scribble.color) }
    }

    /** Scribbles worth offering in the picker, newest first. */
    fun pickable(scribbles: List<Scribble>): List<Scribble> =
        scribbles.filter { it.text.isNotBlank() || it.photo is Picture.Local }.sortedByDescending { it.createdAt }

    fun remove(essay: Essay, blockId: String): Essay = essay.copy(blocks = essay.blocks.filter { it.id != blockId })

    /** The block before [blockId], to move the cursor to when it is removed. */
    fun before(essay: Essay, blockId: String): String? {
        val at = essay.blocks.indexOfFirst { it.id == blockId }
        return if (at > 0) essay.blocks[at - 1].id else null
    }

    private fun Essay.replace(block: EssayBlock): Essay = copy(blocks = blocks.map { if (it.id == block.id) block else it })

    /** Equal for the editor's purposes: what Room sends back after our own write differs only in time. */
    fun sameContent(a: Essay, b: Essay): Boolean = a.copy(editedAt = b.editedAt, storedPreview = b.storedPreview) == b
}
