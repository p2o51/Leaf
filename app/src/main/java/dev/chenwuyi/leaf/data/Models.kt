package dev.chenwuyi.leaf.data

import java.time.Instant

/**
 * The nine Notion highlight colours plus "none". Scribbles store it as a block's background colour
 * (`yellow_background`), essays as the 卡片颜色 select option (`yellow`), so both names map here.
 */
enum class NotionColor(val apiName: String, val label: String) {
    Default("default", "默认"),
    Gray("gray", "灰"),
    Brown("brown", "棕"),
    Orange("orange", "橙"),
    Yellow("yellow", "黄"),
    Green("green", "绿"),
    Blue("blue", "蓝"),
    Purple("purple", "紫"),
    Pink("pink", "粉"),
    Red("red", "红");

    val backgroundApiName: String get() = if (this == Default) apiName else "${apiName}_background"

    companion object {
        fun fromApi(name: String?): NotionColor =
            entries.firstOrNull { it.apiName == name || it.backgroundApiName == name } ?: Default
    }
}

/** A bundled sample artwork (demo data), an image on this device, or an image in Notion (signed URL). */
sealed interface Picture {
    data class Sample(val art: SampleArt) : Picture
    data class Local(val uri: String) : Picture
    data class Remote(val url: String) : Picture
}

enum class SampleArt { Marble, Street, Moss, Dusk, Paper }

/** One line on the scribble wall: a paragraph block on the wall page in Notion, or an image block with a caption. */
data class Scribble(
    val id: String,
    val text: String,
    val color: NotionColor,
    val createdAt: Instant,
    val photo: Picture? = null,
)

sealed interface EssayBlock {
    val id: String

    /**
     * A text block. [type] is Notion's block type (paragraph, heading_2, quote, …); Leaf edits the text
     * and keeps the type. A coloured paragraph is how 复制到随笔 writes a scribble into an essay.
     */
    data class Paragraph(
        override val id: String,
        val text: String,
        val type: String = "paragraph",
        val color: NotionColor = NotionColor.Default,
        /** Holds more in Notion than Leaf can write back (children, links, formatting), so it stays in place. */
        val fixed: Boolean = false,
    ) : EssayBlock

    /** A Notion block Leaf does not edit (table, embed, database…). Shown as a card, left untouched. */
    data class Other(override val id: String, val type: String) : EssayBlock

    /** A copy of a scribble's synced block: it always renders from the live scribble. */
    data class SyncedScribble(override val id: String, val scribbleId: String) : EssayBlock

    /** An image block; [caption] is Notion's image caption. */
    /** A Notion file, PDF, video or audio block: shown as a card that opens it; Leaf does not edit it. */
    data class Attachment(override val id: String, val type: String, val name: String, val url: String?) : EssayBlock

    data class Photo(override val id: String, val picture: Picture, val caption: String = "") : EssayBlock

    companion object {
        /** Notion's divider block; Leaf draws it as a rule and can insert one. */
        const val Divider = "divider"

        /** Notion's table of contents block; Leaf draws it as a small 目录 key that opens the outline. */
        const val TableOfContents = "table_of_contents"
    }
}

/** Every database row other than the wall. The title is the page title; blocks are the page body. */
data class Essay(
    val id: String,
    val title: String,
    val blocks: List<EssayBlock>,
    val color: NotionColor,
    val cover: Picture?,
    val pinned: Boolean,
    val editedAt: Instant,
    /** The card's text before the body is loaded from Notion. */
    val storedPreview: String = "",
    /** The row's 标签 multi-select, read from Notion; see [TagRules]. */
    val tags: List<String> = emptyList(),
    /** When the page was created; null for a row pulled before Leaf kept it, which shows [editedAt] instead. */
    val createdAt: Instant? = null,
) {
    val previewText: String
        get() = blocks.filterIsInstance<EssayBlock.Paragraph>().joinToString(" ") { it.text }.trim().ifEmpty { storedPreview }
}
