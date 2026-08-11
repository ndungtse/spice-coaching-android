package com.medtroniclabs.microcoaching.content.richtext

/**
 * Pure-Kotlin (Compose-free) model of a TipTap / ProseMirror card body.
 *
 * The backend ships a card `body_bn` / `body_en` as **either** a markdown string
 * (legacy) **or** a JSON array of block nodes (TipTap/ProseMirror document). This
 * model represents the parsed JSON form. It is deliberately UI-agnostic so the
 * same tree feeds both the Compose renderer ([com.medtroniclabs.microcoaching.ui.richtext])
 * and the plain-text extractor ([blocksToPlainText]) used by TTS and BM25 search.
 *
 * The parser ([parseRichBlocks]) follows ProseMirror conventions, so node types
 * not present in the current samples still degrade sensibly via [RichBlock.Unknown].
 */
sealed interface RichBlock {

    /** A paragraph of inline runs. */
    data class Paragraph(val inlines: List<RichInline>) : RichBlock

    /** A heading; [level] is 1..6 (defaults to 1 when the backend omits it). */
    data class Heading(val level: Int, val inlines: List<RichInline>) : RichBlock

    /** An unordered list. */
    data class BulletList(val items: List<ListItem>) : RichBlock

    /** An ordered list. [start] is the first item number (defaults to 1). */
    data class OrderedList(val items: List<ListItem>, val start: Int = 1) : RichBlock

    /** One list item — itself a sequence of blocks (paragraphs, nested lists, media…). */
    data class ListItem(val blocks: List<RichBlock>) : RichBlock

    /**
     * An image. Exactly one of [src] / [objectName] is expected to be present.
     *
     * [width] / [height] are the authored intrinsic pixel dimensions (TipTap
     * `attrs.width` / `attrs.height`). When **both** are present and positive the
     * renderer sizes the image to that aspect ratio (capped to the available
     * width); when either is missing it falls back to the default full-width box.
     */
    data class Image(
        val src: String? = null,
        val objectName: String? = null,
        val caption: String? = null,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : RichBlock

    /** A video. Exactly one of [src] / [objectName] is expected to be present. */
    data class Video(
        val src: String? = null,
        val objectName: String? = null,
        val caption: String? = null,
    ) : RichBlock

    /** A block quote — a sequence of nested blocks. */
    data class Blockquote(val blocks: List<RichBlock>) : RichBlock

    /** A fenced/indented code block. */
    data class CodeBlock(val text: String) : RichBlock

    /** A thematic break / horizontal rule. */
    data object HorizontalRule : RichBlock

    /**
     * Catch-all for ProseMirror node types we don't model explicitly. Children are
     * preserved so the renderer and plain-text extractor never silently drop content.
     */
    data class Unknown(val children: List<RichBlock>) : RichBlock
}

/**
 * One inline run of text with its active marks. ProseMirror represents emphasis as
 * a list of marks on a `text` node; we flatten the ones we care about onto a single
 * run so the renderer can apply spans without re-walking.
 */
data class RichInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    /** Link target when this run carries a `link` mark, else null. */
    val href: String? = null,
)
