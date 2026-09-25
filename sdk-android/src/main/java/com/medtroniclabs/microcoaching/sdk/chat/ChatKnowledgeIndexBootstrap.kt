package com.medtroniclabs.microcoaching.sdk.chat

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ai.retrieval.DenseVectorIndex
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.RetrievalHintOverlay
import com.medtroniclabs.microcoaching.ai.retrieval.ScopeClassifier
import com.medtroniclabs.microcoaching.data.db.dao.CardEmbeddingDao
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
 * corpus, used by the chat answer paths to ground responses.
 *
 * The index is deferred from SDK init — a launch-to-home session that never opens chat pays
 * neither the full-corpus JSON parse nor the retained index. [ensure] starts the maintaining
 * collector once (idempotent); the facade calls it when a chat surface opens.
 *
 * Collaborators are passed as providers so nothing forces the facade's lazy service graph
 * at construction.
 */
internal class ChatKnowledgeIndexBootstrap(
    private val scope: CoroutineScope,
    private val config: MicroCoachingConfig,
    private val moduleDao: () -> ModuleDao,
    private val retiredFamilyIds: () -> Set<String>,
    private val cardEmbeddingDao: () -> CardEmbeddingDao,
) {
    private val _index = MutableStateFlow(ModuleKnowledgeIndex.empty())

    /** In-memory BM25 index over the on-device module corpus. `empty()` until [ensure] runs. */
    val index: StateFlow<ModuleKnowledgeIndex> = _index.asStateFlow()

    private val _scope = MutableStateFlow(ScopeClassifier.buildFrom(emptyList()))

    /**
     * Scope/evidence vocabulary over the SAME modules as [index]. The two must be built
     * from one corpus: retrieval can surface a card from any indexed module, and
     * [com.medtroniclabs.microcoaching.ai.retrieval.ServeGate] judges that card
     * against this gazetteer. A narrower vocabulary here makes a card's own topic
     * invisible to the gate, which then reads the hit as having no evidence.
     */
    val scopeClassifier: StateFlow<ScopeClassifier> = _scope.asStateFlow()

    private val _denseIndex = MutableStateFlow<DenseVectorIndex?>(null)

    /**
     * Dense vector index over the SAME chunks as [index], joined to the synced
     * `card_embedding` rows on the per-card id. Null until dense retrieval is
     * enabled AND vectors have synced AND the cached cards carry ids — every
     * "null" path degrades chat to BM25-only for the turn, never to an error.
     * Rebuilt with the BM25 index, so vectors landing between module syncs become
     * visible on the next rebuild (or process restart) rather than instantly.
     */
    val denseIndex: StateFlow<DenseVectorIndex?> = _denseIndex.asStateFlow()

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
                    // Retired families are dropped here rather than inside the index
                    // build, so the index and the vocabulary see one identical list.
                    val retired = retiredFamilyIds()
                    val indexedModules = RetrievalHintOverlay.apply(
                        modules = modules.sortedForDisplay().filter { it.moduleFamilyId !in retired },
                        assets = config.context.assets,
                        enabled = config.enableRetrievalHintFixtureOverlay,
                    )
                    val built = ModuleKnowledgeIndex.build(indexedModules)
                    _index.value = built
                    _scope.value = ScopeClassifier.buildFrom(indexedModules)
                    _denseIndex.value = if (config.enableDenseRetrieval) {
                        val vectors = cardEmbeddingDao().getAll()
                            .associate { it.cardId to it.toFloatVector() }
                        if (vectors.isEmpty()) null
                        else DenseVectorIndex.build(built.cardChunks, vectors).takeIf { it.size > 0 }
                    } else {
                        null
                    }
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
