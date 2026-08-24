package com.medtroniclabs.microcoaching.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Pins the source-document endpoint's path and its required `since` parameter.
 *
 * The backend consolidated several device endpoints into this one, and the paths
 * it replaced return 404 rather than an error the SDK can interpret. `since` is
 * mandatory server-side — omitting it is a 422 — so both the path and the
 * parameter are locked here.
 */
class SyncEndpointPathContractTest {

    private val getSourceDocuments = CoachingApiService::class.java.getDeclaredMethod(
        "getSourceDocuments",
        String::class.java,
        kotlin.coroutines.Continuation::class.java,
    )

    @Test
    fun `source documents endpoint is the consolidated unpaginated path`() {
        val get = getSourceDocuments.getAnnotation(GET::class.java)
        assertEquals("sync/source-documents", get?.value)
    }

    @Test
    fun `source documents endpoint sends since`() {
        val queryNames = getSourceDocuments.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<Query>()
            .map { it.value }
        assertEquals(listOf("since"), queryNames)
    }

    @Test
    fun `endpoints the backend removed are gone from the interface`() {
        val names = CoachingApiService::class.java.declaredMethods.map { it.name }.toSet()
        listOf(
            "getPublishedSourceDocuments",
            "getAssignedVideos",
            "getSourceDocumentPresignedUrls",
            "getSourceDocumentThumbnailPresignedUrls",
            "getModuleThumbnailPresignedUrls",
            "generateCounsellingCard",
            "health",
        ).forEach { removed ->
            assertTrue("$removed no longer exists on the backend", removed !in names)
        }
    }

    @Test
    fun `badges endpoint is an unparameterised full snapshot`() {
        val pullBadges = CoachingApiService::class.java.getDeclaredMethod(
            "pullBadges",
            kotlin.coroutines.Continuation::class.java,
        )
        assertEquals("sync/badges", pullBadges.getAnnotation(GET::class.java)?.value)
        // Adding a `since` here would leave unchanged badges holding an artwork URL
        // that expires with nothing able to re-presign it.
        val queryNames = pullBadges.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<Query>()
            .map { it.value }
        assertEquals(emptyList<String>(), queryNames)
    }

    @Test
    fun `token-derived identity is not sent as a query parameter`() {
        // The backend resolves the CHW and tenant from the auth token and ignores
        // these silently, so sending them would read as though they still scope
        // the response.
        listOf("pullGaps", "pullChatFaqs", "getMorningCards", "pullBadges").forEach { name ->
            val method = CoachingApiService::class.java.declaredMethods.firstOrNull { it.name == name }
            assertNotNull("$name should still exist", method)
            val queryNames = method!!.parameterAnnotations
                .flatMap { it.toList() }
                .filterIsInstance<Query>()
                .map { it.value }
            assertTrue("$name must not send chw_id", "chw_id" !in queryNames)
            assertTrue("$name must not send tenant_id", "tenant_id" !in queryNames)
        }
    }
}
