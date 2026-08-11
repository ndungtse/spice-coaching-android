package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.network.DigitalHelpModuleQuestionsResponse
import com.medtroniclabs.microcoaching.network.DigitalHelpModuleUsageItem
import com.medtroniclabs.microcoaching.network.ModuleCreationSuggestionListItem
import com.medtroniclabs.microcoaching.network.TeamActivitySummary
import com.medtroniclabs.microcoaching.network.TeamMemberActivityDetail
import com.medtroniclabs.microcoaching.network.TeamMemberModuleActivity
import com.medtroniclabs.microcoaching.network.TeamMemberQuestionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pure-mapping contract for the PO dashboard (DTO → UI models). No network / no SDK
 * init needed — [LocalizedText.forSdkLanguage] falls back to Bangla when the SDK
 * singleton is absent, so `bn` titles resolve deterministically here.
 */
class PODashboardMappersTest {

    private val range = DateRange(0L, 0L)

    private fun module(id: String, titleBn: String, done: Boolean) =
        TeamMemberModuleActivity(moduleId = id, title = LocalizedText(bn = titleBn), completedInRange = done)

    @Test
    fun `summary maps to the four KPI cards with total_users`() {
        val summary = TeamActivitySummary(
            totalUsers = 3, activeUsers = 2, nonActiveUsers = 1,
            usersCompletedModule = 1, usersChatbotEngaged = 2,
        )
        val d = mapDashboard(range, summary, emptyList(), emptyList(), emptyList())

        assertEquals(MetricKey.ACTIVE_NOW, d.metrics[0].key)
        assertEquals(2, d.metrics[0].value)
        assertEquals(1, d.metrics[1].value) // INACTIVE
        assertEquals(1, d.metrics[2].value) // FINISHED_MODULES
        assertEquals(2, d.metrics[3].value) // CHATBOT_ENGAGED
        d.metrics.forEach { assertEquals("total is always total_users", 3, it.total) }
    }

    @Test
    fun `users map to SK rows with two-state status`() {
        val users = listOf(
            TeamMemberActivityDetail(
                userId = 1, name = "Amina", isActive = true,
                assignedModules = listOf(module("m1", "One", true), module("m2", "Two", false)),
                chatbotQueryCount = 5, refreshersCompleted = 1, refreshersGenerated = 2,
            ),
            TeamMemberActivityDetail(userId = 2, name = "Fatima", isActive = false),
        )
        val d = mapDashboard(range, TeamActivitySummary(), users, emptyList(), emptyList())

        assertEquals(2, d.sks.size)
        val a = d.sks[0]
        assertEquals("1", a.id)
        assertEquals(SkStatus.ACTIVE, a.status)
        assertEquals(1, a.modulesDone)
        assertEquals(2, a.modulesTotal)
        assertEquals(5, a.queries)
        assertEquals(1, a.refreshersDone)
        assertEquals(2, a.refreshersTotal)
        // is_active == false → INACTIVE (never NEEDS_ATTENTION — API-faithful two-state)
        assertEquals(SkStatus.INACTIVE, d.sks[1].status)
    }

    @Test
    fun `assigned modules pivot into per-module completion`() {
        val users = listOf(
            TeamMemberActivityDetail(userId = 1, name = "A", isActive = true,
                assignedModules = listOf(module("m1", "One", true), module("m2", "Two", false))),
            TeamMemberActivityDetail(userId = 2, name = "B", isActive = true,
                assignedModules = listOf(module("m1", "One", false))),
        )
        val d = mapDashboard(range, TeamActivitySummary(), users, emptyList(), emptyList())

        val one = d.moduleCompletion.first { it.moduleName == "One" }
        assertEquals(1, one.done)   // only user 1 completed
        assertEquals(2, one.total)  // both users assigned
        val two = d.moduleCompletion.first { it.moduleName == "Two" }
        assertEquals(0, two.done)
        assertEquals(1, two.total)
    }

    @Test
    fun `top searched rows carry combined count and tappable id`() {
        val existing = listOf(
            DigitalHelpModuleUsageItem(moduleId = "mod-1", digitalHelpCount = 30,
                moduleRequestedCount = 12, title = LocalizedText(bn = "HTN")),
        )
        val suggested = listOf(
            ModuleCreationSuggestionListItem(id = "sug-1", displayTitle = "PPH",
                questionCount = 7, requestCount = 3, rank = 1),
        )
        val d = mapDashboard(range, TeamActivitySummary(), emptyList(), existing, suggested)

        val e = d.topSearchedExisting.single()
        assertEquals("HTN", e.text)
        assertEquals(42, e.count)          // 30 + 12 combined
        assertEquals("mod-1", e.id)
        val s = d.topSearchedSuggested.single()
        assertEquals("PPH", s.text)
        assertEquals(10, s.count)          // 7 + 3 combined
        assertEquals("sug-1", s.id)
    }

    @Test
    fun `existing-module detail splits served vs requested`() {
        val resp = DigitalHelpModuleQuestionsResponse(
            moduleId = "mod-1", title = LocalizedText(bn = "HTN"), totalQuestions = 8,
            questions = listOf(TeamMemberQuestionItem("What BP needs referral?", 3, null)),
        )
        val detail = mapSearchedModuleDetail("mod-1", resp, requestedCount = 12)

        assertEquals("HTN", detail.title)
        assertEquals(8, detail.servedCount)
        assertEquals(12, detail.requestedCount)
        assertEquals(1, detail.questions.size)
        assertEquals("What BP needs referral?", detail.questions[0].text)
    }

    @Test
    fun `missing title falls back to module id`() {
        val existing = listOf(DigitalHelpModuleUsageItem(moduleId = "mod-x", title = null))
        val d = mapDashboard(range, TeamActivitySummary(), emptyList(), existing, emptyList())
        assertEquals("mod-x", d.topSearchedExisting.single().text)
    }

    @Test
    fun `millis convert to UTC yyyy-MM-dd`() {
        val millis = LocalDate.of(2026, 7, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("2026-07-30", millis.toApiDate())
    }

    @Test
    fun `relative day label`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        assertEquals("Today", relativeDayLabel(today.toString()))
        assertEquals("Yesterday", relativeDayLabel(today.minusDays(1).toString()))
        assertEquals("3 days ago", relativeDayLabel(today.minusDays(3).toString()))
        assertEquals("", relativeDayLabel(null))
        assertEquals("", relativeDayLabel("not-a-date"))
    }

    @Test
    fun `datetime with offset parses to relative label`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        assertEquals("Today", relativeDayLabel(today.toString() + "T09:30:00Z"))
    }

    @Test
    fun `mapDashboard carries the top-searched totals for Show all`() {
        val existing = listOf(DigitalHelpModuleUsageItem(moduleId = "m", title = LocalizedText(bn = "M")))
        val d = mapDashboard(
            range, TeamActivitySummary(), emptyList(), existing, emptyList(),
            existingTotal = 42, suggestedTotal = 7,
        )
        assertEquals(42, d.topSearchedExistingTotal)
        assertEquals(7, d.topSearchedSuggestedTotal)
    }

    @Test
    fun `status filter predicate`() {
        fun sk(status: SkStatus, queries: Int) =
            SkSummary("1", "A", status, 0, 0, "", queries, 0, 0)

        assertTrue(sk(SkStatus.INACTIVE, 0).matchesFilter(SkStatusFilter.ALL))
        assertTrue(sk(SkStatus.ACTIVE, 0).matchesFilter(SkStatusFilter.ACTIVE))
        assertFalse(sk(SkStatus.INACTIVE, 0).matchesFilter(SkStatusFilter.ACTIVE))
        assertTrue(sk(SkStatus.INACTIVE, 0).matchesFilter(SkStatusFilter.INACTIVE))
        assertTrue(sk(SkStatus.INACTIVE, 3).matchesFilter(SkStatusFilter.CHATBOT_ENGAGED))
        assertFalse(sk(SkStatus.ACTIVE, 0).matchesFilter(SkStatusFilter.CHATBOT_ENGAGED))
    }
}
