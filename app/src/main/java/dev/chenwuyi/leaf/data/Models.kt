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

/** A picture is either one of the bundled sample artworks (demo data) or an image the user picked. */
sealed interface Picture {
    data class Sample(val art: SampleArt) : Picture
    data class Local(val uri: String) : Picture
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

    data class Paragraph(override val id: String, val text: String) : EssayBlock

    /** A copy of a scribble's synced block: it always renders from the live scribble. */
    data class SyncedScribble(override val id: String, val scribbleId: String) : EssayBlock
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
) {
    val previewText: String
        get() = blocks.filterIsInstance<EssayBlock.Paragraph>().joinToString(" ") { it.text }.trim()
}
