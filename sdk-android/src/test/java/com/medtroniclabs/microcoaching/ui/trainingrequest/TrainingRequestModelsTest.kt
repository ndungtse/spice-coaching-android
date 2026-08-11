package com.medtroniclabs.microcoaching.ui.trainingrequest

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingRequestModelsTest {

    // ── module_requested payload parsing ────────────────────────────────────────

    @Test
    fun `parses requested_module_name and reason from payload`() {
        val payload = """{"requested_module_name":"ANC","reason":"No idea what ANC is"}"""
        assertEquals("ANC", parseRequestedModuleName(payload))
        assertEquals("No idea what ANC is", parseRequestedReason(payload))
    }

    @Test
    fun `payload parsing is null-safe on blank, malformed, or missing keys`() {
        assertNull(parseRequestedModuleName(null))
        assertNull(parseRequestedModuleName(""))
        assertNull(parseRequestedModuleName("not json"))
        assertNull(parseRequestedModuleName("""{"reason":"x"}"""))
        assertNull(parseRequestedReason("""{"requested_module_name":"ANC"}"""))
        // Blank field value collapses to null.
        assertNull(parseRequestedModuleName("""{"requested_module_name":""}"""))
    }

    // ── Reason normalization ──────────────────────────────────────────────────

    @Test
    fun `reason is trimmed and blank collapses to null`() {
        assertEquals("need practice", normalizeReason("  need practice  "))
        assertNull(normalizeReason(""))
        assertNull(normalizeReason("   \n\t"))
    }

    // ── Picker search filter ──────────────────────────────────────────────────

    private val items = listOf(
        ModulePickerItem("mod-1", "fam-1", LocalizedText(bn = "নবজাতকের যত্ন", en = "Newborn Care"), "rmnch", null),
        ModulePickerItem("mod-2", "fam-2", LocalizedText(bn = "পুষ্টি", en = "Nutrition"), "nutrition", null),
        ModulePickerItem("mod-3", "fam-3", LocalizedText(bn = "স্যানিটারি পায়খানা", en = null), "wash", null),
    )

    @Test
    fun `blank query returns everything`() {
        assertEquals(items, items.filterByQuery(""))
        assertEquals(items, items.filterByQuery("   "))
    }

    @Test
    fun `english search is case-insensitive`() {
        assertEquals(listOf(items[0]), items.filterByQuery("newborn"))
        assertEquals(listOf(items[1]), items.filterByQuery("NUTRI"))
    }

    @Test
    fun `bangla search matches bn titles including en-less modules`() {
        assertEquals(listOf(items[2]), items.filterByQuery("পায়খানা"))
        assertEquals(listOf(items[1]), items.filterByQuery("পুষ্টি"))
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(emptyList<ModulePickerItem>(), items.filterByQuery("zzz"))
    }

    // ── Exclude-assigned filter ─────────────────────────────────────────────────

    @Test
    fun `excludingAssigned drops families assigned by family id`() {
        assertEquals(
            listOf(items[1], items[2]),
            items.excludingAssigned(assignedModuleIds = emptySet(), assignedFamilyIds = setOf("fam-1")),
        )
    }

    @Test
    fun `excludingAssigned drops modules assigned by version id`() {
        // Assigned row keyed on module_id (older/other version) still hides the family.
        assertEquals(
            listOf(items[0], items[2]),
            items.excludingAssigned(assignedModuleIds = setOf("mod-2"), assignedFamilyIds = emptySet()),
        )
    }

    @Test
    fun `excludingAssigned with nothing assigned returns everything`() {
        assertEquals(items, items.excludingAssigned(emptySet(), emptySet()))
    }

    @Test
    fun `excludingAssigned removes all when every family is assigned`() {
        assertEquals(
            emptyList<ModulePickerItem>(),
            items.excludingAssigned(emptySet(), setOf("fam-1", "fam-2", "fam-3")),
        )
    }
}
