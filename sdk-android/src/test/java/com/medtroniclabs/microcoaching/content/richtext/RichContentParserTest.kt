package com.medtroniclabs.microcoaching.content.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentParserTest {

    @Test
    fun `markdown string body is not parsed as rich blocks`() {
        assertNull(parseRichBody("Just a **markdown** string"))
        assertNull(parseRichBody(""))
        assertNull(parseRichBody("{\"type\":\"doc\"}")) // object, not array
    }

    @Test
    fun `paragraph with bold and link marks`() {
        val json = """
            [{"type":"paragraph","content":[
              {"type":"text","text":"Bold ","marks":[{"type":"bold"}]},
              {"type":"text","text":"link","marks":[{"type":"link","attrs":{"href":"https://x.test"}}]}
            ]}]
        """.trimIndent()
        val blocks = parseRichBody(json)!!
        val para = blocks.single() as RichBlock.Paragraph
        assertTrue(para.inlines[0].bold)
        assertEquals("https://x.test", para.inlines[1].href)
        assertEquals("link", para.inlines[1].text)
    }

    @Test
    fun `empty text paragraphs are dropped`() {
        val json = """[{"type":"paragraph","content":[{"type":"text","text":""}]}]"""
        assertTrue(parseRichBody(json)!!.isEmpty())
    }

    @Test
    fun `ordered list with string items`() {
        val json = """[{"type":"ordered_list","items":["First step","Second step"]}]"""
        val list = parseRichBody(json)!!.single() as RichBlock.OrderedList
        assertEquals(2, list.items.size)
        val firstPara = list.items[0].blocks.single() as RichBlock.Paragraph
        assertEquals("First step", firstPara.inlines.single().text)
    }

    @Test
    fun `bullet list with content-object items`() {
        val json = """
            [{"type":"bullet_list","items":[
              {"content":[{"type":"paragraph","content":[{"type":"text","text":"Item one"}]}]}
            ]}]
        """.trimIndent()
        val list = parseRichBody(json)!!.single() as RichBlock.BulletList
        assertEquals(1, list.items.size)
        val para = list.items[0].blocks.single() as RichBlock.Paragraph
        assertEquals("Item one", para.inlines.single().text)
    }

    @Test
    fun `image with direct url`() {
        val json = """[{"type":"image","attrs":{"url":"https://x.test/a.png","caption":"cap"}}]"""
        val image = parseRichBody(json)!!.single() as RichBlock.Image
        assertEquals("https://x.test/a.png", image.src)
        assertNull(image.objectName)
    }

    @Test
    fun `image with object_name only`() {
        val json = """[{"type":"image","attrs":{"object_name":"media/uuid_a.png","content_type":"image/png"}}]"""
        val image = parseRichBody(json)!!.single() as RichBlock.Image
        assertNull(image.src)
        assertEquals("media/uuid_a.png", image.objectName)
    }

    @Test
    fun `video node parsed with src or object_name`() {
        val byUrl = parseRichBody("""[{"type":"video","attrs":{"url":"https://x.test/v.mp4"}}]""")!!
            .single() as RichBlock.Video
        assertEquals("https://x.test/v.mp4", byUrl.src)

        val byObject = parseRichBody("""[{"type":"video","attrs":{"object_name":"media/v.mp4"}}]""")!!
            .single() as RichBlock.Video
        assertEquals("media/v.mp4", byObject.objectName)
    }

    @Test
    fun `unknown node type recurses into content`() {
        val json = """
            [{"type":"customCallout","content":[
              {"type":"paragraph","content":[{"type":"text","text":"inside"}]}
            ]}]
        """.trimIndent()
        val unknown = parseRichBody(json)!!.single() as RichBlock.Unknown
        val para = unknown.children.single() as RichBlock.Paragraph
        assertEquals("inside", para.inlines.single().text)
    }

    @Test
    fun `heading reads level from attrs`() {
        val json = """[{"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"H"}]}]"""
        val heading = parseRichBody(json)!!.single() as RichBlock.Heading
        assertEquals(3, heading.level)
    }
}
