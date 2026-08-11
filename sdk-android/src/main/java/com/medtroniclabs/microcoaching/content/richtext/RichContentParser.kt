package com.medtroniclabs.microcoaching.content.richtext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Parses a TipTap / ProseMirror document (a JSON array of block nodes) into the
 * Compose-free [RichBlock] tree.
 *
 * Tolerant by design: malformed nodes are skipped, node `type`s are matched in
 * both snake_case (backend payload) and camelCase (ProseMirror canonical), and
 * unrecognised types fall through to [RichBlock.Unknown] preserving their children
 * so no authored content is silently lost.
 *
 * See `docs/v3/rich-body.json` for the shape this targets.
 */
private val lenientJson = com.medtroniclabs.microcoaching.util.LenientJson

/**
 * Parse [raw] as a TipTap block array. Returns null when [raw] is not a JSON array
 * (e.g. it's a legacy markdown string) so callers can fall back to the markdown path.
 */
internal fun parseRichBlocksOrNull(raw: String): List<RichBlock>? {
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("[")) return null
    val array = runCatching { lenientJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
    return parseBlocks(array)
}

/** Parse an already-decoded JSON array of block nodes. */
internal fun parseBlocks(array: JsonArray): List<RichBlock> =
    array.mapNotNull { parseBlock(it) }

private fun parseBlock(element: JsonElement): RichBlock? {
    val obj = element as? JsonObject ?: return null
    return when (normalizeType(obj.type)) {
        "paragraph" -> obj.inlineContent().let { inlines ->
            // Drop paragraphs that carry no visible text — the payload is littered
            // with empty {"text":""} paragraphs that would otherwise render as gaps.
            if (inlines.all { it.text.isBlank() }) null else RichBlock.Paragraph(inlines)
        }

        "heading" -> RichBlock.Heading(
            level = (obj.attrs?.get("level") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 6) ?: 1,
            inlines = obj.inlineContent(),
        )

        "bulletlist" -> RichBlock.BulletList(parseListItems(obj))

        "orderedlist" -> RichBlock.OrderedList(
            items = parseListItems(obj),
            start = (obj.attrs?.get("start") as? JsonPrimitive)?.intOrNull ?: 1,
        )

        "listitem" -> RichBlock.ListItem(parseChildBlocks(obj))

        "image" -> obj.attrs.let { a ->
            RichBlock.Image(
                src = a.stringAttr("url") ?: a.stringAttr("src"),
                objectName = a.stringAttr("object_name"),
                caption = a.stringAttr("caption"),
                alt = a.stringAttr("alt"),
                width = a.intAttr("width"),
                height = a.intAttr("height"),
            )
        }

        "video" -> obj.attrs.let { a ->
            RichBlock.Video(
                src = a.stringAttr("url") ?: a.stringAttr("src"),
                objectName = a.stringAttr("object_name"),
                caption = a.stringAttr("caption"),
            )
        }

        "blockquote" -> RichBlock.Blockquote(parseChildBlocks(obj))

        "codeblock" -> RichBlock.CodeBlock(obj.inlineContent().joinToString("") { it.text })

        "horizontalrule" -> RichBlock.HorizontalRule

        // hard_break as a standalone block carries no content — skip.
        "hardbreak" -> null

        else -> parseChildBlocks(obj).takeIf { it.isNotEmpty() }?.let { RichBlock.Unknown(it) }
    }
}

/**
 * Parse the `items` of a list. Items come in two shapes in the wild:
 *  - an array of plain strings (each becomes a single paragraph), or
 *  - an array of `{ "content": [ ...nodes ] }` / `list_item` objects.
 */
private fun parseListItems(listObj: JsonObject): List<RichBlock.ListItem> {
    val items = (listObj["items"] as? JsonArray) ?: (listObj["content"] as? JsonArray) ?: return emptyList()
    return items.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> {
                val text = item.contentOrNull.orEmpty()
                if (text.isBlank()) null
                else RichBlock.ListItem(listOf(RichBlock.Paragraph(listOf(RichInline(text)))))
            }
            is JsonObject -> {
                val blocks = parseChildBlocks(item)
                if (blocks.isEmpty()) null else RichBlock.ListItem(blocks)
            }
            else -> null
        }
    }
}

/** Parse the `content` array of a container node into child blocks. */
private fun parseChildBlocks(obj: JsonObject): List<RichBlock> =
    (obj["content"] as? JsonArray)?.mapNotNull { parseBlock(it) } ?: emptyList()

/**
 * Flatten the inline `content` of a block (paragraph/heading) into [RichInline]
 * runs, folding ProseMirror marks (bold/italic/link/…) onto each text run. A
 * `hard_break` inline becomes a newline run.
 */
private fun JsonObject.inlineContent(): List<RichInline> {
    val content = this["content"] as? JsonArray ?: return emptyList()
    val runs = mutableListOf<RichInline>()
    for (node in content) {
        val nodeObj = node as? JsonObject ?: continue
        when (normalizeType(nodeObj.type)) {
            "text" -> {
                val text = (nodeObj["text"] as? JsonPrimitive)?.contentOrNull ?: continue
                runs += applyMarks(text, nodeObj["marks"] as? JsonArray)
            }
            "hardbreak" -> runs += RichInline("\n")
            else -> {
                // Unknown inline node — pull any nested text so we don't lose it.
                nodeObj.inlineContent().takeIf { it.isNotEmpty() }?.let { runs += it }
            }
        }
    }
    return runs
}

private fun applyMarks(text: String, marks: JsonArray?): RichInline {
    var bold = false
    var italic = false
    var code = false
    var strike = false
    var underline = false
    var href: String? = null
    marks?.forEach { mark ->
        val markObj = mark as? JsonObject ?: return@forEach
        when (normalizeType(markObj.type)) {
            "bold", "strong" -> bold = true
            "italic", "em" -> italic = true
            "code" -> code = true
            "strike", "strikethrough", "s" -> strike = true
            "underline" -> underline = true
            "link" -> href = (markObj["attrs"] as? JsonObject).stringAttr("href")
        }
    }
    return RichInline(text, bold, italic, code, strike, underline, href)
}

// ── small JSON helpers ────────────────────────────────────────────────────────

private val JsonObject.type: String?
    get() = (this["type"] as? JsonPrimitive)?.contentOrNull

private val JsonObject.attrs: JsonObject?
    get() = this["attrs"] as? JsonObject

private fun JsonObject?.stringAttr(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** Positive integer attr (e.g. image `width`/`height`), else null. */
private fun JsonObject?.intAttr(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

/** Lower-cases and strips separators so "ordered_list", "orderedList" both match "orderedlist". */
private fun normalizeType(type: String?): String? =
    type?.lowercase()?.replace("_", "")?.replace("-", "")
