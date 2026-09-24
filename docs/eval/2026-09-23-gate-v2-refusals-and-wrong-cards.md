# Why twelve questions still miss: the 9 refusals and 3 wrong cards

Emulator run of 22 September 2026, Android_13 AVD, `ON_DEVICE_ASSISTED`, BM25 + dense, the
QA team's 100-question bank (`sdk-android/src/test/resources/retrieval/qa_questions_bn.json`,
source `qa-uc2-2026-09-17`). Per-question traces: `ignored/_chat-audit/gate-v2-assisted/logs/<id>.log`.
Card metadata below is quoted from `sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json`.

Every question is grouped by the single thing that decided it. For each: the question, the
card the QA team expected with its authored metadata, the cards retrieval actually put in
front of the gate with their scores and the gate's own evidence line, and what kind of fix
would change the outcome.

Cosines are the device encoder's. `terms=` is the gate's list of shared clinical words after
population, template and unit words are removed. `titleHint` counts how many of those sit in
the card's title, hints or authored questions.

**Summary**

| Cause | Questions | Fix class |
|---|---|---|
| A. Expected card is trainer-session text and is excluded from the index | q011, q022, q033, q055 | corpus |
| B. Card-title word is not in the gazetteer, so an exact title match has no evidence | q034, q043, q066 | retrieval vocabulary |
| C. One shared word, scoped card, question names nobody, cosine just under dominance | q023, q036 | by design, see note |
| D. Deny-list word with two meanings | q078 | scope list |
| E. A marginal cosine on a lower-scored card outranks an exact-title match | q004, q026 | gate ranking |

---

## A. The expected card is not in the index: 4 questions

`ModuleCorpusParser` drops any card whose body reads as a facilitator's session guide
(`ModuleCorpusParser.kt:45-58`, markers such as "বোর্ডে লিখবেন", "অংশগ্রহণকারী", "সেশন শেষ"),
logging `excluding facilitator-guide card` at index build (`:171`). Across the 199-card corpus
exactly four cards trip it, and they are the four immunisation cards these questions expect.

| Card | Title | Body (verbatim start) | Marker |
|---|---|---|---|
| `8f41be1a:0` | ইপিআই কার্যক্রমের উদ্দেশ্য ও লক্ষ্যিত জনগোষ্ঠী | "প্রশিক্ষক প্রথমে সবাইকে শুভেচ্ছা জানিয়ে সেশন শুরু করবেন এবং ইপিআই সর্ম্পকে একটি ধারণা দিবেন। … বোর্ডে লিখবেন এবং সকলকে বলতে বলবেন।" | বোর্ডে লিখবেন |
| `8f41be1a:1` | শিশুর টিকা দিয়ে প্রতিরোধযোগ্য রোগসমূহ | "আমরা বাচ্চাদের কোন কোন রোগের জন্য টিকা দিই, সেই রোগগুলোর নাম জানতে হবে। … তিনি তা বোর্ডে লিখবেন।" | বোর্ডে লিখবেন |
| `8f41be1a:2` | শিশুর টিকার সিডিউল | "… কয়েকজনকে বলতে বলবেন এবং তিনি তা বোর্ডে লিখবেন। … টিকার সিডিউল এর পোষ্টার উপস্থাপন করবেন।" | বোর্ডে লিখবেন |
| `8f41be1a:4` | মাঠ পর্যায়ে ইপিআই কার্যক্রম বাস্তবায়নে স্বাস্থ্যকর্মীদের করণীয় | "… আলোচনা করবেন এবং অংশগ্রহণকারীদের জ্ঞান যাচাই করে সেশন শেষ করবেন।" | অংশগ্রহণকারী |

The hints, questions and keywords on these cards are good ("ইপিআই কি", "শিশুর টিকার সময়সূচী",
"কোন টিকা কখন দিতে হয়"). The bodies are not answers; they tell a trainer how to run the
session. Serving them would tell a CHW to greet the participants and write on the board. The
index is right to drop them, and no change to retrieval can surface a card that is not indexed.

**What the gate saw instead**

| Question | Top candidates (BM25 score, cosine) | Gate |
|---|---|---|
| `q011` ইপিআই কার্যক্রমের উদ্দেশ্য ও লক্ষ্যিত জনগোষ্ঠী কী? | `dcde24a9:3` malaria risk groups 57.6; `8528ce04:4` TB patient types 55.6; `8528ce04:9` 49.0. Dense top 0.31. | refuse, `terms=[জনগোষ্ঠী]` on the malaria card only |
| `q033` ভিজিটে শিশুর টিকার সিডিউল দেখেছি | `7e6098c0:9` women's TT schedule 144.4; `7e6098c0:2` ANC visit 97.2; `8f41be1a:3` vaccine side effects 65.7. Dense top 0.43. | refuse; the two ANC cards vetoed (question names child), side-effects card `terms=[টিকা]` |
| `q055` মাঠ পর্যায়ে ইপিআই কার্যক্রম বাস্তবায়নে… | `7e6098c0:2` ANC visit 128.5; `8f41be1a:3` 53.4; `e41cd447:8` breast problems 52.0. Dense top 0.38. | refuse, no terms on any |
| `q022` শিশুর টিকা দিয়ে প্রতিরোধযোগ্য রোগসমূহ | `8528ce04:8` TB prevention advice 92.2 / 0.56; `812346ad:5` newborn vaccination 67.4; `7e6098c0:8` ANC TT vaccine 63.4 | **served** TB prevention, `terms=[টিকা]` |

`q022` is the one wrong serve in this group. With the right card absent, the best remaining
match by word overlap is the TB module's prevention card, which also shares টিকা, and it is
unscoped, so the veto has nothing to say. `q033` is a good refusal in disguise: the top two
hits are the women's tetanus schedule and an ANC card, and the child-versus-maternal veto is
exactly what stopped them.

**Fix:** content. The immunisation module needs its four card bodies re-authored as
answers. Nothing else will move these four, and the QA team's own device refused or missed
them for the same reason.

---

## B. Exact title match, but the title word is not a clinical term: 3 questions

The gate counts evidence only over gazetteer terms. `ScopeClassifier.buildFrom`
(`ScopeClassifier.kt:206-236`) builds that gazetteer from the static seed, the synonym map and
words split from **module** titles (`:221`). Card titles are not read. The metadata harvest
(`:245-254`) reads `keywords_bn`, `topic_tags` and `clinical_conditions` as flat arrays, but
this corpus stores them as `{bn: [...]}` objects, so it reads nothing. A word that appears only
in a card title and its keywords is therefore invisible to the gate, however strongly BM25 and
the encoder both point at the card.

| Question | Expected card, rank 1 in BM25 | Score / cosine | Gate evidence on it | Word the gate cannot see |
|---|---|---|---|---|
| `q034` মাকে উচ্চতা পরিমাপের পদ্ধতি ও করণীয় সম্পর্কে কী কাউন্সেলিং দিব? | `6818eb87:3` উচ্চতা পরিমাপের পদ্ধতি ও করণীয়, keywords `উচ্চতা পরিমাপ, মেজারিং টেপ, …` | 230.6 / 0.67 | `terms=[] titleHint=0 population-only population-unstated cos=0.67` | উচ্চতা. Module title is "গর্ভকালীন চেকআপের গুরুত্ব", so only those words were harvested. |
| `q043` ভিজিটে নিউমোনিয়া কী এবং এর লক্ষণ দেখেছি | `7a1f98e9:3` নিউমোনিয়া কী এবং এর লক্ষণ, keywords `নিউমোনিয়া, ফুসফুসের সংক্রমণ, দ্রুত শ্বাস, …` | 143.5 / 0.45 | `terms=[] titleHint=0 population-only` | নিউমোনিয়া. Module title is "শ্বাসতন্ত্র কী?". লক্ষণ is a template word by design. |
| `q066` রোগী নাড়ির গতি দেখার পদ্ধতি ও স্বাভাবিক মাত্রা জানতে চেয়েছে | `6818eb87:6` নাড়ির গতি দেখার পদ্ধতি ও স্বাভাবিক মাত্রা, keywords `নাড়ির গতি, পালস রেট, …` | 367.7 / 0.62 | `terms=[] titleHint=0 population-only population-unstated cos=0.62` | নাড়ি. |

In all three the encoder agreed with BM25, and in `q034` the cosine even cleared the 0.65
dominance floor. It was not dominant because the runner-up was `47ea6019:1`, the **twin**
module's height-measurement card, at 0.66. The two ANC-examination modules (`6818eb87` and
`47ea6019`) carry near-identical cards, so they split the cosine and defeat the
0.03 margin (`ServeTuning.cosDominantMargin`, `MicroCoachingConfig.kt:480`). The same twin
split shows in `q066`: 0.62 against the twin's 0.60.

**Fix:** retrieval vocabulary, plus content. Reading card titles and the nested keyword
objects into the gazetteer would give each of these one or two terms and serve all three. The
attempt on 22 September showed that widening the vocabulary needs the matcher tightened at the
same time (no reverse containment, no card-level keyword phrases), because the first try let
"টিকা" match "মাটিকাটা". Separately, de-duplicating the twin ANC modules would restore the
dominance margin on `q034` and `q066` without any code change.

---

## C. One shared word on a scoped card, question names nobody: 2 questions

The rule at `ServeDecision.kt:112`: a card scoped to a population, asked about by a question
that names no population, needs two shared topic terms or a dominant cosine. It exists so that
"what are the symptoms of high blood pressure" is not answered with the antenatal card on the
single shared word রক্তচাপ.

| Question | Expected card, rank 1 in BM25 | Score / cosine | Gate evidence |
|---|---|---|---|
| `q023` ভিজিটে স্বাভাবিক ও অস্বাভাবিক ওজন দেখেছি | `6818eb87:2` স্বাভাবিক ও অস্বাভাবিক ওজন, keywords `স্বাভাবিক ওজন, অস্বাভাবিক ওজন, পূর্ণ বয়স্ক, নবজাতক, …` | 203.6 / 0.60 | `terms=[ওজন] titleHint=1 population-unstated cos=0.60` |
| `q036` ৬ মাস পূর্ণ বয়সের পর বাড়তি খাবার শুরু করার কারণ কী? | `0fa2eaf5:3` same title, keywords `৬ মাস, বাড়তি খাবার, মায়ের দুধ, …` | 580.6 / 0.64 | `terms=[খাবার] titleHint=1 population-unstated cos=0.64` |

`q036` missed dominance by a hundredth: 0.64 on the device against a 0.65 floor, where the
JVM fixture computed 0.67 for the same question and served it. That is the quantized encoder's
run-to-run margin, and it says the floor sits close to the noise.

Both cards are scoped because their hints say গর্ভবতী or শিশু. Both questions name nobody.
The one shared word is real evidence, but so is রক্তচাপ in the hypertension case, and the
gate cannot tell the two apart by count alone. The keyword phrases that would make the
difference, "বাড়তি খাবার" and "অস্বাভাবিক ওজন", are exactly the card-level keywords
the gazetteer does not read (group B). With them, each question would carry two terms.

**Fix:** the same vocabulary work as B turns these into two-term matches. Lowering the
dominance floor or dropping the two-term rule would serve them today, and would also serve the
antenatal card to the hypertension question.

---

## D. A deny-list word with two meanings: 1 question

`q078` শিশুর জন্য বিভিন্ন ধরনের খাদ্য নির্বাচন থাকলে referral করা উচিত কি? was refused before
retrieval by the L0 deny-list. The term that matched is নির্বাচন (`ScopeClassifier.kt:154`),
listed next to রাজনীতি because it means election. It also means selection, and here it is
"খাদ্য নির্বাচন", food selection. The expected card `0fa2eaf5:7` শিশুর জন্য বিভিন্ন ধরনের
খাদ্য নির্বাচন has the phrase in its title.

**Fix:** scope list. Either drop the standalone word or require a political co-term.

---

## E. A marginal cosine outranks an exact-title match: 2 questions

Both wrong serves in this group had the right card at BM25 rank 1 with roughly double the
winner's score. The winner got in and won on the dense channel with a cosine barely over the
0.50 floor, while the right card's cosine was also above the floor but outside the top three,
so it earned no dense credit.

### `q004` মাকে নবজাতকের শারীরিক পরীক্ষা সম্পর্কে কী কাউন্সেলিং দিব?

| Card | Title | BM25 | Cosine (device fixture, rank) | In dense top 3? |
|---|---|---|---|---|
| `d12af13b:0` expected | নবজাতকের শারীরিক পরীক্ষা, keywords `নবজাতক, শারীরিক পরীক্ষা, ওজন, শ্বাসের গতি, …` | **180.9**, rank 1 | 0.511, rank 4 | no |
| `5cec984f:0` twin | নবজাতকের পরীক্ষা-নিরীক্ষা | 99.2, rank 2 | 0.488, rank 6 | no |
| `7e6098c0:3` **served** | গর্ভকালীন পরিচর্যার সেবা সমূহ (ANC services), hints `শারীরিক পরীক্ষা গর্ভকালীন, …` | 88.5, rank 3 | 0.543, rank 3 | yes |

Gate line: `serve 7e6098c0:card:3 score=88.5 terms=[নবজাতক, শারীরিক, নবজাতকের] titleHint=3 cos=0.51`.

The ANC card scores under the 55 percent band (`0.55 × 180.9 = 99.5`), so on words alone it
would not even compete. It enters the band through the dense-agreement exception
(`ServeDecision.kt:271`), then ties the expected card on topic terms and title hints, and wins
at the fifth comparator key, dense agreement (`:287`), because the expected card's 0.511 sat at
dense rank 4 and `denseTopK` is 3 (`MicroCoachingConfig.kt:478`). Raw BM25 score, 180.9
against 88.5, is the sixth key and never reached.

### `q026` নবজাতকের নাভীর যত্ন কী?

| Card | Title | BM25 | Cosine (device fixture, rank) | In dense top 3? |
|---|---|---|---|---|
| `5cec984f:2` expected | নবজাতকের নাভীর যত্ন | **214.6**, rank 1 | 0.513, rank 6 | no |
| `d12af13b:2` expected twin | নবজাতকের নাভীর যত্ন | **214.4**, rank 2 | 0.521, rank 5 | no |
| `812346ad:3` **served** | নবজাতকের ৬ ঘন্টা থেকে ১ মাস পর্যন্ত সেবা, hint `নাভীতে কিছু না লাগানো` | 105.3, rank 3 | 0.537, rank 4 on the fixture, top 3 on this run | yes |

Gate line: `serve 812346ad:card:3 score=105.3 terms=[নবজাতক, নাভী, নাভীর, নবজাতকের] concepts=[umbilical_cord] titleHint=4 cos=0.54`.

Same mechanism. Two exact-title cards at 214 lose to a general newborn-care card at 105 that
mentions the umbilical cord in one hint, because that card alone had a cosine inside the top
three. The twin modules make it worse: the encoder's attention is split between two identical
cards at 0.51 and 0.52, and the served card at 0.54 slips above both.

**Fix:** gate ranking, two candidates. Either give dense credit to every candidate whose cosine
clears the floor rather than only the top three, so the expected cards here would also "agree"
and raw score would decide; or keep the dense-agreement tie-break below raw score when the
score gap exceeds the band. Either is one line in the comparator and needs the four-set
measurement before it lands.

---

## On "symptoms of high blood pressure"

The corpus has ten cards whose title, hints or keywords mention রক্তচাপ. All ten are maternal:
antenatal blood-pressure measurement (`47ea6019:7`), antenatal abnormal blood pressure
(`47ea6019:8`), antenatal hypertension management and emergency referral (`cb824f82:2`, `:4`),
antenatal oedema (`47ea6019:5`, `:6`, `6818eb87:7`), postnatal examination (`dfd06f1e:4`) and
two ANC record cards. There is no card about hypertension in a general adult. So for that
question there is no right card to prefer over the antenatal one, and a refusal is the correct
outcome, which is what the gate produces. The moment an adult NCD module is published, its
hypertension card will carry terms like উচ্চ রক্তচাপ and লক্ষণ in its own title and hints,
outscore the antenatal card on both BM25 and cosine, and win on words.
