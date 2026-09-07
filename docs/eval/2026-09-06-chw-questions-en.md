# CHW question set — English (2026-09-06)

Thirty questions written against the 26 modules / 199 cards in
`sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json`, to test retrieval on
material the tuning has never seen. The trainer's original 26 questions are the set every
threshold in the pipeline was calibrated against, so scoring well on them says little; these
are deliberately phrased the way a health worker would ask rather than in the corpus's own
words, because vocabulary mismatch is exactly what dense retrieval is supposed to close.

**Twenty-five are answerable and five are not.** The five exist because a system that never
refuses looks perfect on an answerable-only set, and the failure mode we already measured
for dense retrieval is the opposite of a miss — it serves a confidently wrong card. A CHW
cannot tell a wrong answer from a right one, so a false serve costs more than a refusal.

Machine-readable form: `sdk-android/src/test/resources/retrieval/chw_questions_2026-09.json`.
Regenerate both with `ignored/qgen/generate.py`.

| # | Question | Expected card | What a right answer covers |
|---|---|---|---|
| 1 | A mother says her breasts feel hard, hot and painful and the baby cannot latch. What do I advise? | `11200183:1` প্রসব পরবর্তী সাধারণ সমস্যা: স্তন ফুলে যাওয়া ও <br>`a6032a14:8` প্রসব পরবর্তী জটিলতা: স্তনে ব্যথা ও ফোলা<br>`e41cd447:8` মায়ের স্তনের সাধারণ সমস্যা ও করণীয় | Engorged/painful breast: keep feeding, express milk, warm compress; refer if fever or abscess. |
| 2 | The mother wants to throw away the thick yellow milk that comes in the first days. Is she right? | `e41cd447:0` শালদুধ কি?<br>`e41cd447:2` শালদুধে মা ও শিশুর উপকারিতা | Colostrum must not be discarded — it is the baby's first immunity. |
| 3 | How soon after birth should the baby be put to the breast for the first time? | `e41cd447:1` কখন শালদুধ খাওয়াতে হবে?<br>`5cec984f:1` নবজাতকের বুকের দুধ খাওয়ানো<br>`d12af13b:1` নবজাতকের বুকের দুধ খাওয়ানো | Within the first hour of birth. |
| 4 | The baby's cord stump looks red and gives off a bad smell. What should I do? | `5cec984f:2` নবজাতকের নাভীর যত্ন<br>`d12af13b:2` নবজাতকের নাভীর যত্ন<br>`812346ad:4` নবজাতকের বিপদচিহ্নসমূহ এবং করণীয় | Cord infection is a danger sign — keep it dry, nothing applied, refer urgently. |
| 5 | A baby was born weighing 2.1 kg. What extra care does he need? | `d12af13b:5` কম জন্ম ওজনের শিশু কারা?<br>`d12af13b:6` কম জন্ম ওজনের শিশুর বিশেষ যত্ন<br>`5cec984f:5` কম জন্ম ওজনের নবজাতকের যত্ন ও বিপদচিহ্ন | Low birth weight: warmth, frequent feeding, KMC, watch for danger signs. |
| 6 | How exactly do I show a mother to carry her small baby against her chest? | `d12af13b:7` ক্যাঙ্গারু মাদার কেয়ার কি?<br>`d12af13b:8` ক্যাঙ্গারু মাদার কেয়ার কিভাবে দিতে হবে? | Kangaroo mother care positioning — skin to skin, upright, head turned to one side. |
| 7 | For how many days and how many hours a day should that chest-to-chest care continue? | `d12af13b:9` ক্যাঙ্গারু মাদার কেয়ার কতদিন ও কতক্ষণ দিতে হবে? | Duration and daily hours of kangaroo mother care. |
| 8 | Two days after delivery the mother is soaking a cloth with blood very quickly. What is this? | `a6032a14:0` প্রসব পরবর্তী জটিলতা: অতিরিক্ত রক্তক্ষরণ | Postpartum haemorrhage — an emergency, refer immediately. |
| 9 | The mother had a fit and lost consciousness a day after giving birth. | `a6032a14:3` প্রসব পরবর্তী জটিলতা: খিঁচুনি বা ফিট | Postpartum convulsions (eclampsia) — emergency referral. |
| 10 | A week after delivery the fluid coming from below smells very bad. | `a6032a14:2` প্রসব পরবর্তী জটিলতা: যোনিপথে দুর্গন্ধযুক্ত স্রা<br>`dfd06f1e:8` লকিয়া: অস্বাভাবিক অবস্থা ও বিপদচিহ্ন | Foul-smelling lochia = infection; refer. |
| 11 | How do I check that the womb is going back to its normal size after birth? | `dfd06f1e:6` মায়ের জরায়ুর উচ্চতা হ্রাস (ইনভলুশন)<br>`4cfbd386:5` মায়ের জরায়ুর উচ্চতা হ্রাস (ইনভলুশন) | Involution — fundal height falls about a finger-breadth a day. |
| 12 | What colour should the flow be in the first few days after birth, and later? | `dfd06f1e:7` লকিয়া: স্বাভাবিক অবস্থা<br>`4cfbd386:6` লকিয়া পর্যবেক্ষণ | Normal lochia progression: red, then pinkish, then whitish. |
| 13 | On which days am I supposed to visit a mother after she has delivered? | `dfd06f1e:3` প্রসব পরবর্তী ভিজিটের সময়সূচী<br>`4cfbd386:3` প্রসব পরবর্তী ভিজিট (পিএনসি ভিজিট) | The PNC visit schedule. |
| 14 | A pregnant woman's feet are puffy. What should I tell her? | `47ea6019:6` গর্ভকালীন শারীরিক পরীক্ষা: ইডিমা হলে পরামর্শ<br>`6818eb87:7` ইডিমা দেখার নিয়ম ও করণীয়<br>`cb824f82:3` হাতে পায়ে পানি আসা | Oedema advice: raise the legs when resting, lie on the left side, nutritious food, watch for danger signs. |
| 15 | Her blood pressure reading came out high during the visit. What now? | `47ea6019:8` গর্ভকালীন রক্তচাপ: অস্বাভাবিক হলে করণীয়<br>`cb824f82:2` উচ্চ রক্তচাপ ব্যবস্থাপনা | Abnormal antenatal BP — recheck, advise, refer per threshold. |
| 16 | She is throwing up a lot in the early months of pregnancy. | `c8f1a780:0` বমিবমি ভাব এবং বমি | Nausea and vomiting in pregnancy — small frequent meals, refer if severe. |
| 17 | She feels a burning in her chest after eating. What advice? | `c8f1a780:2` বুক জ্বালাপোড়া | Heartburn in pregnancy. |
| 18 | The pregnant woman has not passed stool for several days. | `c8f1a780:4` কোষ্ঠ কাঠিন্য | Constipation in pregnancy — fluids, fibre, movement. |
| 19 | When during pregnancy should she get the tetanus injection? | `7e6098c0:8` গর্ভকালীন টিটেনাস টক্সয়েড টিকা<br>`7e6098c0:9` প্রজনন বয়সী মহিলাদের টিটেনাস টিকার সিডিউল | TT vaccine timing and schedule. |
| 20 | The child is breathing very fast. When do I send him to hospital? | `7a1f98e9:6` শিশুকে কখন হাসপাতালে রেফার করতে হবে?<br>`7a1f98e9:5` দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা<br>`7a1f98e9:3` নিউমোনিয়া কী এবং এর লক্ষণ | Fast breathing thresholds and referral criteria. |
| 21 | How many breaths in a minute counts as too fast for a small baby? | `7a1f98e9:5` দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা | Age-specific fast-breathing cut-offs. |
| 22 | The child has loose stools. How much of the packet solution do I give? | `e660517c:5` ডায়রিয়া হলে স্যালাইন খাওয়ানোর নিয়ম | ORS preparation and amount by age. |
| 23 | Should zinc be given for loose motions, and for how many days? | `e660517c:6` ডায়রিয়া হলে বেবী জিংক খাওয়ানোর নিয়ম | Zinc dose and 10–14 day course. |
| 24 | A man has been coughing for three weeks and is losing weight. | `8528ce04:1` যক্ষ্মার লক্ষণ এবং এটি কিভাবে ছড়ায়<br>`8528ce04:2` যক্ষ্মা রোগী চিহ্নিত করার উপায় এবং কফ সংগ্রহের  | TB suspect — symptoms and sputum collection. |
| 25 | Where do I send the spit sample and what happens if it comes back positive? | `8528ce04:3` কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে  | Where sputum is examined and what to do on a positive result. |
| 26 | How do I treat a snake bite in the field? | **refuse** — not in any synced module | Not covered by any synced module. |
| 27 | What dose of insulin should a diabetic patient take? | **refuse** — not in any synced module | Not covered. |
| 28 | How do I apply for a birth registration certificate? | **refuse** — not in any synced module | Administrative, not clinical content. |
| 29 | What should I do for someone with a broken arm? | **refuse** — not in any synced module | Not covered. |
| 30 | Which eye drops are used for conjunctivitis? | **refuse** — not in any synced module | Not covered. |

*25 answerable, 5 unanswerable.*
