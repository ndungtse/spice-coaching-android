package com.medtroniclabs.microcoaching.sdk.chat

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.RetrievalHintOverlay
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Builds and maintains the in-memory BM25 [ModuleKnowledgeIndex] over the on-device module
 * corpus (B1–B2 of docs/v3/chat_plan.md), used by the chat answer paths to ground responses.
 *
 * The index is deferred from SDK init — a launch-to-home session that never opens chat pays
 * neither the full-corpus JSON parse nor the retained index. [ensure] starts the maintaining
 * collector once (idempotent); the facade calls it when a chat surface opens.
 *
 * Extracted verbatim from `MicroCoachingSDK` (behaviour-preserving). Collaborators are passed
 * as providers so nothing forces the facade's lazy service graph at construction.
 */
internal class ChatKnowledgeIndexBootstrap(
    private val scope: CoroutineScope,
    private val config: MicroCoachingConfig,
    private val moduleDao: () -> ModuleDao,
    private val retiredFamilyIds: () -> Set<String>,
) {
    private val _index = MutableStateFlow(ModuleKnowledgeIndex.empty())

    /** In-memory BM25 index over the on-device module corpus. `empty()` until [ensure] runs. */
    val index: StateFlow<ModuleKnowledgeIndex> = _index.asStateFlow()

    @Volatile private var started = false

    /**
     * Start (once) the background collector that builds and maintains [index] from the module
     * corpus. Idempotent and cheap to call repeatedly: only the first call starts the collector,
     * which then lives for the process. The first build completes within ~100 ms.
     */
    fun ensure() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        // Rebuilds after each inbound module sync. `conflate()` + the trailing throttle coalesce
        // invalidation bursts (e.g. per-row thumbnail updates) into one rebuild of the LATEST
        // list. `retryWhen` keeps the collector alive across a transient read failure (Room flows
        // terminate on error).
        scope.launch {
            moduleDao().getAllActive()
                .retryWhen { cause, attempt ->
                    Log.w(TAG, "Module flow failed (attempt ${attempt + 1}) — retrying: ${cause.message}")
                    delay(1_000L * (attempt + 1))
                    attempt < MAX_FLOW_RETRIES
                }
                .conflate()
                .collect { modules ->
                    val indexedModules = RetrievalHintOverlay.apply(
                        modules = modules.sortedForDisplay(),
                        assets = config.context.assets,
                        enabled = config.enableRetrievalHintFixtureOverlay,
                    )
                    _index.value = ModuleKnowledgeIndex.build(
                        indexedModules,
                        retiredFamilyIds = retiredFamilyIds(),
                    )
                    delay(RECOMPUTE_THROTTLE_MS)
                }
        }
    }

    private companion object {
        private const val TAG = "MicroCoachingSDK"
        private const val MAX_FLOW_RETRIES = 4L
        private const val RECOMPUTE_THROTTLE_MS = 500L
    }
}
