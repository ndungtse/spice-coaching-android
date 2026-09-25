# The vocabulary sets behind retrieval and the serve gate

**Status:** current for `feat/hybrid-dense-retrieval`. Coverage numbers are measured on the
audit corpus (`sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json`, 26
modules, 199 cards).

The chat never compares a question to a card as raw text. Every comparison goes through a
named set of words: some are hard-coded in the SDK, some are built from the module corpus at
index time. This page lists each set, where it comes from, what it is used for, and how well
it covers the current corpus. [retrieval.md](./retrieval.md) explains the ranking and
[serve-gate.md](./serve-gate.md) the decision; this page explains the words both of them
read.

---

## 1. The map

| Set | Defined in | Origin | Size here | Read by | Job |
|---|---|---|---|---|---|
| Stop-words | `BanglaTokenizer.STOPWORDS` | static | 177 | every query tokeniser | drop function words from questions, never from cards |
| Deny list | `ScopeClassifier.DENY_TERMS` | static | 96 | L0, before retrieval | refuse off-topic questions outright |
| **Gazetteer** | `ScopeClassifier.scopeTerms()` | static seed + synonym aliases + harvested module words | 299 | L1 in-scope check; gate evidence | "the clinical words we recognise" |
| Static seed | `ScopeClassifier.STATIC_TERMS` | static | 170 | gazetteer | pilot-era clinical vocabulary, EN and BN |
| Harvest filter | `ScopeClassifier.GENERIC_HARVEST_STOPWORDS` | static | 25 | gazetteer build | keep framing words out of the harvest |
| Synonym concepts | `ClinicalSynonymMap.GROUPS` | static | 13 concepts, 80 aliases | BM25 expansion; gazetteer; gate concepts; groundedness | bridge spellings and BN↔EN |
| Bridges | `ClinicalSynonymMap.BRIDGES` | static + per-module `synonyms` | 14 static | BM25 expansion only | abbreviation → words |
| Template words | `CardEvidence.TEMPLATE_TERMS` | static | 104 | gate | words that say what *form* of answer is wanted |
| Demographic words | `CardEvidence.DEMOGRAPHIC_TERMS` | static | 36 | gate | words that say *who*, not *what* |
| Units | `CardEvidence.UNIT_TERMS` | static | 14 | gate | numbers and units are not topics |
| Population groups | `CardEvidence.POPULATION_GROUPS` | static | 3 groups | gate | veto, unstated rule, topic discount |
| Population concepts | `CardEvidence.POPULATION_CONCEPTS` | derived from the groups | 2 | gate | concepts that only say who or when |
| Facilitator markers | `ModuleCorpusParser.FACILITATOR_MARKERS` | static | 6 | index build | drop trainer-script cards |

The gazetteer is the one that matters most and the only one partly built from the modules.

---

## 2. Each set

### 2.1 Tokens and stop-words

`BanglaTokenizer` (`ai/retrieval/BanglaTokenizer.kt`) is the single tokeniser for cards and
questions. It normalises to NFC, folds Bengali digits to ASCII, splits on whitespace and
punctuation while keeping "140/90" whole, lowercases, emits Bangla character bigrams beside
each word so টিকার still meets টিকা, and dual-emits English Porter stems.

`STOPWORDS` (`:71`) are removed from **questions only** (`tokenizeQuery`, `:113`): cards keep
them so BM25 length statistics stay honest. They are question words, pronouns, auxiliaries and
conjunctions in both languages. Clinical qualifiers such as low, high, severe, না are
deliberately not listed.

### 2.2 The deny list (L0)

`DENY_TERMS` (`ScopeClassifier.kt:139`) is a static list of topics a small model will answer
from pre-training if allowed: coding, sports, weather, politics, entertainment, creative
writing, farming, business, cooking. A single-word entry must match a whole token; a
multi-word entry matches as a substring. A hit refuses before retrieval runs, in every mode.

Entries are single words on purpose, and that makes homonyms a risk: নির্বাচন is both
"election" and "selection", so "খাদ্য নির্বাচন" (choosing food) is refused. The list is
maintained by hand; a false refusal from it is fixed by editing the list.

### 2.3 The gazetteer

Built once per index by `ScopeClassifier.buildFrom` (`:206`) from three layers:

1. **Static seed**, `STATIC_TERMS` (`:70`): 170 hand-written terms grouped by pilot domain:
   hypertension, diabetes, maternal and ANC, newborn and child, eye care, family planning,
   danger signs and referral, nutrition, the SPICE app surface, CHW workflow, malaria, TB and
   sepsis, cancer screening, low birth weight, plus Bangla equivalents added after field
   testing (ডায়রিয়া, অ্যানিমিয়া, বুক, টান, ডিহাইড্রেশন, দফা and others).
2. **Every alias of every synonym concept**, `ClinicalSynonymMap.allTerms`: 80 words.
3. **Harvested from the module corpus**: each module's `domain` and `subDomain`, and every
   word of each module's Bangla and English title that is at least three characters and is
   not a stop-word or a `GENERIC_HARVEST_STOPWORDS` entry (`:199`: nature, type, overview,
   importance, process, introduction and the like). The harvest also tries to read module
   metadata keys `keywords_en`, `keywords_bn`, `topic_tags`, `clinical_conditions` as flat
   arrays (`harvestMetadataTerms`, `:245`).

What is **not** harvested: card titles, card hints, card questions, card keywords. And the
metadata read at step 3 finds nothing in the current corpus, because every module ships the
nested shape `keywords: {bn: [...]}` rather than a flat `keywords_bn` array. The index
parser handles both shapes (`ModuleCorpusParser.parseSearchMetadata`, `:79`); the gazetteer
harvest handles only the flat one, so today the module metadata reaches BM25 and never
reaches the gate.

For the audit corpus the harvest contributes 79 words. Titles are split on whitespace only, so
some carry punctuation or inflection and some are filler that survived the two filters:

```
ডেঙ্গু  ম্যালেরিয়া  যক্ষ্মা  নবজাতক  শালদুধ  রক্তক্ষরণ  পরিচর্যা  ওজন  বিকাশ      ← useful
কি?  কী?  মা,  বলে  সাধারণ  কার্ডের  সময়ের  তথ্য  হওয়া  যাওয়া  (rdt)          ← noise
```

The noise is why `TEMPLATE_TERMS` grew its "verbal and function words" and "relational words"
sections: words like গুরুত্ব, পরবর্তী, সময়ের enter the gazetteer through module titles and
have to be subtracted again in the gate.

**Where it is read.** Two places:

- `ScopeClassifier.isInScope` (`:26`): the L1 check. Any gazetteer term anywhere in the
  question means in scope. Only Strict mode refuses on it; ExtendedClinical lets retrieval
  decide.
- `CardEvidence.gazetteerTermsIn` (`CardEvidence.kt:354`): which gazetteer terms the
  question contains. After subtracting template and demographic words this is the question's
  **term list**, and a card's `terms=[…]` in the trace is the subset of that list found in the
  card's title, body, hints or questions.

**Matching rules** (`gazetteerTermsIn`, and `termInText` for the card side):

| Term shape | Matches when |
|---|---|
| contains a space ("blood pressure") | substring of the lowercased text |
| ASCII, one word | equal to a whole token |
| Bangla, 4+ characters | contained in a token, or (question side) contains a 4+ character token |
| Bangla, under 4 characters | a token starts with it |

Containment is what lets উচ্চরক্তচাপের match রক্তচাপ, and also what let টিকা match মাটিকাটা
when the gazetteer was widened on 22 September. Widening the vocabulary and tightening the
matcher have to happen together.

### 2.4 Synonym concepts and bridges

`ClinicalSynonymMap.GROUPS` (`ClinicalSynonymMap.kt:14`) holds 13 concepts, each a set of
aliases across English, Bangla and common variants:

```
anaemia  diarrhoea  chest_indrawing  dehydration  jaundice  sepsis  malnutrition
referral  anc_visit  edema  postpartum  vaginal_discharge  umbilical_cord
```

A concept is "in" a text when any alias matches it: multi-word aliases as a phrase, English
aliases as a whole token, Bangla aliases as a prefix (বুকের matches বুক). Four readers:

- **BM25 query expansion** (`expandQueryWeighted`): the CHW's own words weigh 1.0, aliases
  of any concept the question touches join at 0.4, so they widen recall without outvoting the
  typed words. The TITLE field is scored on the typed words only.
- **Gazetteer**: every alias is a gazetteer term.
- **Gate**: `concepts=[…]` in the trace is the intersection of the question's concepts and
  the card's. A shared concept makes a card servable but does not rank it, because concepts
  are broad by design (postpartum spans half the PNC titles).
- **Groundedness**: an answer token counts as grounded if it shares a concept with the card,
  so anemia grounds on anaemia.

`BRIDGES` (`:83`) are one-directional: pw → pregnant woman, bp and বিপি and প্রেসার →
রক্তচাপ, htn, hb, ors, fits → convulsion, itn → mosquito net. They feed expansion only. A
bridge whose source word does not exist anywhere in the corpus gets full weight 1.0, because
then it is the only path to any card. Modules add their own bridges through
`search_metadata.synonyms` (for example DOT → the full phrase), merged at index build.

### 2.5 Template, demographic and unit words

All in `CardEvidence.kt`, all static, all **subtracted** rather than matched.

- `TEMPLATE_TERMS` (`CardEvidence.kt:127`), 104 words. Question-form words (লক্ষণ, প্রতিরোধ, করণীয়,
  পরামর্শ, কারণ, symptoms, prevention, advice, cause), procedure words (পরিমাপ, পরীক্ষা,
  measure, test), near-universal clinical filler (রক্ত, উচ্চ, চাপ, blood, pressure, high,
  low), verbal words that reach the gazetteer through titles (দেওয়া, করা, গুরুত্ব), and
  relational words (পরবর্তী, আগের). Without this list every symptom card in the corpus
  shares লক্ষণ with every symptom question.
- `WHOLE_TERM_ONLY_TEMPLATE` (`:321`): the seven filler words (রক্ত, উচ্চ, চাপ, মাত্রা,
  পরিমাণ …) that sit inside real condition words. They are subtracted only when they are the
  whole term, so রক্তচাপ survives while রক্ত alone does not.
- `DEMOGRAPHIC_TERMS` (`:110`), 36 words: গর্ভবতী, মা, শিশু, রোগী, সেবাগ্রহীতা, pregnant,
  mother, child, patient and their inflections. A quarter of a maternal corpus mentions
  গর্ভবতী, so a match on it proves nothing.
- `UNIT_TERMS` (`:159`) and anything numeric: a shared mmHg says both texts quote a reading.

Consequence worth keeping in mind: "ডায়রিয়ার প্রধান কারণ" has exactly one topic word,
ডায়রিয়া. The question's own template words carry no evidence, by design, so a card is judged
on how many *subject* words it shares, and a question with one subject word needs to share
that one.

### 2.6 Populations

`POPULATION_GROUPS` (`CardEvidence.kt:171`): three groups of markers, both languages.

| Group | Markers |
|---|---|
| maternal | গর্ভ, গর্ভবতী, গর্ভকালীন, প্রসূতি, প্রসব, প্রসবোত্তর, মা, মায়ের, মহিলা, নারী, এএনসি, পিএনসি, pregnan, antenatal, anc, postpartum, postnatal, pnc, mother, maternal, woman, women, delivery, childbirth, labour, labor, puerperium |
| child | শিশু, বাচ্চা, নবজাতক, child, children, baby, newborn, neonat, infant |
| adult | সেবাগ্রহীতা, ক্লায়েন্ট, প্রাপ্তবয়স্ক, পুরুষ, বয়স্ক, client, adult, man, elderly |

A **card's** populations are read from its title, hints and questions only (`populationsIn`
over `titleHintText`, `:268`), never its body, because bodies mention mothers and children in
passing on cards that answer anyone. A **question's** populations are read from the whole
guard query, the typed text plus its translation, so an English question about "the mother"
and a Bangla one about মা land in the same group. Markers under four characters must match a
whole token, so মা does not fire inside মাপার.

`POPULATION_CONCEPTS` (`:196`) is derived by asking the synonym map which concepts the
markers touch; today that is `anc_visit` and `postpartum`. Those two concepts are discounted
when the gate counts topic evidence, so "postpartum" shared between a PNC question and a PNC
card counts as population, not subject.

The three uses are described in [serve-gate.md](./serve-gate.md): the veto (card scoped to
A, question names B), the unstated rule (card scoped, question names nobody: two topic terms
or a dominant cosine), and the topic discount (a match on a marker is not a match on a
subject).

### 2.7 Index exclusions

`ModuleCorpusParser` (`:30`, `:45`) drops a card from the index when its body is under 20
characters or contains one of six facilitator phrases (বোর্ডে লিখবেন, অংশগ্রহণকারী, সেশন
পরিচালনা, প্রশিক্ষণার্থী, বলতে বলবেন, সেশন শেষ). Each exclusion is logged at index build.
Four cards in the audit corpus are dropped, all in the immunisation module.

---

## 3. What a card offers, and where each field goes

Every card ships a title, a body and a `search_metadata` object with `retrieval_hints`,
`questions`, `keywords` and `synonyms`; every module ships `keywords`, `topic_tags`,
`clinical_conditions`, `search_phrases` and `synonyms`. This is where each one is read.

| Authored field | BM25 field and weight | Gate: evidence text (`terms`) | Gate: title/hint text (ranking, scope) | Gazetteer |
|---|---|---|---|---|
| card title | TITLE ×3.0, typed words only | yes | yes | **no** |
| card body | BODY ×1.0 | yes | no | no |
| card `retrieval_hints`, `questions` | QUESTION ×2.5 | yes | yes | **no** |
| card `keywords` | KEYWORD ×0.5 | **no** | **no** | **no** |
| module `keywords`, `topic_tags`, `clinical_conditions`, `search_phrases` | KEYWORD ×0.5, copied onto every card of the module | no | no | intended, **not today** (nested shape) |
| card and module `synonyms` | query expansion bridges | no | no | no |
| module title | not indexed | no | no | yes, split into words |

Field weights are `ModuleKnowledgeIndex.kt:252`; the copy of module metadata onto each card
is at `:369`. The evidence text is assembled at `CardEvidence.kt:259` and the
title/hint text at `:268`.

Read the two bold columns together and the shape of the problem is visible. The authored
keywords are what put a card at BM25 rank 1, and the gate cannot see them. The gate can only
count a shared word if that word is *also* in a 299-word gazetteer built from module titles
and a static list.

---

## 4. Coverage on the audit corpus

Measured with the same matching rules as `gazetteerTermsIn`, after subtracting template and
demographic words.

| Measure | Value |
|---|---|
| Gazetteer size for this corpus | 299 terms (170 static, 80 aliases, 79 harvested, minus overlap) |
| Card titles with **no** gazetteer term | 24 of 199 |
| Card titles with exactly one | 44 of 199 |
| Per-card `keywords` entries | 1,712 |
| Distinct keyword phrases containing no gazetteer term | 686 |

The 24 titles the gate cannot see at all:

```
পেরিনিয়ামের যত্ন (two modules)          উচ্চ রক্তচাপ ব্যবস্থাপনা
পিঠের নিম্নাংশে ব্যাথা                    কোষ্ঠ কাঠিন্য
ঘনঘন প্রসাব                              পায়ের স্ফীত শিরা
অর্শ্ব (পাইলস)                            পায়ে ব্যাথা বা পা কামড়ানো
গ্রোথ চার্ট … (three cards)              নিউমোনিয়া কী এবং এর লক্ষণ
ডট (DOT) … (two cards)                   পানিস্বল্পতা কী? / পানিস্বল্পতার স্তর নির্ণয়
ক্যাঙ্গারু মাদার কেয়ার … (three cards)   উচ্চতা পরিমাপের পদ্ধতি ও করণীয়
তাপমাত্রা … (two cards)                  নাড়ির গতি দেখার পদ্ধতি ও স্বাভাবিক মাত্রা
```

A third of the corpus, 68 cards, can be served on title evidence only if the question
happens to use a body word the gazetteer knows, or if the encoder agrees. When such a card is
also population-scoped and the question names nobody, the unstated rule asks for two terms
and only dense dominance can serve it.

This is the mechanism behind the false refusals in the QA runs. `q043` "ভিজিটে নিউমোনিয়া কী
এবং এর লক্ষণ দেখেছি": the pneumonia card is BM25 rank 1 at 143.5, lifted by its title and its
keywords (নিউমোনিয়া, ফুসফুসের সংক্রমণ, দ্রুত শ্বাস). The gate line reads `terms=[]
titleHint=0`, because নিউমোনিয়া is in neither the static list nor the module title
("শ্বাসতন্ত্র কী?"), and লক্ষণ is a template word. Refused with the right card on top.

The numbers say the same thing from the other side: 686 authored keyword phrases carry
vocabulary the gate does not know. The content team has already written the words; the SDK
reads them for ranking and ignores them for the decision.

---

## 5. What this means for changing the gate

The user-facing question is "can we lean on topics instead of on these lists". The lists split
into two kinds, and the answer differs.

**The subtraction lists must stay.** Template, demographic and population words are what stop
a card from winning on one shared filler word. Turning the veto off alone raised wrong-family
serves on the trainer set from 5 to 9 in English; the veto is the cheapest such rule and the
one that fires least. These lists are about the *question*, they are short, and they change
rarely.

**The gazetteer can go, or be rebuilt from the cards.** Its job in the gate is to answer "is
this shared word a real subject word". The corpus already answers that: a word an author put
in a card's title, hints, questions, keywords or `clinical_conditions` is a subject word for
that card. Three ways to get there, in order of size:

1. **Read the authored vocabulary as the evidence set.** Evidence for a card = topic tokens of
   the question (after the subtraction lists) that appear in that card's title, hints,
   questions or keywords, plus its module's `clinical_conditions`. No gazetteer membership
   required. The gazetteer keeps its L0/L1 role only. Per-card keywords are specific to the
   card; module-level lists are shared by every card of the module, so they should make a
   card servable but not rank it, the same treatment concepts get today. Keyword lists also
   contain template words (লক্ষণ, কারণ, ব্যবস্থাপনা appear as keywords), so the subtraction
   lists still apply to them.
2. **Fix the harvest**: add card titles and the nested metadata to the gazetteer, with the
   matcher tightened at the same time (no reverse containment, whole-word for short terms).
   This is the deferred item from the 22 September plan. It is smaller than option 1 but keeps
   a global word list, and a global list is what let টিকা meet মাটিকাটা.
3. **Trust the TITLE field**: a card whose BM25 TITLE contribution on the CHW's own words is
   non-zero already shares a title word. `explain()` exposes the per-field score. This is a
   one-line evidence source but says nothing about hints or keywords.

Either of the first two also removes the twin-module and near-miss cases in the eval doc that
are really this gap wearing a different hat (`q034`, `q066`, `q023`, `q036`).

**Was this the backend's job?** No. The backend metadata is present, well-formed and does its
work in BM25: it is why these cards rank first. The gap is on the device, in a harvester that
reads a schema the corpus stopped using and a gate that reads a list instead of the cards.
The change is small either way. If a backend change is ever wanted it would be a flat
per-module `vocabulary` array, but the parser already reads the nested shape, so the on-device
fix needs no backend release.

---

## Reading the trace with this page

```
RANK pick=7a1f98e9:3 fused=1 band=[…] | 7a1f98e9-…:card:3 score=143.5 terms=[] concepts=[] titleHint=0 population-only
GATE refuse 7a1f98e9:3 reason=NO_EVIDENCE
```

`terms` is section 2.3 after section 2.5's subtraction; `concepts` is section 2.4;
`titleHint` counts `terms` found in section 3's title/hint text; `population-only` means the
card and question share nothing but section 2.6's markers. A card with a high `score` and an
empty `terms` is the section 4 gap, not a ranking error.
