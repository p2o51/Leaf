package dev.chenwuyi.leaf.data.local

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.notion.NotionJson
import java.time.Instant

fun ScribbleEntity.toScribble(): Scribble = Scribble(
    id = id,
    text = text,
    color = NotionColor.fromApi(color),
    createdAt = Instant.ofEpochMilli(createdAt),
    photo = when {
        localPhoto != null -> Picture.Local(localPhoto)
        remotePhoto != null -> Picture.Remote(remotePhoto)
        samplePhoto != null -> sampleArt(samplePhoto)?.let(Picture::Sample)
        else -> null
    },
)

fun Scribble.toEntity(blockId: String? = null, sample: Boolean = false): ScribbleEntity = ScribbleEntity(
    id = id,
    blockId = blockId,
    text = text,
    color = color.apiName,
    createdAt = createdAt.toEpochMilli(),
    localPhoto = (photo as? Picture.Local)?.uri,
    remotePhoto = (photo as? Picture.Remote)?.url,
    samplePhoto = (photo as? Picture.Sample)?.art?.name,
    sample = sample,
)

fun EssayEntity.toEssay(blocks: List<EssayBlockEntity>): Essay = Essay(
    id = id,
    title = title,
    blocks = blocks.sortedBy { it.position }.map { it.toBlock() },
    color = NotionColor.fromApi(color),
    // A cover picked in Leaf is a local copy (file://) until a pull brings Notion's URL back.
    cover = coverUrl?.let { if (it.startsWith("file:")) Picture.Local(it) else Picture.Remote(it) } ?: sampleCover?.let(::sampleArt)?.let(Picture::Sample),
    pinned = pinned,
    editedAt = Instant.ofEpochMilli(editedAt),
    storedPreview = preview,
    tags = tagList(tags),
    createdAt = createdAt?.let(Instant::ofEpochMilli),
)

fun tagList(stored: String): List<String> = stored.split('\n').filter { it.isNotBlank() }

fun tagColumn(tags: List<String>): String = tags.joinToString("\n")

fun EssayBlockEntity.toBlock(): EssayBlock = when {
    type == "scribble" && scribbleId != null -> EssayBlock.SyncedScribble(id, scribbleId)
    type == "image" && photo != null -> EssayBlock.Photo(id, if (photo.startsWith("http")) Picture.Remote(photo) else Picture.Local(photo), text)
    type in NotionJson.FILE_TYPES -> EssayBlock.Attachment(id, type, text, photo)
    type in NotionJson.TEXT_TYPES -> EssayBlock.Paragraph(id, text, type, NotionColor.fromApi(color), fixed)
    else -> EssayBlock.Other(id, type)
}

fun EssayBlock.toEntity(essayId: String, position: Int, blockId: String?): EssayBlockEntity = when (this) {
    is EssayBlock.Paragraph -> EssayBlockEntity(id, essayId, position, blockId, type, text, color.apiName, fixed = fixed)
    is EssayBlock.Attachment -> EssayBlockEntity(id, essayId, position, blockId, type, name, NotionColor.Default.apiName, photo = url)
    is EssayBlock.SyncedScribble -> EssayBlockEntity(id, essayId, position, blockId, "scribble", "", NotionColor.Default.apiName, scribbleId)
    is EssayBlock.Other -> EssayBlockEntity(id, essayId, position, blockId, type, "", NotionColor.Default.apiName)
    is EssayBlock.Photo -> EssayBlockEntity(
        id, essayId, position, blockId, "image", caption, NotionColor.Default.apiName,
        photo = (picture as? Picture.Local)?.uri ?: (picture as? Picture.Remote)?.url,
    )
}

private fun sampleArt(name: String): SampleArt? = SampleArt.entries.firstOrNull { it.name == name }
