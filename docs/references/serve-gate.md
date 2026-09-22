# The serve gate: deciding whether any card is served

**Status:** current for `feat/hybrid-dense-retrieval`

`ServeDecision.decide` is a pure function. It takes the three fused candidates from
[retrieval](./retrieval.md) and returns either one card to serve or a refusal with a reason.
Both on-device modes call it, so the language model can only reword a card the gate would
have served verbatim, and a refusal costs no inference.

The policy: **a card is served only when it demonstrably shares the question's subject**.
Otherwise the chat refuses, because a CHW cannot tell a confidently served wrong card from a
right one.

---

## The five steps

### 1. Read the question

Four things are extracted before any card is looked at.

- **Topic tokens**: the question's content words minus demographic words, question-form
  words (`TEMPLATE_TERMS`: symptoms, cause, advice, method and the like), numbers and units.
- **Gazetteer terms**: which of those words are known clinical terms. The gazetteer is the
  static clinical seed, the synonym map, and words harvested from module titles.
- **Concepts**: synonym groups the words map to, so "ইডিমা" and "পানি আসা" both become
  `edema`. Concepts reachable from a population marker (postpartum, ANC visit) count as
  population, never as topic.
- **Populations**: whether the question names the maternal group, the child group, the
  adult group, several, or none. Markers are in `POPULATION_GROUPS`.

### 2. Evidence per candidate

For each of the three cards the gate records: shared condition terms, shared concepts, how
many of those appear in the card's title or hints (`titleHint`), whether the card is scoped to
a population the question does not name (`populationVeto`), whether the card is scoped and
the question names nobody (`populationUnstated`), and the dense channels: cosine, agrees
(≥ 0.50), dominant (≥ 0.65 with a 0.03 lead).

Scope is read from the card's title and hints only. Bodies mention mothers and children in
passing on cards that answer anyone.

### 3. Is the card servable?

Checked in this order; the first rule that applies decides.

| Rule | Result |
|---|---|
| Card scoped to a population, question names a **different** one | not servable |
| Dense dominant | servable |
| Card scoped, question names **nobody** | servable only on 2 topic terms |
| Dense agrees | servable |
| Question has no topic words at all | servable on any shared term or concept |
| Otherwise | servable on 1 topic term, or 2 when the question offers 3 or more |

The third rule is the compromise between two failures: refusing every card-title question
that names no population, and serving an antenatal blood-pressure card to "what are the
symptoms of high blood pressure" on one shared word.

If no candidate is servable, the turn refuses with `NO_EVIDENCE`.

### 4. Pick among the servable

Only cards within 55 percent of the top BM25 score compete (`promoteRatio`), plus any card
with dense agreement. The winner is chosen by these keys, first difference wins:

1. dense dominant
2. has any topic term (a card about the subject beats one about only the population)
3. title-hint overlap (authors write hints as the questions a card answers)
4. condition terms plus concepts
5. dense agrees
6. raw BM25 score

### 5. Floor

The chosen card must score at least 25 on a Bengali turn or 40 on an English one
(`bnScoreFloor`, `enScoreFloor`), unless it has dense agreement, since a dense-only entrant
has no BM25 score to clear. Otherwise the turn refuses with `BELOW_SCORE_FLOOR`.

---

## The population veto

A card written for one population does not answer a question about another. "Blocked"
below means the card is not servable, whatever else it has going for it.

| Card scoped to | Question names | Blocked |
|---|---|---|
| nothing | anything | no |
| maternal | maternal | no |
| maternal | maternal and child | no |
| maternal | child | yes |
| maternal | adult client | yes |
| maternal | nothing | no, but needs 2 topic terms or dominance |
| child | nothing | no, but needs 2 topic terms or dominance |

The adult group (সেবাগ্রহীতা, ক্লায়েন্ট, প্রাপ্তবয়স্ক, পুরুষ, বয়স্ক, client, adult, man,
elderly) exists so that a question about an adult client conflicts with a maternal or child
card. Without it, "the client has high blood pressure" names nobody the gate recognises.

---

## Every way a turn can refuse

| Where | Reason | Trigger |
|---|---|---|
| Before retrieval | L0 deny-list | The question contains a term from `ScopeClassifier.DENY_TERMS` (weather, sports, recipes). |
| Gate | `NO_HITS` | BM25 returned nothing above 1.5. |
| Gate | `NO_EVIDENCE` | No candidate passes step 3. |
| Gate | `BELOW_SCORE_FLOOR` | The pick scores under 25 (BN) or 40 (EN) and has no dense agreement. |
| After the model answers | groundedness | The answer's content-word overlap with the card is under 0.25 (strong retrieval) or 0.35. The card is served verbatim instead if its score is at least `minFallbackServeScore` (20); otherwise the turn refuses. |

---

## Worked example

A question from the QA bank, the card's own title with a template suffix:

> ৬ মাস পূর্ণ বয়সের পর বাড়তি খাবার শুরু করার কারণ কী? SK হিসেবে আমি রোগীকে কী বলব?

The expected card is the complementary-feeding card with that exact title. Its hints mention
শিশু, so it is scoped to the child group. The question names no population.

**Before the veto change**, the trace read:

```
SERVE-DECISION refuse reason=NO_EVIDENCE 0fa2eaf5:card:3 score=580.6 terms=[খাবার] titleHint=1 POPULATION-VETO cos=0.67 cos-dominant
```

The highest BM25 score in the bank, a dominant cosine, a title match, and a refusal, because
the veto blocked any scoped card for a question that named nobody. Step 3's first rule fired.

**After**, the same question in the two modes:

- With dense on, the cosine of 0.67 is dominant, step 3's second rule fires, and the card is
  served.
- With dense off (a device without the encoder), the card has one topic term, খাবার, and the
  question names nobody, so the third rule asks for two. It refuses. This is the accepted
  cost of not serving "symptoms of high blood pressure" an antenatal card on one word.

A counter-example that must refuse, from the trainer's set:

> What are the symptoms of high blood pressure?

The antenatal blood-pressure danger-sign card scores 163.8 and shares রক্তচাপ. One topic
term, a scoped card, no population named. Refused, correctly: the corpus has no general
hypertension card, and the CHW would otherwise be told about pregnancy.

---

## Reading a ChatTrace line

```
SERVE-DECISION serve 6818eb87…:card:0 score=273.9 terms=[গর্ভ, গর্ভকালীন, চেকআপের] concepts=[] titleHint=3 cos=0.55
```

| Field | Meaning |
|---|---|
| `serve` / `refuse reason=…` | the decision; a refusal lists every candidate's evidence after the reason |
| `<family>:card:<n>` | module family id and 0-based card index of the pick |
| `score` | the card's BM25 score, unchanged by fusion |
| `terms` | shared gazetteer terms |
| `concepts` | shared synonym concepts |
| `titleHint` | how many shared terms sit in the card's title or hints |
| `population-only` | the shared evidence is all population words |
| `POPULATION-VETO` | the card is scoped to a population the question does not name |
| `population-unstated` | the card is scoped and the question names nobody |
| `cos`, `cos-dominant` | the dense cosine, and whether it cleared the dominance floor and margin |

---

## Measured on the question banks

JVM replay on the audit corpus, scored on the card served (`QaQuestionBankEvalTest`,
`ServeDecisionEvalTest`, `HybridServeDecisionEvalTest`). "Exact" is the labelled card;
"module" allows a neighbouring card in the same module family.

| Set | Where | Mode | Exact | Module | Wrong family | False refusals |
|---|---|---|---|---|---|---|
| QA bank, 100 questions | JVM replay | BM25 + dense | 79/95 | 84/95 | 3 | 8 |
| QA bank, 100 questions | emulator, assisted | BM25 + dense | 78/95 | 83/95 | 3 | 9 |
| QA bank, 100 questions | emulator, direct | BM25 only | 69/95 | 75/95 | 2 | 18 |
| QA bank, second run of 100 | JVM replay | BM25 only | 72/95 | 78/95 | 2 | 15 |

Before the veto change the QA team's device stood at 65/95 with 18 false refusals on the
first bank, and the JVM replay at 60/95 with 20. Off-topic questions are refused 5 of 5 in
every mode. Direct mode runs without dense retrieval because the encoder ships with the
"simple words" opt-in; the emulator rows above are the same build and the same gate.
