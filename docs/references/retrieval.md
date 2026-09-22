# Retrieval: how a question becomes three candidate cards

**Status:** current for `feat/hybrid-dense-retrieval`

Before any answer is written, offline chat has to find the card that answers the question.
Two searches run, independently, over every card in the synced corpus: a word-matching
search (BM25) and a meaning-matching search (dense vectors). Their two rankings are merged
by rank position, and the top three of the merged list go to the [serve gate](./serve-gate.md),
which decides whether any of them is served.

Neither search replaces the other. If the encoder is missing, no vectors have synced, or no
dense candidate is close enough, the result is exactly the BM25-only ranking.

---

## BM25 over the synced corpus

`ModuleKnowledgeIndex` builds an in-memory Okapi BM25 index when the module corpus loads
and rebuilds it after each module sync. It indexes each card's title, body, authored
retrieval hints and authored questions, in Bangla and English as separate fields.

- Every card gets a score for the question. Scores are unbounded; a title match on a
  Bangla question typically lands between 50 and 600.
- `ChatTuning.bm25ScoreThreshold` (1.5) drops noise only. Nothing that matters is decided
  by it.
- The trace shows the top five (`BM25 native[BN] hits=5`); the top three are fused.

## Dense retrieval on device

The query encoder is EmbeddingGemma-300m running under LiteRT, producing a 768-dimension
vector, L2-normalised. Card vectors are computed by the backend and synced into the
`card_embedding` table. At query time the phone encodes the question and takes the dot
product against every card vector, which is the cosine because both sides are unit length.

- `ServeTuning.denseTopK` (3) candidates are kept.
- A candidate joins fusion only at cosine ≥ `ServeTuning.cosFloor` (0.50). Below that the
  encoder's opinion is not allowed to reshuffle BM25.
- One candidate can be **dominant**: cosine ≥ `cosDominantFloor` (0.65) and at least
  `cosDominantMargin` (0.03) ahead of the runner-up. Dominance is the only signal in the
  gate that outranks word evidence.

The trace line `DENSE off (index=true embedder=false)` means the encoder was not available
on this device, and the turn ran BM25-only.

## Fusion by rank, not by score

BM25 scores and cosines live on different scales, so they are never added. Each list gives a
card `1 / (k + rank)` with `ServeTuning.rrfK` = 60, and the two contributions are summed
(`GroundingSelector.fuseWithDense`). A card on both lists roughly doubles. A card at rank 1
on one list only cannot beat a card at rank 3 on both.

### Worked example

A question from the CHW evaluation set, replayed in the JVM against the audit corpus with
cosines computed on device:

> প্রসবের পর জরায়ু ঠিকমতো আগের আকারে ফিরছে কিনা কীভাবে দেখব?
> (After delivery, how do I check whether the uterus is returning to its normal size?)

BM25 top three:

| Rank | Score | Card |
|---|---|---|
| 1 | 81.8 | What is postnatal care (Puerperium)? |
| 2 | 71.9 | What is postnatal care? (sister module) |
| 3 | 70.1 | Decrease in uterine height (involution), the right card |

Dense top three:

| Rank | Cosine | Card |
|---|---|---|
| 1 | 0.597 | Decrease in uterine height (involution), the right card |
| 2 | 0.597 | Same card, sister module |
| 3 | 0.541 | Antenatal: measuring uterine height |

Fused:

| Card | BM25 rank | Dense rank | Fused |
|---|---|---|---|
| Involution | 3 → 1/63 | 1 → 1/61 | **0.0323** |
| Postnatal care (Puerperium) | 1 → 1/61 | none | 0.0164 |
| Postnatal care (sister) | 2 → 1/62 | none | 0.0161 |
| Involution (sister) | none | 2 → 1/62 | 0.0161 |
| Measuring uterine height | none | 3 → 1/63 | 0.0159 |

BM25 alone would have served the postnatal-care overview. Hybrid serves the involution card.
The gate's trace on the pick reads `score=70.1 … cos=0.60`: the BM25 score is the card's own,
and the cosine sits beside it as evidence.

### Three more cases from the same set

- **A cosine under 0.50 is discarded.** On "pregnant woman constipated for days" the dense
  list had the constipation card at 0.491. Not admitted; both modes served a wrong card.
- **Dense can rescue a refusal.** On "where to send a sputum sample" BM25's three hits were
  two dengue cards and one TB card with no topic term, so BM25 alone refused. Dense placed
  two TB cards at 0.603 and 0.502; the one on both lists reached fused rank 1 and was served.
- **Dominance is the one case dense outranks words.** On "child breathing fast, when to
  refer", BM25 had "counting breathing rate" at 154.2 over "when to refer" at 149.4. Dense
  had "when to refer" at 0.674 against 0.588, above the floor and past the margin, so the
  gate picked "when to refer".

---

## Why the device is not the backend

The online path (`POST /coaching/rag-query`) is dense-only over one vector per **module**,
takes the five nearest modules with no distance cutoff, puts every card of those modules into
the prompt, and lets Gemini pick and write. There is no retrieval gate online; the large model
reads and decides.

The phone cannot copy that, for three reasons:

- **The on-device model rewords one card; it does not choose.** So retrieval has to pick the
  exact card first, at card level, which the backend never does.
- **The device encoder is weaker.** The quantized EmbeddingGemma matches its own
  full-precision output at 0.955 cosine, and with quantized queries against quantized cards
  Bengali scored below BM25 alone on the trainer's set. Word matching is the stronger single
  signal for questions that reuse card vocabulary.
- **The device must refuse before generating.** The gate that does that is calibrated on
  BM25 evidence and population scope; dense is layered on as evidence.

---

## Where the numbers live

| Setting | Field | Default |
|---|---|---|
| BM25 noise floor | `ChatTuning.bm25ScoreThreshold` | 1.5 |
| Dense candidates | `ServeTuning.denseTopK` | 3 |
| Dense admit floor | `ServeTuning.cosFloor` | 0.50 |
| Dominance floor / margin | `ServeTuning.cosDominantFloor` / `cosDominantMargin` | 0.65 / 0.03 |
| Fusion constant | `ServeTuning.rrfK` | 60 |
