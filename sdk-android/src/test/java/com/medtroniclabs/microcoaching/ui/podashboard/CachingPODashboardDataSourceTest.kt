package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.data.db.dao.DashboardCacheDao
import com.medtroniclabs.microcoaching.data.db.entity.DashboardCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingPODashboardDataSourceTest {

    private val range = DateRange(0L, 0L)

    private fun sample(spineError: String? = null) = PoDashboard(
        range = range,
        metrics = emptyList(),
        sks = listOf(SkSummary("u1", "A", SkStatus.ACTIVE, 1, 2, "Today", 3, 1, 2)),
        moduleCompletion = emptyList(),
        topSearchedExisting = emptyList(),
        topSearchedSuggested = emptyList(),
        spineError = spineError,
    )

    /** In-memory single-row DAO. */
    private class FakeDao : DashboardCacheDao {
        var row: DashboardCacheEntity? = null
        override suspend fun get() = row
        override suspend fun upsert(entity: DashboardCacheEntity) { row = entity }
        override suspend fun clear() { row = null }
    }

    /** Delegate whose loadDashboard behaviour is injected; other methods are unused here. */
    private class StubDelegate(val onLoad: () -> PoDashboard) : PODashboardDataSource {
        override suspend fun loadDashboard(chwId: String, range: DateRange) = onLoad()
        override suspend fun loadSkDetail(skId: String): SkDetail? = null
        override suspend fun loadSearchedModuleDetail(moduleId: String, range: DateRange) = error("n/a")
        override suspend fun loadSuggestionDetail(suggestionId: String) = error("n/a")
        override suspend fun loadAllSearchedExisting(range: DateRange) = emptyList<TopQuery>()
        override suspend fun loadAllSearchedSuggested(range: DateRange) = emptyList<TopQuery>()
        override suspend fun loadAllDocumentUsage(range: DateRange) = emptyList<DocumentUsageRow>()
        override suspend fun loadDocumentUsageDetail(documentId: String, range: DateRange): DocumentUsageDetail? = null
    }

    @Test
    fun `online success writes through and returns fresh`() = runBlocking {
        val dao = FakeDao()
        val src = CachingPODashboardDataSource(StubDelegate { sample() }, dao, isOnline = { true }, now = { 123L })
        val out = src.loadDashboard("chw1", range)
        assertEquals(false, out.fromCache)
        assertEquals(123L, out.fetchedAt)
        assertNotNull(dao.row)
        assertEquals("chw1", dao.row!!.chwId)
    }

    @Test
    fun `offline serves cached snapshot for same chw`() = runBlocking {
        val dao = FakeDao()
        CachingPODashboardDataSource(StubDelegate { sample() }, dao, isOnline = { true }, now = { 99L })
            .loadDashboard("chw1", range)
        val offline = CachingPODashboardDataSource(StubDelegate { error("must not call api") }, dao, isOnline = { false })
        val out = offline.loadDashboard("chw1", range)
        assertEquals(true, out.fromCache)
        assertEquals(99L, out.fetchedAt)
    }

    @Test
    fun `offline with different chw does not serve cache`() = runBlocking {
        val dao = FakeDao()
        CachingPODashboardDataSource(StubDelegate { sample() }, dao, isOnline = { true }).loadDashboard("chw1", range)
        val other = CachingPODashboardDataSource(StubDelegate { error("offline") }, dao, isOnline = { false })
        val ex = runCatching { other.loadDashboard("chw2", range) }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `api failure falls back to cache`() = runBlocking {
        val dao = FakeDao()
        CachingPODashboardDataSource(StubDelegate { sample() }, dao, isOnline = { true }, now = { 77L })
            .loadDashboard("chw1", range)
        val failing = CachingPODashboardDataSource(StubDelegate { throw RuntimeException("502") }, dao, isOnline = { true })
        val out = failing.loadDashboard("chw1", range)
        assertEquals(true, out.fromCache)
        assertEquals(77L, out.fetchedAt)
    }

    @Test
    fun `partial snapshot is shown live but not cached`() = runBlocking {
        val dao = FakeDao()
        val src = CachingPODashboardDataSource(StubDelegate { sample(spineError = "spine down") }, dao, isOnline = { true })
        val out = src.loadDashboard("chw1", range)
        assertEquals(false, out.fromCache)
        assertNull(dao.row)
    }
}
