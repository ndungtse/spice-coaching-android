# On-device embedding storage

> **Date**: 2026-09-15 · **Module**: `sdk-android` · **Room schema**: v36
> **Scope**: how card embedding vectors live on the phone, and why there is no vector
> database there. The backend's own vector storage is a different question with a
> different answer — see [Division of labour](#division-of-labour).

---

## Summary

Card vectors are stored as **little-endian float32 BLOBs in SQLite**, decoded once into a
plain in-memory array, and searched by **exhaustive dot product**. For the 199 cards on a
device today that is 199 vectors, **0.6 MB on disk**, and a scan that costs roughly a thousandth of
what producing the query vector costs.

There is no on-device vector index, and adding one would make retrieval *worse*, not
better: an exhaustive scan returns the exact top-k, whereas every approximate index trades
recall for speed we do not need. The storage layer is not where quality leaks in this
pipeline — see [Does this cost quality](#does-this-cost-quality).

---

## Terms, and what scales with what

Every size and threshold in this document is counted in **cards**, because the store holds
**exactly one vector per card**. Nothing here scales with the number of questions asked, and
only indirectly with the number of modules.

| term | what it is | today | grows the store? |
|---|---|---|---|
| **module** | a published training unit, synced from `/sync/modules`. Contains cards. | 26 | only via its cards |
| **card** | one screen of content inside a module — the unit retrieval serves. **One card = one row in `card_embedding` = one 3,072-byte vector.** | 199 | **yes, 1:1** |
| **chunk** | a card as the retrieval layer sees it (`GroundingChunk`), joined to its vector on `card_id` | 195 joined | no |
| **query** | the CHW's typed question, embedded per turn and discarded | 1 per turn | no |

So the axis of the [revisit table](#when-to-revisit) is **cards**. At today's ratio of
about **7.7 cards per module**, the thresholds translate roughly as:

| modules | ≈ cards | vectors (disk **and** heap) |
|---:|---:|---:|
| **26** (today) | **199** | **0.6 MiB** |
| 100 | 765 | 2.2 MiB |
| 650 | 4,975 | 14.6 MiB |
| 1,000 | 7,654 | 22.4 MiB |
| 2,600 | 19,900 | 58.3 MiB |
| **5,000** | **38,269** | **112.1 MiB** |
| 13,000 | 99,500 | 291.5 MiB |

Two things deliberately do **not** enter these counts. Questions are transient — a query
vector is computed, used and thrown away, so a deployment answering a million questions
a day stores no more vectors than one answering ten. And quiz chunks, which BM25 also indexes
(`GroundingChunk.Source` is `CARD` or `QUIZ`), get no vectors: the dense index is built from
card chunks alone.

---

## What is stored

Room schema v36 adds one table (`data/db/migration/MIGRATION_35_36.kt`):

```sql
CREATE TABLE IF NOT EXISTS `card_embedding` (
    `card_id`          TEXT NOT NULL PRIMARY KEY,
    `module_family_id` TEXT NOT NULL,
    `dim`              INTEGER NOT NULL,
    `model_id`         TEXT,
    `vec`              BLOB NOT NULL,
    `synced_at_ms`     INTEGER NOT NULL
)
```

One row per card, keyed by the backend's per-card UUID, so a delta sync only rewrites the
cards that changed. `dim` and `model_id` are provenance: they are what let a later read
notice that a row was written by a different encoder or at a different width.

### The encoding

A 768-dimensional vector is 768 float32s, and a float32 is four bytes of IEEE-754. The
whole vector is therefore 3,072 bytes of opaque binary — exactly what a BLOB holds
(`data/db/entity/CardEmbeddingEntity.kt`):

```kotlin
fun FloatArray.toLeBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in this) buf.putFloat(f)
    return buf.array()
}
```

Two decisions in that function are load-bearing.

**Byte order is pinned explicitly.** `ByteBuffer` defaults to *big*-endian, so without
`.order(ByteOrder.LITTLE_ENDIAN)` the on-disk format would depend on a JVM default rather
than on anything written down. It happens to be stable in practice, which is precisely why
an implicit choice here would be a latent trap.

**Vectors are normalised at write time, not read time.** `CardEmbeddingsSyncApi.kt` stores
`embedding.toFloatArray().l2Normalized().toLeBytes()`. Every stored vector is therefore
unit length, which is what allows search to be a bare dot product with no square roots and
no per-query division — the dot product of two unit vectors *is* their cosine. There is no
`sqrt` anywhere in `DenseVectorIndex`.

### Reading it back

Decoding is symmetric, and happens **once per index build, not once per query**
(`sdk/chat/ChatKnowledgeIndexBootstrap.kt`):

```kotlin
val vectors = cardEmbeddingDao().getAll()
    .associate { it.cardId to it.toFloatVector() }
DenseVectorIndex.build(built.cardChunks, vectors)
```

`DenseVectorIndex` then holds a plain `float[N][768]` in memory and joins each vector to a
grounding chunk on `cardId`. Queries touch only RAM:

```kotlin
for (i in chunks.indices) {
    val v = vectors[i]
    var dot = 0f
    for (j in v.indices) dot += v[j] * query[j]
    scored.add(chunks[i] to dot)
}
```

Rows whose `cardId` matches no chunk are dropped with a count logged, as are rows of the
wrong width. Against the QA content that reads `built: 195 vectors joined of 199 synced` — the
four unmatched vectors belong to cards outside the modules assigned to that CHW.

---

## Sizes

Exact, for the 768-dimensional model in use:

| | value |
|---|---|
| one vector | 768 × 4 B = **3,072 B** |
| 199 cards (all 26 modules on a device today) | 611,328 B = **0.58 MiB** |
| resident as `float[N][768]` | the same 0.6 MB again, plus object overhead |
| sync payload | ~3.5 MB of JSON for ~200 cards, once, then deltas |
| the encoder that produces query vectors | **170.8 MB** download |

The proportion is the point: the vector store is **0.35%** of what the feature costs on
disk. The 171 MB model dwarfs it by 280×. Anyone optimising the storage layer here is
optimising the wrong three decimal places.

### Disk or RAM? Both — and the RAM half is the one with a ceiling

The same bytes exist **twice**, and conflating them is the easiest way to misread every
table in this document:

| | where | how much | what caps it |
|---|---|---|---|
| the BLOBs | SQLite (SQLCipher) on disk | N × 3,072 B | device storage — gigabytes, effectively free |
| the decoded copy | **Java heap**, as `float[N][768]` | N × 3,072 B | **the per-app heap limit** |

The `resident` column in the scan table is the **second** row: Java heap, not physical RAM
and not disk. That distinction decides the whole scaling story, because Android does not
give an app the phone's RAM. It gives it a heap quota — OEM-configured, typically in the
**128–256 MB** range, and `largeHeap` is not requested by either the SDK or the host, so we
get the standard quota.

That quota, not physical memory, is what binds:

- the model's weights are **mapped native memory**, outside the heap, so the 171 MB encoder
  does not compete for it
- the vectors **are** heap, so they compete directly with Compose, Room, image caches and
  everything else the host app holds

At 199 cards, 0.6 MB of a ~192 MB heap is nothing. At 38,269 cards it is **112 MiB of a
~192 MB heap**, which is not survivable alongside a real app. The ceiling arrives far
earlier than physical RAM would suggest.

---

## Why no vector index

### Measured cost of the exhaustive scan

Desktop JVM, 768 dimensions, JIT warmed, mean of 50 runs. A mid-tier phone is meaningfully
slower — treat these as a lower bound and the shape of the curve as the real finding:

| cards (= vectors) | FMAs per query | scan | resident |
|---:|---:|---:|---:|
| **199** (today, 26 modules) | 152,832 | **0.07 ms** | 0.6 MB |
| 1,000 | 768,000 | 0.37 ms | 2.9 MB |
| 10,000 | 7,680,000 | 3.99 ms | 29.3 MB |
| 100,000 | 76,800,000 | 43.64 ms | 293 MB |

Set against the one number measured on a real device: **198 ms per query to run the
encoder** (Android 13 emulator, arm64, 2 CPU threads, plus 644 ms to load the graph on
first use). Producing the query vector costs roughly **2,800×** more than searching with
it. Even at fifty times today's card count the scan would still be a rounding error next
to the encode.

### What an index would actually cost

`pgvector` is a **Postgres extension** and cannot be installed on a phone at any price.
The on-device equivalents are `sqlite-vec` or `sqlite-vss`, which means another native
library in an APK that already carries:

| existing native (arm64) | size |
|---|---:|
| `liblitertlm_jni.so` (chat LLM runtime) | 21.5 MB |
| `libonnxruntime.so` (sherpa STT) | 25.8 MB |
| `libtensorflowlite_jni.so` (the query encoder) | 4.27 MB |

So the trade is: add a fourth native dependency, its packaging rules and its version-skew
risk, in order to speed up the one part of the turn that is already free. At 199 rows an
ANN index would plausibly be *slower* than the linear scan, since tree traversal and cache
misses cost more than 153k sequential fused multiply-adds.

---

## Device tiers: what runs on 2 GB

**Nothing. Dense retrieval never runs on a 2 GB device, by design.**

`DeviceCapability.MIN_RAM_BYTES_FOR_FULL_MODE` is **3 GiB**, and `isLowEndDevice` is
`totalMem < 3 GiB`. `EncoderModelRule.evaluate` returns `SKIPPED_LOW_END` for such a
device, so the 171 MB encoder is never downloaded, no query vector is ever produced, and
chat runs BM25-only — the same behaviour as with the feature flag off. A 2 GB phone is
therefore unaffected by any number in this document.

That gate is doing real work rather than being conservative for its own sake. Were it
removed, a 2 GB device would need to hold, simultaneously:

| | cost |
|---|---|
| encoder weights (mapped native, not heap) | ~110 MB RSS at seq256 |
| card vectors (**heap**) | 0.6 MB today, 112 MiB at 5,000 modules |
| the host app itself — Compose, Room, SQLCipher, image caches | the rest |

on a device whose per-app heap quota is typically **128 MB** at that RAM class. The
vectors alone would exceed the quota somewhere around 30,000–40,000 cards, and the encoder
would be competing for physical memory with the rest of the system long before that.

**The honest summary for the 3 GB+ tier that does run it:** today's 0.6 MB is free, and
the arrangement stays comfortable to roughly 5,000–10,000 cards (650–1,300 modules). Beyond
that the Java heap — not the scan, not the disk — is what needs attention.

---

## Does this cost quality

**No — and this is the part most often assumed backwards.** An exhaustive scan evaluates
every vector, so it returns the mathematically exact top-k: recall is 100% by construction.
Approximate nearest-neighbour indexes (HNSW, IVF) are *approximations* — they buy speed by
accepting that they will sometimes miss the true nearest neighbour. Brute force is the
quality ceiling that those indexes are measured against, not a compromise against them.

Retrieval quality in this pipeline leaks in three other places, none of which a vector
store would touch:

1. **The on-device encoder is quantised and the backend's is not.** Measured parity of
   on-device query vectors against fp32 reference vectors for the same text is 0.916 min /
   0.955 mean / 0.972 max, against a 0.99 target. `litert-community` publishes only
   mixed-precision builds of this model, so that gap is a property of the artifact.
2. **Card text must be embedded with the same recipe and the same model.** A mismatch
   silently collapses similarity rather than erroring. `model_id` on each row plus the
   clear-on-change guard in `CardEmbeddingsSyncApi` are the defence.
3. **The join is not total.** 195 of 199 vectors bind to a chunk; a stale module cache with
   no card ids gets no dense index at all, which degrades to BM25-only by design.

If someone proposes a vector database to improve answer quality, items 1 and 2 are where
the actual loss is, and both are upstream of storage.

---

## Limitations, honestly

**The vectors are opaque to SQL.** There is no `ORDER BY embedding <-> :query` in SQLite,
so every ranking and fusion decision happens in Kotlin (`GroundingSelector.fuseWithDense`).
That is fine for reciprocal-rank fusion over a few candidates and would be painful if the
retrieval logic ever needed to filter on vector distance inside a larger query.

**The schema does not enforce width.** `dim` is a column, not a constraint. A row of the
wrong width is caught at read time and dropped, which is a correct outcome but a late one.

**The index is rebuilt wholesale.** Every module sync calls `getAll()` and rebuilds the
array; there is no incremental update. At 199 rows that is invisible. It scales linearly
and would need attention well before the scan does.

**It is resident in RAM for the life of the process**, and the dense branch is not
currently gated on device tier at index-build time — only the encoder download is. At 0.6 MB
this does not matter; at 30 MB it would deserve a decision.

**The scan is single-threaded** and not SIMD-accelerated. Both are available levers long
before an index is warranted.

---

## When to revisit

Thresholds, in the order they will actually bite:

| cards (≈ modules) | what changes | do |
|---|---|---|
| **< 5,000** (≈ 650) | nothing. Scan under 2 ms, under 15 MiB of heap | leave it alone |
| **5,000–20,000** (≈ 650–2,600) | heap reaches 15–60 MiB — a visible share of the quota | quantise to int8 with a scale factor (4× smaller) or truncate 768 → 256 via Matryoshka, which this model supports (3× smaller, small recall cost). Either one buys back a factor of 3–4 with no new dependency |
| **20,000–40,000** (≈ 2,600–5,000) | **heap becomes the binding constraint**, at 60–112 MiB against a ~128–256 MB quota | stop holding the vectors on the Java heap: memory-map the BLOBs or move to a `ByteBuffer.allocateDirect` off-heap store and scan in place. This is the change that actually matters at scale |
| **> 40,000** (≈ 5,000+) | sync volume and scan latency join heap as problems | an on-disk ANN index (`sqlite-vec`) finally earns its native dependency — but only *after* the sync-scope question below is settled |

Two things about that ordering.

**Quantisation and going off-heap both come before an index.** They attack the resource
that binds first — the Java heap — cost no new native dependency, and are reversible. An
index addresses scan latency, which has by far the most headroom: even at 38,269 cards the
scan is roughly 61 ms against the encoder's measured 198 ms.

**The earlier version of this table claimed RAM only became a blocker past 100,000 cards.
That was wrong**: it implicitly assumed the store competed with physical RAM. It competes
with the per-app Java heap, which is an order of magnitude smaller, so the ceiling arrives
at roughly 30,000–40,000 cards instead.

### The prior question: sync scope

Before any of the above matters, one architectural fact needs confirming. Today the device
holds **199 vectors for 26 cached modules while only 4 modules are assigned** to that CHW,
because `/sync/card-embeddings` is watermark-based (`?since=`) rather than
assignment-filtered. The store therefore tracks the **published catalogue**, not what the
health worker actually uses.

If that holds at catalogue scale, 5,000 published modules means ~38,000 vectors, 112 MiB of
heap and — at today's ratio of ~3.5 MB of JSON per 200 cards — on the order of **670 MB of
sync payload** onto a CHW's phone, to serve someone who opens four modules. The sync, not
the storage format, breaks first.

Filtering card embeddings by assignment server-side would keep the device at a few hundred
vectors regardless of how large the catalogue grows, and would make every threshold in this
document irrelevant. **That is the change worth making before any storage work.**

---

## Division of labour

The phone's BLOB is not an alternative to a vector database. Both exist, at opposite ends
of the same pipeline:

| | storage | search |
|---|---|---|
| **backend** (`coaching-platform`) | `module_card.local_embedding vector(768)` on `pgvector/pgvector:pg15` | `VectorStore.search`, pgvector adapter |
| **device** (`sdk-android`) | `card_embedding.vec` BLOB in SQLite (SQLCipher) | exhaustive dot product in `DenseVectorIndex` |

The backend embeds every card once, at scale, across all tenants, and needs SQL-level
filtering and joins against the rest of the schema — pgvector is the right tool there and
is what is used. The phone receives ~0.6 MB of finished vectors for the modules assigned to
one CHW and needs to rank 199 of them against one query, offline. Those are different
problems, and the second one does not need a database.

---

## Reference

| concern | file |
|---|---|
| table and migration | `data/db/migration/MIGRATION_35_36.kt` |
| encode / decode / normalise | `data/db/entity/CardEmbeddingEntity.kt` |
| sync and the model-change guard | `sync/CardEmbeddingsSyncApi.kt` |
| decode site and index build | `sdk/chat/ChatKnowledgeIndexBootstrap.kt` |
| in-memory index and search | `ai/retrieval/DenseVectorIndex.kt` |
| fusion with BM25 | `ai/retrieval/GroundingSelector.kt` (`fuseWithDense`) |
| codec tests | `data/db/entity/CardEmbeddingVectorCodecTest.kt` |
| index tests | `ai/retrieval/DenseVectorIndexTest.kt` |
