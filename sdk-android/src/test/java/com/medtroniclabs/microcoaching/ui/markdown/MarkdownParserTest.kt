package com.medtroniclabs.microcoaching.ui.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the markdown parser with real lesson card bodies from the backend payload
 * and asserts that the detection helpers + AST structure match what the renderer
 * relies on (tables found where expected, nested lists detected, etc.).
 */
class MarkdownParserTest {

    private lateinit var cardsEn: List<String>
    private lateinit var cardsBn: List<String>

    @Before
    fun loadFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("modules_sync_res.json")
        requireNotNull(stream) { "modules_sync_res.json missing from test/resources" }
        val root: JsonElement = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        val cards = root.jsonObject["modules"]!!.jsonArray[0].jsonObject["cards"]!!.jsonArray
        cardsEn = cards.map { it.jsonObject["body_en"]!!.jsonPrimitive.content }
        cardsBn = cards.map { it.jsonObject["body_bn"]!!.jsonPrimitive.content }
    }

    // ── Detection helpers ────────────────────────────────────────────────────

    @Test
    fun hasTable_trueOnlyForCard3() {
        cardsEn.forEachIndexed { index, body ->
            val expected = index == 2 // 0-based: cards[2] is the comparison table
            assertEquals(
                "card $index hasTable mismatch (body=\"${body.take(40)}…\")",
                expected,
                hasTable(body),
            )
        }
    }

    @Test
    fun hasOrderedList_trueForPreventionCards() {
        // Cards 5 & 6 (0-based: indices 4 & 5) lead each step with `1.` / `2.`
        assertTrue("English card 4", hasOrderedList(cardsEn[4]))
        assertTrue("English card 5", hasOrderedList(cardsEn[5]))
        assertFalse("English card 0 (prose)", hasOrderedList(cardsEn[0]))
        assertFalse("English card 1 (bullets only)", hasOrderedList(cardsEn[1]))
        assertFalse("English card 2 (table)", hasOrderedList(cardsEn[2]))
    }

    @Test
    fun hasUnorderedList_trueForBulletCards() {
        assertTrue("card 1 has bullets", hasUnorderedList(cardsEn[1]))
        assertTrue("card 3 has bullets", hasUnorderedList(cardsEn[3]))
        assertTrue("card 4 has nested bullets", hasUnorderedList(cardsEn[4]))
        assertFalse("card 0 prose only", hasUnorderedList(cardsEn[0]))
        assertFalse("card 2 table only", hasUnorderedList(cardsEn[2]))
    }

    @Test
    fun hasNestedList_trueForCards4And5() {
        // Each numbered step in cards 4 & 5 wraps a sub-list of bullets.
        assertTrue("card 4 should be nested", hasNestedList(cardsEn[4]))
        assertTrue("card 5 should be nested", hasNestedList(cardsEn[5]))
        assertFalse("card 1 is flat list", hasNestedList(cardsEn[1]))
        assertFalse("card 0 has no list", hasNestedList(cardsEn[0]))
    }

    // ── AST structure ────────────────────────────────────────────────────────

    @Test
    fun card3Table_hasOneHeaderTwoRowsTwoColumns() {
        val parsed = MarkdownTreeBuilder.parse(cardsEn[2])
        val tables = parsed.root.collect { it.type == GFMElementTypes.TABLE }
        assertEquals("exactly one table in card 3", 1, tables.size)
        val table = tables.single()

        val headers = table.children.filter { it.type == GFMElementTypes.HEADER }
        assertEquals("exactly one header row", 1, headers.size)
        val headerCells = headers.single().children.filter { it.type == GFMTokenTypes.CELL }
        assertEquals("header has 2 cells", 2, headerCells.size)

        val rows = table.children.filter { it.type == GFMElementTypes.ROW }
        assertEquals("two body rows", 2, rows.size)
        rows.forEachIndexed { idx, row ->
            val cells = row.children.filter { it.type == GFMTokenTypes.CELL }
            assertEquals("row $idx has 2 cells", 2, cells.size)
        }
    }

    @Test
    fun card5OrderedList_containsNestedUnorderedListInFirstItem() {
        val parsed = MarkdownTreeBuilder.parse(cardsEn[4])
        val ordered = parsed.root.collect { it.type == MarkdownElementTypes.ORDERED_LIST }
            .firstOrNull()
        assertNotNull("ordered list present", ordered)
        val firstItem = ordered!!.children
            .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        assertNotNull("first list item present", firstItem)
        val nested = firstItem!!.children
            .firstOrNull { it.type == MarkdownElementTypes.UNORDERED_LIST }
        assertNotNull("first item nests an unordered list of sub-bullets", nested)
    }

    @Test
    fun bengaliBodies_parseWithoutErrors() {
        cardsBn.forEachIndexed { index, body ->
            val parsed = MarkdownTreeBuilder.parse(body)
            assertTrue(
                "Bengali card $index produced empty AST (body=\"${body.take(40)}…\")",
                parsed.root.children.isNotEmpty(),
            )
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun emptyString_producesNoBlockChildren() {
        val parsed = MarkdownTreeBuilder.parse("")
        val blocks = parsed.root.children.filter { it.isBlock() }
        assertTrue("empty input has no block-level content", blocks.isEmpty())
    }

    @Test
    fun whitespaceOnly_producesNoBlockChildren() {
        val parsed = MarkdownTreeBuilder.parse("   \n\n   \n")
        val blocks = parsed.root.children.filter { it.isBlock() }
        assertTrue("whitespace input has no block-level content", blocks.isEmpty())
    }

    @Test
    fun malformedTable_missingDivider_doesNotCrashAndProducesNoTable() {
        val malformed = """
            Heading

            | a | b |
            | c | d |

            Trailing text.
        """.trimIndent()
        // Parser must not throw; without a divider row, GFM tables are not formed.
        val parsed = MarkdownTreeBuilder.parse(malformed)
        val hasTable = parsed.root.collect { it.type == GFMElementTypes.TABLE }.isNotEmpty()
        assertFalse("no table should be detected without divider row", hasTable)
    }

    // ── HTML → Markdown normaliser ────────────────────────────────────────────

    @Test
    fun convertHtmlToMarkdown_pureMarkdown_isByteIdentical() {
        // Every card in modules_sync_res.json is pure markdown — the converter must
        // be a no-op so we don't risk regressing the existing rendering.
        cardsEn.forEachIndexed { index, body ->
            assertEquals(
                "EN card $index altered by HTML converter",
                body,
                convertHtmlToMarkdown(body),
            )
        }
        cardsBn.forEachIndexed { index, body ->
            assertEquals(
                "BN card $index altered by HTML converter",
                body,
                convertHtmlToMarkdown(body),
            )
        }
    }

    @Test
    fun convertHtmlToMarkdown_flatUnorderedList_parsesAsUnorderedList() {
        val html = "<ul><li>a</li><li>b</li></ul>"
        val parsed = MarkdownTreeBuilder.parse(html)
        val lists = parsed.root.collect { it.type == MarkdownElementTypes.UNORDERED_LIST }
        assertEquals("one unordered list", 1, lists.size)
        val items = lists.single().children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        assertEquals("two list items", 2, items.size)
    }

    @Test
    fun convertHtmlToMarkdown_nestedList_parsesAsNestedUnorderedList() {
        val html = "<ul><li>a<ul><li>aa</li></ul></li></ul>"
        val parsed = MarkdownTreeBuilder.parse(html)
        val ordered = parsed.root.collect { it.type == MarkdownElementTypes.UNORDERED_LIST }.firstOrNull()
        assertNotNull("outer unordered list present", ordered)
        val firstItem = ordered!!.children.firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        assertNotNull("first list item present", firstItem)
        val inner = firstItem!!.children.firstOrNull { it.type == MarkdownElementTypes.UNORDERED_LIST }
        assertNotNull("first item nests an inner unordered list", inner)
    }

    @Test
    fun convertHtmlToMarkdown_inlineTagsAndEntities() {
        val html = "<p>Hello <strong>world</strong>&amp; friends.<br>Bye.</p>"
        val md = convertHtmlToMarkdown(html)
        assertTrue("strong → **world**: $md", md.contains("**world**"))
        assertTrue("br → newline before Bye: $md", md.contains("\nBye."))
        assertTrue("&amp; decoded to literal &: $md", md.contains("& friends"))
        // Whole thing should parse as a paragraph with strong inline content.
        val parsed = MarkdownTreeBuilder.parse(html)
        val paragraph = parsed.root.collect { it.type == MarkdownElementTypes.PARAGRAPH }.firstOrNull()
        assertNotNull("paragraph produced", paragraph)
        val hasStrong = paragraph!!.collect { it.type == MarkdownElementTypes.STRONG }.isNotEmpty()
        assertTrue("inline STRONG element present in paragraph", hasStrong)
    }

    @Test
    fun convertHtmlToMarkdown_unknownTagsStripped_innerTextKept() {
        assertEquals("plain text", "x", convertHtmlToMarkdown("<custom-tag>x</custom-tag>"))
        assertEquals("script & style stripped entirely", "ok",
            convertHtmlToMarkdown("<script>alert('x')</script>ok<style>.a{}</style>"))
    }

    @Test
    fun convertHtmlToMarkdown_orphanCloseTag_doesNotCrash() {
        // No </li> partner — converter must degrade gracefully and not throw.
        val s = convertHtmlToMarkdown("some text </li> more text")
        assertTrue("inner text preserved: $s", s.contains("some text") && s.contains("more text"))
    }

    @Test
    fun convertHtmlToMarkdown_idempotent() {
        val html = "<ul><li>a</li><li>b<strong>!</strong></li></ul>"
        val once = convertHtmlToMarkdown(html)
        val twice = convertHtmlToMarkdown(once)
        assertEquals("running converter twice should not change the result", once, twice)
    }

    @Test
    fun fullPayload_cervicalCancerCard_renders6BulletItems() {
        // body_en for the "Preventing Cervical Cancer" card from the live backend
        // has six <li> items inside a <ul>.
        val stream = javaClass.classLoader!!.getResourceAsStream("modules_sync_res.json")
        requireNotNull(stream) { "modules_sync_res.json missing from test/resources" }
        val root = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        val htmlCard = root.jsonObject["modules"]!!.jsonArray
            .flatMap { it.jsonObject["cards"]!!.jsonArray }
            .map { it.jsonObject["body_en"]!!.jsonPrimitive.content }
            .firstOrNull { it.contains("<ul>") && it.contains("cervical exam every 3 years", ignoreCase = true) }
        requireNotNull(htmlCard) { "cervical-cancer HTML card not found in fixture" }

        val parsed = MarkdownTreeBuilder.parse(htmlCard)
        val list = parsed.root.collect { it.type == MarkdownElementTypes.UNORDERED_LIST }.firstOrNull()
        assertNotNull("HTML <ul> normalised to an UNORDERED_LIST", list)
        val items = list!!.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        assertEquals("six bullet items rendered", 6, items.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun ASTNode.collect(predicate: (ASTNode) -> Boolean): List<ASTNode> {
        val out = mutableListOf<ASTNode>()
        if (predicate(this)) out += this
        children.forEach { out += it.collect(predicate) }
        return out
    }

    private fun ASTNode.isBlock(): Boolean = when (type) {
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
        GFMElementTypes.TABLE,
        -> true
        else -> false
    }
}
