package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.util.StrictJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class PoDashboardSerializationTest {

    @Test
    fun `PoDashboard round-trips through StrictJson`() {
        val original = PoDashboard(
            range = DateRange(1000L, 2000L),
            metrics = listOf(PoMetric(MetricKey.ACTIVE_NOW, 3, 10)),
            sks = listOf(SkSummary("u1", "Amina", SkStatus.ACTIVE, 2, 4, "Today", 5, 1, 2)),
            moduleCompletion = listOf(
                ModuleCompletion("HTN", 1, 2, listOf(SkCheck("u1", "Amina", true))),
            ),
            topSearchedExisting = listOf(TopQuery(1, "HTN", 42, id = "mod-1")),
            topSearchedSuggested = listOf(TopQuery(1, "PPH", 15, id = "sug-1")),
            topSearchedExistingTotal = 6,
            topSearchedSuggestedTotal = 6,
            fetchedAt = 123456L,
        )

        val json = StrictJson.encodeToString(original)
        val restored = StrictJson.decodeFromString<PoDashboard>(json)

        assertEquals(original, restored)
    }
}
