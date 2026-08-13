package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.util.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing contract for `GET /dashboard/team-activity`. The roster field is `members`
 * (the backend renamed it from `users`); reading the wrong key silently yields an empty
 * roster — which blanks My SKs / Module Completion while the summary counts still show.
 */
class TeamActivityResponseParsingTest {

    @Test
    fun `members roster and summary parse`() {
        val json = """
            {
              "from_date": "2026-07-25",
              "to_date": "2026-07-31",
              "summary": {
                "total_users": 2, "active_users": 1, "non_active_users": 1,
                "users_completed_module": 1, "users_chatbot_engaged": 1
              },
              "members": [
                {
                  "user_id": 1313054034,
                  "name": "ANJALI RANI",
                  "role": "SHASTIYA_KORMI",
                  "can_drill_down": false,
                  "is_active": false,
                  "is_chatbot_engaged": false,
                  "last_chat_at": null,
                  "last_active_at": null,
                  "has_completed_module_in_range": false,
                  "assigned_modules": [
                    {"module_id": "462857d4-89a7-4483-887d-a5f4e0f6235f", "title": {"bn": "যক্ষ্মা"}, "completed_in_range": false}
                  ],
                  "chatbot_query_count": 3,
                  "refreshers_generated": 2,
                  "refreshers_completed": 1
                }
              ],
              "focus_user_id": null,
              "total_users": 2,
              "total_members": 2,
              "total_pages": 1,
              "limit": 100,
              "offset": 0,
              "server_time_utc": "2026-08-01T00:00:00Z"
            }
        """.trimIndent()

        val resp = LenientJson.decodeFromString(TeamActivityResponse.serializer(), json)

        assertEquals(1, resp.members.size)
        assertEquals("ANJALI RANI", resp.members[0].name)
        assertEquals(3, resp.members[0].chatbotQueryCount)
        assertEquals(1, resp.members[0].assignedModules.size)
        assertEquals(2, resp.totalMembers)
        assertEquals(1, resp.summary.activeUsers)
        assertTrue(!resp.members[0].canDrillDown)
    }
}
