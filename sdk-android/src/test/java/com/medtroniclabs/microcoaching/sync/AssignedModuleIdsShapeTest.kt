package com.medtroniclabs.microcoaching.sync

import com.medtroniclabs.microcoaching.network.ModulesSyncBundle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `assigned_module_ids` shape drift that hid CHWs' assigned modules:
 * the backend moved from bare id strings to `{ module_id, assigned_at }` objects.
 *
 * Two things must hold:
 *  1. The whole [ModulesSyncBundle] still deserializes across EITHER shape — a
 *     drift on this field must never fail the bundle (which carries `modules` /
 *     `module_cache`). That regression is what made assigned modules disappear.
 *  2. [parseAssignedRefs] extracts `module_id` (+ `assigned_at` when present) from
 *     the v3 object shape and still handles the legacy id string.
 */
class AssignedModuleIdsShapeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun bundle(assigned: String) = """
        {
          "modules": [
            { "id": "m-v1", "module_family_id": "fam-1", "version": 1,
              "title": {"bn":"শিরোনাম","en":"Title"}, "domain": "rmnch",
              "module_type": "initial_training", "estimated_minutes": 10,
              "difficulty_level": "moderate", "clinically_reviewed": true,
              "updated_at": "2026-07-08T06:00:00Z" }
          ],
          "assigned_module_ids": $assigned,
          "server_time_utc": "2026-07-08T06:37:21.941Z"
        }
    """.trimIndent()

    @Test
    fun `v3 object shape parses and does not break the modules bundle`() {
        val res = json.decodeFromString(
            ModulesSyncBundle.serializer(),
            bundle("""[{"module_id":"3fa85f64","assigned_at":"2026-07-08T06:37:21.941Z"}]"""),
        )
        // Bundle intact — module_cache content survives the assigned-shape change.
        assertEquals(1, res.modules.size)

        val refs = parseAssignedRefs(res.assignedModuleIds)
        assertEquals(1, refs.size)
        assertEquals("3fa85f64", refs.single().moduleId)
        assertEquals("2026-07-08T06:37:21.941Z", refs.single().assignedAtIso)
    }

    @Test
    fun `legacy bare-string shape still parses (back-compat)`() {
        val res = json.decodeFromString(ModulesSyncBundle.serializer(), bundle("""["id-a","id-b"]"""))
        assertEquals(1, res.modules.size)

        val refs = parseAssignedRefs(res.assignedModuleIds)
        assertEquals(listOf("id-a", "id-b"), refs.map { it.moduleId })
        assertTrue(refs.all { it.assignedAtIso == null })
    }

    @Test
    fun `malformed or partial entries are skipped, never thrown`() {
        val res = json.decodeFromString(
            ModulesSyncBundle.serializer(),
            // one valid object, one object missing module_id, one stray number
            bundle("""[{"module_id":"ok","assigned_at":"2026-07-08T06:37:21.941Z"},{"assigned_at":"x"},42]"""),
        )
        val refs = parseAssignedRefs(res.assignedModuleIds)
        assertEquals(listOf("ok"), refs.map { it.moduleId })
    }

    @Test
    fun `object without assigned_at yields null assignedAt`() {
        val res = json.decodeFromString(ModulesSyncBundle.serializer(), bundle("""[{"module_id":"ok"}]"""))
        val ref = parseAssignedRefs(res.assignedModuleIds).single()
        assertEquals("ok", ref.moduleId)
        assertNull(ref.assignedAtIso)
    }
}
