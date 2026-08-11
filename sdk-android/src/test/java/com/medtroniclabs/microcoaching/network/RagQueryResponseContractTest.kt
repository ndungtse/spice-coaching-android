package com.medtroniclabs.microcoaching.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `/coaching/rag-query` response contract (v3) the SDK must handle:
 *  - `retrieved_modules[].title` is a nested `{bn, en}` map (not flat `title_bn`).
 *  - `suggested_questions` is a string array (contextual follow-ups → chat chips).
 *  - `source_documents[]` carries `source_pages` + `linked_module_ids`, plus extra
 *    keys (`storage_path`, `object_name`, `content_sha256`) that must be IGNORED,
 *    not crash deserialization.
 *
 * Mirrors the NetworkModule converter config (ignoreUnknownKeys + isLenient).
 */
class RagQueryResponseContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val payload = """
        {
          "answer": "ম্যালেরিয়া প্রতিরোধের ব্যবস্থা...",
          "retrieved_modules": [
            {
              "module_id": "edb08e93-9b22-4e2a-aed7-1b0ef5670075",
              "title": { "bn": "ম্যালেরিয়া প্রতিরোধ", "en": "Malaria prevention" },
              "domain": "rmnch",
              "cosine_distance": 0.124
            }
          ],
          "source_documents": [
            {
              "source_document_id": "c64bd4e1-64b1-4695-883c-0112e7c9c885",
              "title": "SK Basic Training Module 2025",
              "source_type": "pdf",
              "storage_path": "medtronics-storage/ingest/abc_SK.pdf",
              "object_name": "ingest/abc_SK.pdf",
              "original_filename": "SK Basic Training Module 2025.pdf",
              "content_sha256": "4bdb7550f55b2cba",
              "page_numbers": [146],
              "source_pages": [ { "page_number": 146, "start_ms": null, "end_ms": null } ],
              "presigned_url": "https://example.com/doc.pdf",
              "presigned_expires_seconds": 3600,
              "linked_module_ids": ["edb08e93-9b22-4e2a-aed7-1b0ef5670075"]
            }
          ],
          "model": "gemini-2.5-flash",
          "cited_module_ids": ["edb08e93-9b22-4e2a-aed7-1b0ef5670075"],
          "suggested_questions": [
            "মশার কামড় থেকে আত্মরক্ষার উপায়গুলো কী কী?",
            "ম্যালেরিয়া পরীক্ষা কীভাবে হয়?"
          ]
        }
    """.trimIndent()

    @Test
    fun `v3 rag-query response deserializes with nested title, suggestions, and ignored extras`() {
        val res = json.decodeFromString(RagQueryResponse.serializer(), payload)

        assertTrue(res.answer.isNotBlank())
        assertEquals("gemini-2.5-flash", res.model)
        assertEquals(listOf("edb08e93-9b22-4e2a-aed7-1b0ef5670075"), res.citedModuleIds)

        // Nested title resolves through the computed accessors.
        val module = res.retrievedModules.single()
        assertEquals("ম্যালেরিয়া প্রতিরোধ", module.titleBn)
        assertEquals("Malaria prevention", module.titleEn)
        assertEquals("rmnch", module.domain)

        // New follow-up questions captured (no longer silently dropped).
        assertEquals(2, res.suggestedQuestions.size)
        assertTrue(res.suggestedQuestions[0].isNotBlank())

        // Source document: consumed fields parsed; unknown keys ignored (no crash).
        val doc = res.sourceDocuments.single()
        assertEquals(146, doc.sourcePages.single().pageNumber)
        assertNull(doc.sourcePages.single().startMs)
        assertEquals(listOf(146), doc.pageNumbers)
        assertEquals(listOf("edb08e93-9b22-4e2a-aed7-1b0ef5670075"), doc.linkedModuleIds)
        assertEquals("https://example.com/doc.pdf", doc.presignedUrl)
    }

    @Test
    fun `legacy flat title_bn still resolves (back-compat)`() {
        val legacy = """
            { "answer": "x",
              "retrieved_modules": [
                { "module_id": "m1", "title_bn": "ফ্ল্যাট", "title_en": "Flat" }
              ] }
        """.trimIndent()
        val res = json.decodeFromString(RagQueryResponse.serializer(), legacy)
        assertEquals("ফ্ল্যাট", res.retrievedModules.single().titleBn)
        assertEquals("Flat", res.retrievedModules.single().titleEn)
        assertTrue(res.suggestedQuestions.isEmpty())
    }
}
