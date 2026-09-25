# The serve gate: which card is offered, and whether it is served

**Status:** current for `feat/hybrid-dense-retrieval`

Between retrieval and the answer sit three steps. Each is a pure function, and each writes
one trace line.

```
BM25 top-k ──┐
             ├─► fusion ─► RANKER ─► GATE ─► (direct)   the card, verbatim
dense top-k ─┘                              (assisted) model rewrite ─► post-model checks ─► shown
  trace:      FUSED     RANK       GATE                 LLM raw         POST
```

| Stage | Input | Output | Can it change the card? |
|---|---|---|---|
| Fusion (`GroundingSelector`) | BM25 hits, dense hits | up to 3 candidates in fused order | yes, by ordering |
| Ranker (`CardRanker`) | the fused candidates and their evidence | one card | yes, by choosing |
| Gate (`ServeGate`) | that one card's evidence | serve or refuse, with a reason | only by refusing |
| Model rewrite | the served card | an English answer | no |
| Post-model checks | the answer and the served card | the answer, or the card verbatim | no; they choose the text, never the card |

The policy: **a card is served only when it demonstrably shares the question's subject**.
Otherwise the chat refuses, because a CHW cannot tell a confidently served wrong card from
a right one. Both on-device modes run the gate before any model, so a refusal costs no
inference and the model only rewords a card that passed.

---

## 1. Evidence (`CardEvidence`)

Computed once per turn and read by both the ranker and the gate, so a card is never judged
on facts the ranker did not see.

**From the question:**

- **Topic tokens:** its content words minus demographic words, question-form words
  (`TEMPLATE_TERMS`: symptoms, cause, advice, method and the like), numbers and units.
- **Gazetteer terms:** which of those words are known clinical terms. The gazetteer is the
  static clinical seed, the synonym map, and words harvested from module titles. See
  [gate-vocabulary.md](./gate-vocabulary.md).
- **Concepts:** the synonym groups the words map to, so "ইডিমা" and "পানি আসা" both become
  `edema`. Concepts reachable from a population marker count as population, never as topic.
- **Populations:** whether it names the maternal group, the child group, the adult group,
  several, or none.

**For each candidate:**

- the shared condition terms and shared concepts
- `titleHint`: how many of those sit in the card's title, hints or questions
- `populationVeto`: the card is scoped to a population the question does not name
- `populationUnstated`: the card is scoped and the question names nobody
- the dense channels: cosine, agrees (≥ 0.50), and dominant (≥ 0.65 with a 0.03 lead)

A card's scope is read from its title and hints only. Bodies mention mothers and children in
passing on cards that answer anyone.

---

## 2. The ranker (`CardRanker`)

It picks one card from the fused candidates. **Every candidate competes, servable or not.**
Choosing a card and judging it are separate steps.

The competitors are the cards within 55 percent of the top BM25 score (`promoteRatio`), plus
any card with dense agreement, since a dense-only entrant has no BM25 score to clear the ratio
with. The first difference wins:

1. dense dominant
2. has any topic term, so a card about the subject beats one about only the population
3. title-hint overlap, since authors write hints as the questions a card answers
4. condition terms plus concepts
5. dense agrees
6. raw BM25 score

---

## 3. The gate (`ServeGate`)

It judges only the ranker's pick. The first rule that applies decides:

| Rule | Result |
|---|---|
| Card scoped to a population, question names a **different** one | refuse, `NO_EVIDENCE` |
| Dense dominant | serve |
| Card scoped, question names **nobody** | serve only on 2 topic terms, else `NO_EVIDENCE` |
| Dense agrees | serve |
| Question has no topic words at all | serve on any shared term or concept, else `NO_EVIDENCE` |
| Otherwise | serve on 1 topic term, or 2 when the question offers 3 or more, else `NO_EVIDENCE` |

A served pick must then score at least 25 on a Bangla turn or 40 on an English one
(`bnScoreFloor`, `enScoreFloor`), unless it has dense agreement. Otherwise the gate refuses
with `BELOW_SCORE_FLOOR`.

**A refused pick refuses the turn.** The gate never serves a lower candidate in its place. If
the ranker picked the wrong card, the fix belongs in fusion or the ranker, and the trace
shows which.

The third rule is the compromise between two failures. One is refusing every card-title
question that names no population. The other is serving an antenatal blood-pressure card to
"what are the symptoms of high blood pressure" on one shared word.

---

## 4. The population veto

A card written for one population does not answer a question about another. "Blocked" means
the gate refuses the card whatever else it has going for it.

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
card.

---

## 5. After the gate

In direct mode the served card is shown verbatim. In assisted mode the model rewrites it, and
four checks run on the answer in this order:

1. **Groundedness:** the share of the answer's content words found in the card. It must reach
   0.25 when retrieval is strong, or 0.35 otherwise.
2. **The output validator:** rejects empty text, the length cap, an answer that echoes the
   question, a diagnostic phrase, or a drug or dosage the card does not contain.
3. **Translation** of the English answer to Bangla.
4. **The Latin-share guard:** if the translation is still over 30 percent Latin characters,
   the English answer is shown with a "translation unavailable" prefix.

If step 1 or 2 rejects the answer, the served card is shown verbatim instead. **Nothing after
the gate refuses a card the gate passed.**

---

## 6. Every way a turn can refuse

| Where | Reason | Trigger |
|---|---|---|
| Before retrieval | L0 deny-list | The question contains a term from `ScopeClassifier.DENY_TERMS` (weather, sports, recipes). |
| Gate | `NO_HITS` | BM25 returned nothing above 1.5 and dense added nothing. |
| Gate | `NO_EVIDENCE` | The ranker's pick fails the gate's rules in section 3. |
| Gate | `BELOW_SCORE_FLOOR` | The pick scores under 25 (BN) or 40 (EN) and has no dense agreement. |

---

## 7. Worked example

A question from the QA bank, the card's own title with a template suffix:

> ৬ মাস পূর্ণ বয়সের পর বাড়তি খাবার শুরু করার কারণ কী? SK হিসেবে আমি রোগীকে কী বলব?

The expected card is the complementary-feeding card with that exact title. Its hints mention
শিশু, so it is scoped to the child group. The question names no population.

The ranker picks it: it has a dominant cosine, key 1. The gate then applies section 3:

- **With dense on,** the cosine of 0.67 is dominant, the second rule fires, and it is
  served: `GATE serve 0fa2eaf5:3`.
- **With dense off** (a device without the encoder), the card has one topic term, খাবার, and
  the question names nobody, so the third rule asks for two:
  `GATE refuse 0fa2eaf5:3 reason=NO_EVIDENCE`. This is the accepted cost of not serving
  "symptoms of high blood pressure" an antenatal card on one word.

A counter-example that must refuse, from the trainer's set:

> What are the symptoms of high blood pressure?

The antenatal blood-pressure danger-sign card scores 163.8 and shares রক্তচাপ: one topic
term, a scoped card, no population named. The ranker picks it and the gate refuses it,
correctly. The corpus has no general hypertension card, and the CHW would otherwise be told
about pregnancy.

---

## 8. Reading the trace

Four lines from one assisted turn on the emulator:

```
FUSED n=3 order=[952fbce0:7 bm25=1/177.4 dense=2/0.57, e660517c:2 bm25=2/173.6 dense=1/0.57, 952fbce0:0 bm25=3/111.6 dense=-]
RANK pick=952fbce0:7 fused=1 band=[952fbce0:7,e660517c:2,952fbce0:0] | 952fbce0-…:card:7 score=177.4 terms=[ডায়রিয়া, ডায়রিয়ার] concepts=[diarrhoea] titleHint=2 cos=0.57 | …
GATE serve 952fbce0:7
POST groundedness=0.70 floor=0.25 validator=ok translation=ok shown=rewrite
```

| Line | Field | Meaning |
|---|---|---|
| `FUSED` | `<key> bm25=<rank>/<score> dense=<rank>/<cos>` | each candidate in fused order; `-` when a channel did not return it |
| `RANK` | `pick`, `fused`, `band` | the chosen card, its position in the fused order, the cards that competed |
| `RANK` | after `|` | every candidate's evidence (below) |
| `GATE` | `serve <key>` or `refuse <key> reason=…` | the verdict on the pick; `refuse -` when nothing was picked |
| `POST` | `groundedness`, `floor` | the groundedness score and the floor it was held to |
| `POST` | `validator` | `ok`, the rejection reason, or `skipped` when groundedness already rejected |
| `POST` | `translation` | `ok`, `passthrough` (English shown untranslated), `degraded`, or `n/a` in English mode |
| `POST` | `shown` | `rewrite` or `card` |

Card keys are `family8:index`. The evidence fields on the `RANK` line:

| Field | Meaning |
|---|---|
| `<family>:card:<n>` | module family id and 0-based card index |
| `score` | the card's BM25 score, unchanged by fusion |
| `terms` | shared gazetteer terms |
| `concepts` | shared synonym concepts |
| `titleHint` | how many shared terms sit in the card's title or hints |
| `population-only` | the shared evidence is all population words |
| `POPULATION-VETO` | the card is scoped to a population the question does not name |
| `population-unstated` | the card is scoped and the question names nobody |
| `cos`, `cos-dominant` | the dense cosine, and whether it cleared the dominance floor and margin |

---

## 9. Measured on the question banks

JVM replay on the audit corpus, scored on the card served (`QaQuestionBankEvalTest`).
"Exact" is the labelled card; "module" allows a neighbouring card in the same module family.

| Set | Mode | Exact | Module | Wrong family | False refusals |
|---|---|---|---|---|---|
| QA bank, first 100 | BM25 + dense | 78/95 | 80/95 | 3 | 12 |
| QA bank, first 100 | BM25 only | 71/95 | 73/95 | 1 | 21 |
| QA bank, second 100 | BM25 only | 72/95 | 74/95 | 1 | 20 |

Off-topic questions are refused 5 of 5 in every mode.

Separating the ranker from the gate moved these numbers compared with the previous gate,
which fell through to a lower candidate when its first choice failed:
- Wrong-family serves did not grow in any set, and fell on the trainer's sets.
- Off-topic questions refused on the CHW set rose from 8 of 10 to 10 of 10.
- On the first QA bank, false refusals went from 8 to 12. Of the 4 questions that now
  refuse, 3 used to be served a neighbouring card and 1 the exact card.

The trace now names the refused pick, so each of those can be traced to the ranker's choice.
