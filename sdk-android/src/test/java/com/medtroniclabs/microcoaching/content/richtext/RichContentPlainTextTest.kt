package com.medtroniclabs.microcoaching.content.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentPlainTextTest {

    @Test
    fun `extracted text contains no JSON structure or media noise`() {
        val json = """
            [
              {"type":"paragraph","content":[
                {"type":"text","text":"Signs of dehydration include dry mouth"}
              ]},
              {"type":"image","attrs":{"object_name":"media/uuid_pic.png","caption":"ChatGPT Image.png"}},
              {"type":"video","attrs":{"url":"https://x.test/v.mp4"}}
            ]
        """.trimIndent()
        val text = bodyToPlainText(json)

        assertTrue(text.contains("Signs of dehydration include dry mouth"))
        assertFalse(text.contains("object_name"))
        assertFalse(text.contains("media/"))
        assertFalse(text.contains("attrs"))
        assertFalse(text.contains("paragraph"))
        assertFalse(text.contains(".mp4"))
        assertFalse(text.contains("https"))
    }

    @Test
    fun `link emits visible text only not href`() {
        val json = """
            [{"type":"paragraph","content":[
              {"type":"text","text":"See "},
              {"type":"text","text":"the guide","marks":[{"type":"link","attrs":{"href":"https://youtube.com/watch?v=abc"}}]}
            ]}]
        """.trimIndent()
        val text = bodyToPlainText(json)
        assertTrue(text.contains("See the guide"))
        assertFalse(text.contains("youtube"))
        assertFalse(text.contains("href"))
    }

    @Test
    fun `list items are read in order`() {
        val json = """[{"type":"ordered_list","items":["First","Second","Third"]}]"""
        val text = bodyToPlainText(json)
        val first = text.indexOf("First")
        val second = text.indexOf("Second")
        val third = text.indexOf("Third")
        assertTrue(first in 0 until second && second < third)
    }

    @Test
    fun `bengali sentence terminator is respected`() {
        val json = """[{"type":"paragraph","content":[{"type":"text","text":"এই লক্ষণগুলি দেখা দিলে।"}]}]"""
        val text = bodyToPlainText(json)
        // Should not append a Latin '.' after the Bengali danda.
        assertFalse(text.contains("।."))
        assertTrue(text.endsWith("।"))
    }

    @Test
    fun `markdown string body still routed through markdown stripper`() {
        val text = bodyToPlainText("This is **bold** text")
        assertTrue(text.contains("This is bold text"))
        assertFalse(text.contains("**"))
    }

    @Test
    fun `blank body returns empty`() {
        assertEquals("", bodyToPlainText(""))
        assertEquals("", bodyToPlainText("   "))
    }
}
