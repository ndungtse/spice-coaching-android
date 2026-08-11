package com.medtroniclabs.microcoaching.network

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET

/**
 * Pins the `getMediaPresignedUrl` endpoint path.
 *
 * The backend serves rich-body media presigned URLs at `admin/files/presigned-url`
 * (relative to the `medtronics-api/` base). A spurious `admin/v3/files/...` prefix
 * once shipped here and returned 404 `{"detail":"Not Found"}` on device while the
 * unversioned path worked on Swagger — rich card images silently failed to load.
 *
 * This locks the path so the `v3` regression can't return unnoticed.
 */
class MediaPresignedUrlPathContractTest {

    @Test
    fun `media presigned endpoint is unversioned admin files path`() {
        val method = CoachingApiService::class.java
            .getDeclaredMethod(
                "getMediaPresignedUrl",
                String::class.java,
                Int::class.java,
                String::class.java,
                kotlin.coroutines.Continuation::class.java,
            )
        val get = method.getAnnotation(GET::class.java)
        assertEquals("admin/files/presigned-url", get?.value)
    }
}
