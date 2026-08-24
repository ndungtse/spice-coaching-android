package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.content.richtext.bodyToPlainText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale

/**
 * Local dev server that runs the REAL offline-chat retrieval stack over a static
 * corpus file, so the algorithm can be exercised from a browser without a device,
 * a backend, or synced data.
 *
 * It is deliberately a thin shell: every ranking decision comes from the production
 * classes ([ModuleKnowledgeIndex], [GroundingSelector], [OffTopicGuard]) rather than a
 * re-implementation. Re-implementations drift, and a prototype that disagrees with the
 * shipped tokenizer produces confident but false conclusions about ranking changes.
 *
 * Run it with:
 *   MC_CORPUS=ignored/v3/modules/modules.json ./gradlew :sdk-android:retrievalLab
 *
 * Accepts either corpus shape:
 *   - sync-bundle  `{"modules":[{... "cards":[...] }]}`
 *   - device dump  `[{"module_id":…, "cards_json":"[…]"}]` (from docs/_chat-audit/export_corpus.py)
 *
 * Endpoints (CORS-open for the Vite dev server):
 *   GET  /health   → corpus stats
 *   GET  /cards    → every indexed card, for the corpus browser
 *   POST /query    → the full low-end trace for one question
 */
object DevRetrievalServer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val corpusPath = System.getenv("MC_CORPUS") ?: args.firstOrNull()
            ?: error("set MC_CORPUS=<path to modules.json or a device dump>")
        val port = (System.getenv("MC_LAB_PORT") ?: "7171").toInt()

        val modules = loadModules(File(corpusPath))
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val cards = modules.flatMap { m -> ModuleCorpusParser.extractCardChunks(m).map { it.first } }

        println("retrieval-lab: ${modules.size} modules, ${cards.size} indexable cards from $corpusPath")

        val server = try {
            HttpServer.create(InetSocketAddress(port), 0)
        } catch (e: java.net.BindException) {
            System.err.println(
                "\nretrieval-lab: port $port is already in use — another lab instance is probably still running.\n" +
                    "  free it : lsof -nP -iTCP:$port -sTCP:LISTEN | awk 'NR>1{print \$2}' | xargs kill\n" +
                    "  or move : MC_LAB_PORT=7272 ./gradlew :sdk-android:retrievalLab\n" +
                    "            (then point the UI's vite.config.ts proxy at the same port)\n",
            )
            throw e
        }
        server.createContext("/health") { ex ->
            ex.respond(
                buildJsonObject {
                    put("corpus", corpusPath)
                    put("modules", modules.size)
                    put("indexedCards", index.size)
                    put("cardsWithHints", cards.count { c ->
                        modules.any { m ->
                            ModuleCorpusParser.extractCardChunks(m)
                                .any { it.first.chunkId == c.chunkId && it.third.hasSearchableContent }
                        }
                    })
                }.toString(),
            )
        }
        server.createContext("/cards") { ex ->
            ex.respond(
                buildJsonArray {
                    cards.forEach { c ->
                        add(
                            buildJsonObject {
                                put("chunkId", c.chunkId)
                                put("family", c.moduleFamilyId.take(8))
                                put("title", c.titleBn ?: c.titleEn ?: "")
                                put("body", c.bodyBn ?: c.bodyEn ?: "")
                            },
                        )
                    }
                }.toString(),
            )
        }
        server.createContext("/query") { ex ->
            if (ex.requestMethod == "OPTIONS") return@createContext ex.respond("{}")
            val body = ex.requestBody.readBytes().decodeToString()
            val req = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            val q = (req?.get("q") as? JsonPrimitive)?.contentOrNull.orEmpty()
            // MLKit does not exist off-device, so English mode supplies the Bangla
            // translation explicitly instead of pretending to translate.
            val bn = (req?.get("banglaQuery") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ex.respond(trace(q, bn, index, scope).toString())
        }
        server.executor = null
        server.start()
        println("retrieval-lab: listening on http://127.0.0.1:$port  (ctrl-c to stop)")
        Thread.currentThread().join()
    }

    /**
     * Reproduces `ChatViewModel.handleRetrievalOnlyMessage` step by step, recording what each
     * production call returned so the UI can show the decision chain.
     */
    private fun trace(
        typed: String,
        banglaOverride: String?,
        index: ModuleKnowledgeIndex,
        scope: ScopeClassifier,
    ): JsonObject {
        val steps = mutableListOf<JsonObject>()
        fun step(stage: String, verdict: String, detail: String, extra: JsonObject? = null) {
            steps += buildJsonObject {
                put("stage", stage); put("verdict", verdict); put("detail", detail)
                extra?.forEach { (k, v) -> put(k, v) }
            }
        }

        val nativeQuery = banglaOverride ?: typed
        val crossQuery = if (banglaOverride != null) typed else null

        // L0 — deny-list
        if (scope.isOutOfScope(typed) || (crossQuery != null && scope.isOutOfScope(nativeQuery))) {
            step("L0 deny-list", "REFUSE", "hard out-of-scope match — no retrieval attempted")
            return result(steps, outcome = "REFUSAL", refusalKey = "refused_scope")
        }
        step("L0 deny-list", "PASS", "not an obvious out-of-domain topic")

        // L1 — advisory allow-list
        step(
            "L1 allow-list",
            if (scope.isInScope(nativeQuery)) "PASS" else "ADVISORY MISS",
            "advisory only — a miss no longer refuses before retrieval",
        )

        // L2 — retrieval
        val selection = GroundingSelector.select(
            nativeQuery = nativeQuery,
            englishQuery = crossQuery,
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 1.5f,
        )
        val grounding = selection.hits
        val tokens = BanglaTokenizer.tokenizeQuery(nativeQuery)
        val words = tokens.filterNot { it.length == 2 && it.all { ch -> ch.code in 0x0980..0x09FF } }
        step(
            "L2 retrieval", if (grounding.isEmpty()) "NO HITS" else "${grounding.size} hits",
            "chosen=${selection.chosenLabel} · ${words.size} words + ${tokens.size - words.size} char-bigrams",
            buildJsonObject {
                putJsonArray("words") { words.distinct().forEach { add(it) } }
                put("charBigrams", tokens.size - words.size)
                put("englishHits", selection.englishHits.size)
            },
        )
        if (grounding.isEmpty()) {
            step("serve", "REFUSE", "nothing cleared the retrieval floor")
            return result(steps, outcome = "REFUSAL", refusalKey = "refused_no_ground")
        }

        // Clinical-overlap backstop
        val confident = OffTopicGuard.hasConfidentTopHit(grounding)
        val refuseLowEnd = OffTopicGuard.shouldRefuseLowEnd(nativeQuery, grounding, scope.scopeTerms())
        step(
            "OffTopicGuard.shouldRefuseLowEnd", if (refuseLowEnd) "REFUSE" else "PASS",
            "confidentTopHit(score≥80)=$confident — a confident hit bypasses the overlap check entirely",
        )
        if (refuseLowEnd) {
            return result(steps, grounding, outcome = "REFUSAL", refusalKey = "refused_no_ground",
                index = index, query = nativeQuery)
        }

        // Serve-target selection
        val top = OffTopicGuard.selectLowEndServeHit(nativeQuery, grounding, scope.scopeTerms())
            ?: grounding.first()
        step(
            "selectLowEndServeHit", "PICK",
            "${top.chunkId} — ${if (top.chunkId == grounding.first().chunkId) "BM25 rank 1" else "promoted over rank 1"}",
        )

        // Topical gate (question↔evidence)
        val related = OffTopicGuard.sharesTopicalTerm(nativeQuery, top)
        step(
            "sharesTopicalTerm", if (related) "PASS" else "REFUSE",
            if (related) "shares a topical (non-demographic) word with the question"
            else "matches only on who the question is about — refusing instead of serving",
        )
        if (!related) {
            return result(steps, grounding, outcome = "REFUSAL", refusalKey = "refused_no_ground",
                served = top, index = index, query = nativeQuery)
        }

        val body = (top.bodyBn ?: top.bodyEn).orEmpty()
        step("serve", "SERVE", "served_retrieval_only · ${body.length} chars")
        return result(steps, grounding, outcome = "SERVED", served = top, body = body,
            index = index, query = nativeQuery)
    }

    private fun result(
        steps: List<JsonObject>,
        grounding: List<GroundingChunk> = emptyList(),
        outcome: String,
        refusalKey: String? = null,
        served: GroundingChunk? = null,
        body: String? = null,
        index: ModuleKnowledgeIndex? = null,
        query: String? = null,
    ): JsonObject = buildJsonObject {
        put("outcome", outcome)
        refusalKey?.let { put("refusalKey", it) }
        served?.let {
            putJsonObject("served") {
                put("chunkId", it.chunkId); put("family", it.moduleFamilyId.take(8))
                put("title", it.titleBn ?: it.titleEn ?: ""); put("score", it.score)
            }
        }
        body?.let { put("body", it) }
        putJsonArray("steps") { steps.forEach { add(it) } }
        putJsonArray("hits") {
            grounding.forEachIndexed { i, h ->
                add(
                    buildJsonObject {
                        put("rank", i + 1)
                        put("chunkId", h.chunkId)
                        put("family", h.moduleFamilyId.take(8))
                        put("title", h.titleBn ?: h.titleEn ?: "")
                        put("score", "%.2f".format(Locale.US, h.score).toFloat())
                        put("body", (h.bodyBn ?: h.bodyEn).orEmpty().take(400))
                        // Why this card scored what it did — same scorers as search().
                        if (index != null && query != null) {
                            index.explain(query, h.chunkId, ModuleKnowledgeIndex.Lang.BN)?.let { ex ->
                                putJsonObject("explain") {
                                    ex.perField.forEach { (f, v) ->
                                        put(f.name, "%.2f".format(Locale.US, v).toFloat())
                                    }
                                    put("wordOnly", "%.2f".format(Locale.US, ex.wordOnlyTotal).toFloat())
                                    put("charBigramShare", "%.2f".format(Locale.US, ex.charBigramShare).toFloat())
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    // ── corpus loading ───────────────────────────────────────────────────────

    private fun loadModules(file: File): List<ModuleEntity> {
        val root = json.parseToJsonElement(file.readText())
        val arr = when {
            root is JsonObject && root["modules"] is JsonArray -> root["modules"]!!.jsonArray
            root is JsonArray -> root
            else -> error("unrecognised corpus shape in ${file.name}")
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            // device dump keeps cards as a JSON *string*; the sync bundle nests them
            val cardsJson = when {
                o["cards_json"] != null -> (o["cards_json"] as? JsonPrimitive)?.contentOrNull ?: "[]"
                o["cards"] is JsonArray -> o["cards"]!!.toString()
                else -> "[]"
            }
            val metaJson = when {
                o["search_metadata_json"] != null -> (o["search_metadata_json"] as? JsonPrimitive)?.contentOrNull ?: "{}"
                o["search_metadata"] is JsonObject -> o["search_metadata"]!!.toString()
                else -> "{}"
            }
            val titleBn = s("title_bn")
                ?: ((o["title"] as? JsonObject)?.get("bn") as? JsonPrimitive)?.contentOrNull
                ?: ""
            moduleEntityFixture(
                moduleId = s("module_id") ?: s("id") ?: return@mapNotNull null,
                moduleFamilyId = s("module_family_id") ?: return@mapNotNull null,
                titleBn = titleBn,
                cardsJson = cardsJson,
                searchMetadataJson = metaJson,
            )
        }
    }

    private fun HttpExchange.respond(payload: String) {
        responseHeaders.add("Access-Control-Allow-Origin", "*")
        responseHeaders.add("Access-Control-Allow-Headers", "content-type")
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        val bytes = payload.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
