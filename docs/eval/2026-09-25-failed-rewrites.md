# Failed rewrites of the right card: the texts at every stage

**Run:** `chat-eval/runs/rewrite-rerun/`, a rerun on 2026-09-25 of the 69 UC2 questions whose expected card was served but whose answer was not a CORRECT rewrite in the first run (`runs/full-assisted/`, 2026-09-24). Assisted mode: BM25 + dense + Qwen3 0.6B, emulator, SDK logging the model's full English answer and the full shown text.
**Modules:** exported from the device at the start and end of the run (`modules.json`, sha256 `724b0127dda7…`); unchanged during the run.
**Verdicts:** judged by the model running the harness (`model:claude-opus-5-5`), not yet checked by a person.

## What the rerun established

- **The model's output is reproducible.** All 51 rewrites shown to the CHW are word for word the same as in the first run, and the same 18 rows were rejected by the groundedness check again. At temperature 0.2 the device gives the same answer to the same input, so a fix can be tested on exactly these rows.
- **Every verdict therefore carries over** from the first run unchanged: same card, same shown text.
- **Every text is now complete.** The model's English answer, which the first run's log cut at 220 characters on 19 rows, is complete on all 69 rows. The shown text is taken from the new `SHOWN` log line; on two rows (`uc2_q002`, `uc2_q071`) the screen capture differs only by markdown the app renders (`**bold**`, a leading `- `).

## Where each text comes from

| Text | Source |
|---|---|
| Bangla question | the question bank |
| Bangla card | the modules exported from the device during this run |
| English question the model got | the `Question:` line of the logged prompt (ML Kit's translation) |
| English card the model got | the logged prompt above the question (ML Kit's translation) |
| The model's English answer | the new `LLM answer` log line, complete |
| Bangla shown to the CHW | the new `SHOWN` log line, complete (ML Kit's translation of the model's answer) |

Of the 77 questions whose expected card was served in the first run, the 8 not rerun are the rewrites judged CORRECT, listed in section 3.

---

## 1. The 51 rewrites shown to the CHW that were not CORRECT

PARTIAL: 28. INCORRECT: 23.

| Id | Verdict | Question (Bangla) | Question the model got (English) | Why |
|---|---|---|---|---|
| uc2_q002 | PARTIAL | প্রসব পরবর্তী মায়ের যত্ন: আয়রন ও ক্যালসিয়াম বড়ি — নির্দেশনা কী? আমি কী পরামর্শ দিব? | Care of birth next mother: Iron and calcium pills - what is the instruction? What will I advise? | Keeps two calcium, one iron and not together, but drops the three-month duration and iron at noon, and ends by telling the mother to avoid taking both. |
| uc2_q005 | PARTIAL | ডায়রিয়া: ভয়াবহতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | Diarrhea: What is the health worker to do with horror? | Mentions diarrhoea, malnutrition and child deaths in garbled grammar; drops the frequency, the recovery without medicine and the dehydration risk. |
| uc2_q006 | PARTIAL | মা, নবজাতক ও শিশুস্বাস্থ্য তথ্য কার্ডের গুরুত্ব কী? SK হিসেবে আমি রোগীকে কী বলব? | What is the importance of mother, newborn and child health information card? What would I say to the patient as a sk? | First sentence carries the card's point about filling the card correctly for quick action; then adds an invented patient quote. |
| uc2_q008 | INCORRECT | ভিজিটে যক্ষ্মা কী এবং এর প্রকারভেদ দেখেছি। করণীয় কী? | Visit Tuberculosis Key and Its Types. What is the key? | One garbled sentence naming the topic; no definition, cause or the two types of TB. |
| uc2_q010 | INCORRECT | শ্বাসতন্ত্র কী? নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the respiratory? What is the health worker? | Says only 'respiratory system'; omits the organs that make it up. |
| uc2_q013 | INCORRECT | ভিজিটে প্রসব পরবর্তী মায়ের যত্ন: কাজ ও বিশ্রাম দেখেছি। করণীয় কী? | Between visual delivery mother care: saw work and rest. What is the key? | Shows two words, 'what health'; none of the 42-day heavy-work rule, rest or sleep advice. |
| uc2_q014 | PARTIAL | মাকে ৬ মাসের পর কেন পারিবারিক খাবার প্রয়োজন? সম্পর্কে কী কাউন্সেলিং দিব? | Why does the mother need family food after 6 months? What will give me counseling? | Says family food is needed after six months for nutrition, but garbles the child as 'soil' and reverses the malnutrition warning. |
| uc2_q015 | PARTIAL | নবজাতকের বুকের দুধ খাওয়ানো নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the health worker to feed the newborn breast? | Keeps ten to twelve feeds a day but makes the health worker the one who breastfeeds, and drops colostrum and six months of breast milk only. |
| uc2_q017 | PARTIAL | গর্ভবতী মহিলার প্রাথমিক তথ্য লিপিবদ্ধকরণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? | Primary information of pregnant women - what is the instruction? What will I advise? | Name and age are right; drops the household number, provider details and last menstrual period, and adds a delivery date the card does not ask for here. |
| uc2_q019 | INCORRECT | মাকে যক্ষ্মার লক্ষণ এবং এটি কিভাবে ছড়ায় সম্পর্কে কী কাউন্সেলিং দিব? | Mother tuberculosis signs and how will it be counseling about how it spreads? | Claims the information does not mention TB symptoms, when the card lists them and how TB spreads. |
| uc2_q020 | INCORRECT | ডেঙ্গু আরডিটি পরীক্ষার জন্য প্রয়োজনীয় উপকরণ নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the need to do health workers with the necessary materials for the dengue RDT test? | Says the materials are mentioned without naming any; the cassette, lancet, cotton, antiseptic and buffer are missing. |
| uc2_q021 | PARTIAL | শ্বাসতন্ত্রের সংক্রমণ কী? কী? SK হিসেবে আমি রোগীকে কী বলব? | What is the infection of the respiratory? What? What would I say to the patient as a sk? | Gives 'sudden infection of part of the respiratory tract' but drops ARI, then adds an invented self-introduction. |
| uc2_q024 | INCORRECT | মাকে প্রসব পরবর্তী মায়ের যত্ন: ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কী কাউন্সেলিং দিব? | Mother's care of the mother's care: What counseling will be given about personal cleanliness? | Claims the information gives no hygiene advice, when the card covers pads, daily bathing, breast care and perineal stitches. |
| uc2_q025 | INCORRECT | পারিবারিক খাবার খাওয়ানোর প্রয়োজনীয়তা ও উপকারিতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the need for health workers to feed family food and benefit? | Claims the information says nothing about the need and benefits, when the card lists cost, growth, immunity and time saved. |
| uc2_q027 | PARTIAL | ডায়রিয়ার প্রধান কারণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? | The main reason for diarrhea - what is instruction? What will I advise? | Right cause categories (virus, bacteria, parasite) but drops every named organism the card lists, such as rotavirus, E. coli and Shigella. |
| uc2_q030 | PARTIAL | যক্ষ্মা রোগী চিহ্নিত করার উপায় এবং কফ সংগ্রহের নিয়ম নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the way to identify tuberculosis patients and the rules of cuff collection? | Sputum test and chest X-ray are right; the collection rule is garbled and drops the second spot sample and how to cough it up. |
| uc2_q031 | INCORRECT | ডেঙ্গু আরডিটি পরীক্ষা করার পদ্ধতি কী? SK হিসেবে আমি রোগীকে কী বলব? | What is the method of testing dengue RDT? What would I say to the patient as a sk? | The method is unrecognisable ('cooking barrel', 'set of fingers') and it ends with an invented claim that a test came back positive. |
| uc2_q035 | PARTIAL | প্রসব পরবর্তী মায়ের যত্ন: পরিবার পরিকল্পনা ও বিপদ চিহ্ন নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | Care of the next mother's care: What is health work for family planning and danger mark? | Keeps early birth control only; drops six weeks without intercourse and immediate referral on a danger sign. |
| uc2_q037 | INCORRECT | নবজাতকের পরিচ্ছন্নতা ও স্বাস্থ্যবিধি — নির্দেশনা কী? আমি কী পরামর্শ দিব? | Newborn cleanliness and hygiene - What is the instruction? What will I advise? | Garbles the rules into washing after three days and after the first month, and drops handwashing before touching the newborn. |
| uc2_q038 | PARTIAL | ভিজিটে ডায়রিয়া ছড়ানোর কারণ দেখেছি। করণীয় কী? | Seeing the cause of spreading diarrhea. What is the key? | Keeps clean water and handwashing; drops stale or uncovered food, sanitary latrines and cooking meat, milk and eggs well. |
| uc2_q039 | INCORRECT | মাকে গর্ভবতী মহিলার ভিজিট ও শারীরিক পরিমাপের তথ্য সম্পর্কে কী কাউন্সেলিং দিব? | What counseling will be counseling about the information of pregnant women's visits and physical measurements? | Says the information gives no counselling detail; none of the visit date, month, weight, height or temperature entries. |
| uc2_q041 | PARTIAL | কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে করণীয় কী? SK হিসেবে আমি রোগীকে কী বলব? | Where is cuff tested and tuberculosis is caught? What would I say to the patient as a sk? | Keeps prompt treatment and taking medicine in front of the provider; omits every place sputum is tested and garbles TB as 'skin'. |
| uc2_q044 | INCORRECT | মাকে টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা সম্পর্কে কী কাউন্সেলিং দিব? | What is the general and dangerous side effects of mama Ticker and what counseling about the management? | Says no information is given. The expected card is itself trainer-session text with no side-effect content, so no rewrite could answer from it. |
| uc2_q045 | INCORRECT | তাপমাত্রা পরিমাপের পদ্ধতি ও স্বাভাবিক মাত্রা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the health worker to treat temperature and normal levels? | Claims the information does not cover temperature measurement, when the card gives the thermometer steps. |
| uc2_q046 | PARTIAL | প্রসব পরবর্তী জটিলতা: অতিরিক্ত রক্তক্ষরণ কী? SK হিসেবে আমি রোগীকে কী বলব? | Next complexity of delivery: What is extra bleeding? What would I say to the patient as a sk? | Says heavy bleeding after delivery is a complication; drops that it is a danger sign needing urgent referral. |
| uc2_q048 | PARTIAL | ভিজিটে নবজাতকের টিকাদান দেখেছি। করণীয় কী? | Seeing the newborn's immunization. What is the key? | BCG as soon as possible is right; the schedule is garbled and it drops the five visits and ten diseases. |
| uc2_q050 | INCORRECT | গর্ভস্থ শিশুর নড়াচড়া ও ইডিমা লিপিবদ্ধকরণ নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the health worker to do with the movement of the pregnant child's movement and Edema? | Says to record movement and address oedema without any content: no 8 to 10 movements a day, no 20 weeks, no referral on reduced movement. |
| uc2_q051 | PARTIAL | ম্যালেরিয়ার সাধারণ উপসর্গ কী? SK হিসেবে আমি রোগীকে কী বলব? | What is the common symptom of malaria? What would I say to the patient as a sk? | Fever and a fever history in the last 48 hours are right, then the sentence repeats in a loop; drops ruling out other causes of fever. |
| uc2_q052 | PARTIAL | যক্ষ্মার চিকিৎসায় রোগীর ধরণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? | Patient type in tuberculosis - what is the guidance? What will I advise? | New and old patient categories are right, but it defines new patients by a negative test and garbles the old-patient rule. |
| uc2_q053 | INCORRECT | ভিজিটে ডেঙ্গু প্রতিরোধে করণীয় দেখেছি। করণীয় কী? | We saw the dengue to prevent dengue. What is the key? | One garbled sentence; none of the prevention steps about stagnant water, containers or mosquito nets. |
| uc2_q054 | PARTIAL | মাকে শ্বাসতন্ত্রের সংক্রমণের কারণ সম্পর্কে কী কাউন্সেলিং দিব? | What counseling will be given about the cause of breathing infection? | Direct causes are right; the indirect list turns 2.5 kg birth weight into five kg and garbles the other factors. |
| uc2_q057 | PARTIAL | প্রসব পরবর্তী জটিলতা: জ্বর — নির্দেশনা কী? আমি কী পরামর্শ দিব? | Next complications of delivery: Fever - What is the instruction? What will I advise? | Keeps quick referral to a health centre in broken grammar; drops that fever after delivery is a danger sign. |
| uc2_q058 | INCORRECT | ভিজিটে বয়স অনুযায়ী শিশুর খাবারের পরিমাণ ও বার দেখেছি। করণীয় কী? | According to the age of the village, I saw the amount of baby food. What is the key? | A repetitive generic sentence; none of the bowl sizes or meal counts by age. |
| uc2_q060 | INCORRECT | ডায়রিয়া হলে স্যালাইন খাওয়ানোর নিয়ম নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? | What is the health worker to do with the rules of feeding saline if diarrhea? | Says to follow the saline rule without stating it; the card's point, to continue normal food and breastfeeding, is missing. |
| uc2_q061 | INCORRECT | রোগী গর্ভবতী মহিলার রক্ত ও প্রস্রাব পরীক্ষার ফলাফল জানতে চেয়েছে। মূল কথাগুলো (গর্ভবতী ও মহিলার) কী বলব? | The patient wanted to know the results of the pregnant woman's blood and urine test. What is the main words (pregnant and woman)? | Talks about the words 'pregnant' and 'woman' instead of the glucose, haemoglobin and urine results to record. |
| uc2_q063 | INCORRECT | পরিবারকে ডট (DOT) কী এবং এর কৌশলসমূহ বলতে হবে। কৌশলসমূহ ও হলো নিয়ে কী নির্দেশনা দেব? | The family will say dot (dot) and its strategies. What will I do with the strategies? | Says to use the provided information; no definition of DOT and none of its strategies. |
| uc2_q064 | PARTIAL | SK হিসেবে ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের প্রস্তুতি কাউন্সেলিংয়ে ডেঙ্গু কীভাবে ব্যাখ্যা করব? | How do I explain Dengue in the counseling of the dengue Rapid Diagnostic Test (RDT) Kit as SK? | Keeps checking temperature and expiry and using the kit promptly; drops the 30 minutes out of the fridge and adds a severity claim not on the card. |
| uc2_q068 | PARTIAL | পরিবারকে শিশুর জন্য পুষ্টিকর পারিবারিক খাবার তৈরির নিয়ম বলতে হবে। শিশুর ও পুষ্টিকর নিয়ে কী নির্দেশনা দেব? | The family will have to say the rules for making nutritious family foods for the baby. What do you guidelines with children and nutritious? | Handwashing, a 250 ml bowl and the food groups are there, but it turns the bowl into 250 ml of liquid such as soup. |
| uc2_q069 | PARTIAL | SK হিসেবে কম জন্ম ওজনের শিশুর বিশেষ যত্ন কাউন্সেলিংয়ে জন্ম কীভাবে ব্যাখ্যা করব? | How do I explain the birth of special care counseling in the special care counseling of less born child? | Skin-to-skin, frequent breastfeeding, no bath before 7 days, handwashing and spoon feeding are right; 'wrap in a soft cloth' became 'wash with a cloth'. |
| uc2_q070 | INCORRECT | ডায়রিয়া হলে বেবী জিংক খাওয়ানোর নিয়ম নিয়ে প্রশ্ন এসেছে। ডায়রিয়া ও হলে কী বলব? | If diarrhea, there is a question about the rules of Babi Jink feeding. What would you say if diarrhea? | Restates the question; none of the zinc dose, the ten days or what to do after vomiting. |
| uc2_q071 | PARTIAL | আল্ট্রাসোনোগ্রাম, টিটি টিকা ও আয়রন/ক্যালসিয়াম ট্যাবলেট — key counselling points কী? | Ultrasonogram, TT vaccine and iron / calcium tablet - key counseling points? | Follows the card's recording steps, but tells the worker to put a tick when no ultrasound was done, where the card says a cross. |
| uc2_q074 | INCORRECT | ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের পদ্ধতি danger signs ও করণীয় কী? | What is the use of dengue Rapid Diagnostic Test (RDT) kit? | Says what the test detects; none of the steps: blood drop, two buffer drops, 15 to 20 minutes, invalid after 20. |
| uc2_q075 | PARTIAL | Patient কে শিশুকে কখন হাসপাতালে রেফার করতে হবে? নিয়ে কী advice দিব? | When will Patient who have to refer to the hospital? What advice? | Fast breathing, abnormal sleepiness, noisy breathing, convulsions and low temperature are there; drops chest indrawing, inability to feed, high fever and vomiting. |
| uc2_q076 | PARTIAL | ইডিমা দেখার নিয়ম ও করণীয় — key counselling points কী? | Conditions of EDLLY and Do - What is the key counseling points? | Keeps lying down, finding the ankle bone and pressing for 60 seconds; garbles the leg instruction and the result reading, and is cut off. |
| uc2_q077 | INCORRECT | SK হিসেবে প্রসব পরবর্তী জটিলতা: খিঁচুনি বা ফিট management কীভাবে করব? | Next complexity of delivery as SK: How do we do seizures or fits management? | Says the health worker should address the convulsion directly, replacing the card's instruction to refer urgently to a health centre. |
| uc2_q083 | INCORRECT | যক্ষ্মার ওষুধের পার্শ্বপ্রতিক্রিয়া থাকলে referral করা উচিত কি? | Should tuberculosis medicine side effects should be referral? | Says there is no reason for concern and mentions skin disease; omits the orange urine reassurance and every serious side effect that needs the hospital. |
| uc2_q085 | PARTIAL | Patient কে যোনীপথে ফোঁটা ফোঁটা রক্তক্ষরণ নিয়ে কী advice দিব? | What advice will be advice on the droplet bloating bloating in the Patient? | Rest, no heavy work, a sanitary pad and frequent fluids are there; drops referral if bleeding does not stop and garbles the opening. |
| uc2_q087 | PARTIAL | শিশুর বৃদ্ধি ও বিকাশ কী? নিয়ে কী জানতে হবে? SK হিসেবে করণীয় কী? | What is the growth and development of the child? What do you need to know? What is SK? | Defines growth correctly; drops the definition of development and adds that the text does not mention SK. |
| uc2_q089 | PARTIAL | ডায়রিয়া প্রতিরোধে করণীয়: নিরাপদ পানি ও খাবার — নির্দেশনা কী? | Diarrhea to prevent: What is safe water and food - instruction? | Keeps always using safe water and food; drops fresh hot food, covering food, washing produce and cooking meat, milk and eggs. |
| uc2_q090 | INCORRECT | আজ নবজাতকের জন্মগত ত্রুটি ও খাওয়ানোর তথ্য দেখেছি। পরবর্তী পদক্ষেপ কী? | Today saw the information of the newborn's birth and feeding information. What is the next step? | Says the information is already provided and to follow it; nothing about recording birth defects or the first-hour feeds. |
| uc2_q091 | PARTIAL | মশার কামড় থেকে আত্মরক্ষার উপায় — ki guidance dibo? | Self-defense from mosquito bite - Ki Guidance Dibo? | Mentions insecticide-treated nets in garbled form; drops hanging them every evening and the outdoor-sleeping advice. |

### Each row in full

### uc2_q002: PARTIAL

- **Card:** `f62c103f:0` প্রসব পরবর্তী মায়ের যত্ন: আয়রন ও ক্যালসিয়াম বড়ি
- **Groundedness:** 0.45, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী মায়ের যত্ন: আয়রন ও ক্যালসিয়াম বড়ি — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Care of birth next mother: Iron and calcium pills - what is the instruction? What will I advise? |
| Card, Bangla | বাচ্চা হওয়ার পর তিন মাস পর্যন্ত প্রতিদিন একটি করে আয়রন বড়ি দুপুরে ভরা পেটে খেতে হবে। প্রতিদিন দুটি করে ক্যালসিয়াম বড়ি সকালে ও রাতে ভরা পেটে খেতে হবে। আয়রন ও ক্যালসিয়াম বড়ি একসাথে খাওয়া যাবে না। |
| Card given to the model (ML Kit) | After getting a child, I will have to eat in the stomach filled with an iron pill every day. Two calcium pills every day will eat in the morning and at night. Iron and calcium pills can not be eaten together. |
| Model's English answer | The instruction is to follow the guidelines for eating: / - Eat in the morning and at night. / - Take 2 calcium pills and 1 iron pill. / - Iron and calcium pills cannot be taken together. /  / So, the health worker's instruction is: / **"Take 2 calcium and 1 iron pill, but avoid eating both together."** |
| Shown to the CHW (ML Kit back to Bangla) | নির্দেশটি খাওয়ার জন্য নির্দেশিকা অনুসরণ করা: - সকালে এবং রাতে খাও। - 2 ক্যালসিয়াম পিল এবং 1 লোহা পিল নিন। - লোহা এবং ক্যালসিয়াম গোলাপ একসঙ্গে নেওয়া যাবে না। সুতরাং, স্বাস্থ্য কর্মীর নির্দেশটি হল: ** "২ ক্যালসিয়াম এবং 1 লোহা পিল নিন, তবে উভয়কে খাওয়া এড়িয়ে চলুন।" ** |

**Judged:** Keeps two calcium, one iron and not together, but drops the three-month duration and iron at noon, and ends by telling the mother to avoid taking both. Hallucinated phrase: “উভয়কে খাওয়া এড়িয়ে চলুন”.

### uc2_q005: PARTIAL

- **Card:** `952fbce0:5` ডায়রিয়া: ভয়াবহতা
- **Groundedness:** 0.44, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়া: ভয়াবহতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | Diarrhea: What is the health worker to do with horror? |
| Card, Bangla | ডায়রিয়া এবং তা থেকে সৃষ্ট অপুষ্টি সারা বিশ্বে, বিশেষ করে বাংলাদেশে, শিশুর অসুস্থতা ও মৃত্যুর অন্যতম প্রধান কারণ। আমাদের ও আমাদের পার্শ্ববর্তী দেশসমূহে পাঁচ বছরের কম বয়সী শিশুরা বছরে অন্তত দুই থেকে পাঁচ বার ডায়রিয়ায় ভোগে এবং এই রোগে অন্তত বিশ থেকে ত্রিশ দিন অসুস্থ থাকে। বেশিরভাগ ডায়রিয়া কিছুদিনের মধ্যেই সেরে যায় এবং কোনো প্রকার ঔষধের দরকার হয় না। তবে, শুধু দশ শতাংশ ডায়রিয়ায় মারাত্মক পানিস্বল্পতার সৃষ্টি হয় এবং তা থেকে পাঁচ বছরের কম বয়সী বাচ্চা মারা যেতে পারে। বাংলাদেশে বছরে প্রায় এক লক্ষ দশ হাজার শিশু ডায়রিয়া থেকে মারা যায়। |
| Card given to the model (ML Kit) | Diarrhea and malnutrition created from it all over the world, especially in Bangladesh, one of the main reasons for child's illness and death. Children under our neighboring countries and children suffer from at least two to five times in diarrhea a year and at least twenty thirty-three days of this disease. Most diarrhea can be recovered in a few days and there is no need for any kind of medicine. However, only ten percent diarrhea was created in severe water and children under five years of age could die. About one hundred ten thousand children die from diarrhea a year in Bangladesh. |
| Model's English answer | The health worker to do with horror is to address the issue of diarrhea and malnutrition, especially in children, and to prevent it from spreading and causing death. |
| Shown to the CHW (ML Kit back to Bangla) | ভয়াবহতার সাথে স্বাস্থ্যকর্মী ডায়রিয়া ও অপুষ্টির বিষয়টিকে, বিশেষ করে শিশুদের মধ্যে, এবং এটিকে ছড়িয়ে দেওয়ার এবং মৃত্যুর সৃষ্টি করতে বাধা দিতে। |

**Judged:** Mentions diarrhoea, malnutrition and child deaths in garbled grammar; drops the frequency, the recovery without medicine and the dehydration risk.

### uc2_q006: PARTIAL

- **Card:** `f9cdf65f:0` মা, নবজাতক ও শিশুস্বাস্থ্য তথ্য কার্ডের গুরুত্ব
- **Groundedness:** 0.39, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মা, নবজাতক ও শিশুস্বাস্থ্য তথ্য কার্ডের গুরুত্ব কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is the importance of mother, newborn and child health information card? What would I say to the patient as a sk? |
| Card, Bangla | মা, নবজাতক ও শিশুস্বাস্থ্য তথ্য কার্ড সঠিকভাবে পূরণ করতে পারা গুরুত্বপূর্ণ। এই কার্ড পূরণের মাধ্যমে অনেক তথ্য জানা যায়, যার ফলে গর্ভবতীর কোনো ছোটখাট সমস্যা বা জটিলতার দ্রুত ব্যবস্থা গ্রহণ করা সম্ভব। এজন্য কার্ড অত্যন্ত যত্ন, ধৈর্য ও মনোযোগ সহকারে সঠিকভাবে পূরণ করা প্রয়োজন। |
| Card given to the model (ML Kit) | Mother, newborn and child health information card can be properly met. Many information is known by filling this card, so that it is possible to take quick action of a minor problem or complications of pregnant. For this, the card needs to be fulfilled properly, patiently and properly properly. |
| Model's English answer | The importance of the health information card is to ensure that all information is properly and accurately documented, allowing for quick and effective responses to any health-related issues, whether they are minor or complications, especially for pregnant individuals. As a patient, I would say: "I am happy to have this information and can take quick action on any health-related problems." |
| Shown to the CHW (ML Kit back to Bangla) | স্বাস্থ্য সম্পর্কিত তথ্য কার্ডের গুরুত্বটি নিশ্চিত করা যে সমস্ত তথ্য সঠিকভাবে এবং সঠিকভাবে নথিভুক্ত করা হয়েছে, যা কোনও স্বাস্থ্য সম্পর্কিত সমস্যাগুলির জন্য দ্রুত এবং কার্যকর প্রতিক্রিয়াগুলির জন্য, বিশেষ করে গর্ভবতী ব্যক্তিদের জন্য কিনা তা নিশ্চিত করা হয়। একজন রোগী হিসেবে আমি বলব: "আমি এই তথ্যটি খুশি করতে পেরে খুশি এবং কোন স্বাস্থ্য সম্পর্কিত সমস্যাগুলিতে দ্রুত পদক্ষেপ নিতে পারি।" |

**Judged:** First sentence carries the card's point about filling the card correctly for quick action; then adds an invented patient quote. Hallucinated phrase: “আমি এই তথ্যটি খুশি করতে পেরে খুশি”.

### uc2_q008: INCORRECT

- **Card:** `8528ce04:0` যক্ষ্মা কী এবং এর প্রকারভেদ
- **Groundedness:** 0.75, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে যক্ষ্মা কী এবং এর প্রকারভেদ দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | Visit Tuberculosis Key and Its Types. What is the key? |
| Card, Bangla | যক্ষ্মা একটি জীবাণুঘটিত মারাত্মক সংক্রামক রোগ। যা আক্রান্ত ব্যক্তির শরীর থেকে সুস্থ ব্যক্তির শরীরে যায়। মাইকোব্যাকটেরিয়াম টিউবারকুলোসিস নামক যক্ষ্মার জীবাণু এই রোগ ঘটায়। ফুসফুস, গ্রন্থি, হাড় ও অন্ত্রসহ শরীরের বিভিন্ন অংশে যক্ষ্মা হতে পারে। শরীরের অবস্থান অনুযায়ী যক্ষ্মাকে দুই ভাগে ভাগ করা হয়: ফুসফুসের যক্ষ্মা এবং ফুসফুস বহির্ভূত যক্ষ্মা। ফুসফুস ছাড়া শরীরের অন্য কোন স্থানে যক্ষ্মা যেমন ফুসফুসের আবরণী, গ্রন্থি, হাড়, মস্তিষ্কের আবরণী, অস্ত্র ইত্যাদিকে ফুসফুস বহির্ভূত যক্ষ্মা বলে। ফুসফুসের যক্ষ্মা আবার দুই প্রকার: কফে জীবাণুযুক্ত ফুসফুসের যক্ষ্মা (এসব রোগীর কফ পরীক্ষার মাধ্যমে জীবাণু পাওয়া যায় এবং এরা সংক্রামক রোগী) এবং কফে জীবাণুমুক্ত ফুসফুসের যক্ষ্মা (এসব রোগীর কফ পরীক্ষায় জীবাণু পাওয়া যায় না, পরবর্তী সময়ে এক্স-রে দ্বারা চিহ্নিত হয় এবং এরা সাধারণত অ-সংক্রামক রোগী)। ফুসফুস বহির্ভূত যক্ষ্মা সাধারণত সংক্রামক নয়। দাঁত, চুল, নখ ছাড়া শরীরের সব জায়গায় যেমন ফুসফুস, হাড়, কিডনি, জরায়ু, গ্রন্থি, পাকস্থলি ও শরীরের বিভিন্ন জায়গায় যক্ষ্মা হতে পারে। |
| Card given to the model (ML Kit) | TB of tuberculosis is a germal fatal infectious disease. Which goes to the body of a healthy person from the body of the affected person. Microbacterium tuberculosis called tuberculosis germs caused this disease. There may be tuberculosis in different parts of the body, including lungs, glands, bones and intestines. According to the body's position, tuberculosis is divided into two parts: Tuberculosis of lung tuberculosis and lungs. Except for lungs, such as tuberculosis, such as lung cover, glands, bones, brain covers, arms etc., say tuberculosis. Two types of lung tuberculosis again: Between Bacular lungs tuberculosis (these patients are found through cough tests and they are infectious patients) and Bibhumi Lung Tuberculosis (Patient Cough Examination The germs are not available, later marked by X-rays and they are usually non-contagious patients). Lung outside tuberculosis is not usually infectious. Without the teeth, hair, nails, such as lung, bone, kidney, uterus, glands, stomach and body can be tuberculosis. |
| Model's English answer | The key is the classification of tuberculosis types and its prevalence in different body parts. |
| Shown to the CHW (ML Kit back to Bangla) | কীটি টিউবারকুলোসিস প্রকারের শ্রেণীবিভাগ এবং বিভিন্ন শরীরের অংশে এর প্রাদুর্ভাব। |

**Judged:** One garbled sentence naming the topic; no definition, cause or the two types of TB.

### uc2_q010: INCORRECT

- **Card:** `7a1f98e9:0` শ্বাসতন্ত্র কী?
- **Groundedness:** 1.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | শ্বাসতন্ত্র কী? নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the respiratory? What is the health worker? |
| Card, Bangla | যে সকল অঙ্গ দিয়ে শ্বাস নেয়া হয় যেমন- নাক, মুখ, গলা, ফুসফুস এগুলোকে একসাথে শ্বাসতন্ত্র বলে। |
| Card given to the model (ML Kit) | The limbs that are breathing with such as nose, mouth, throat, lungs are called breathing together. |
| Model's English answer | The respiratory system. |
| Shown to the CHW (ML Kit back to Bangla) | শ্বাসযন্ত্রের সিস্টেম। |

**Judged:** Says only 'respiratory system'; omits the organs that make it up.

### uc2_q013: INCORRECT

- **Card:** `f62c103f:1` প্রসব পরবর্তী মায়ের যত্ন: কাজ ও বিশ্রাম
- **Groundedness:** 1.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে প্রসব পরবর্তী মায়ের যত্ন: কাজ ও বিশ্রাম দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | Between visual delivery mother care: saw work and rest. What is the key? |
| Card, Bangla | প্রসবের পর ৪২ দিন পর্যন্ত ভারী কাজ যেমন কলসি দিয়ে পানি টানা, ভারী কাপড় কাচা, মাটিকাটা, ধানভানা, ইটভাঙ্গা করা যাবে না। সাত থেকে দশ দিন পর্যন্ত পূর্ণ বিশ্রামে থাকতে হবে। প্রতিদিন কমপক্ষে আট ঘন্টা ঘুমাতে হবে এবং দুই ঘন্টা বিশ্রাম নিতে হবে। |
| Card given to the model (ML Kit) | After delivery, heavy tasks till 42 days such as drinking water with Callsi, heavy clothes, matakata, dhanbana, it will not be brought. Must have full rest for seven to ten days. Every day will be at least eight hours to sleep and take two hours to rest. |
| Model's English answer | The key is health. |
| Shown to the CHW (ML Kit back to Bangla) | কী স্বাস্থ্য। |

**Judged:** Shows two words, 'what health'; none of the 42-day heavy-work rule, rest or sleep advice.

### uc2_q014: PARTIAL

- **Card:** `0fa2eaf5:1` ৬ মাসের পর কেন পারিবারিক খাবার প্রয়োজন?
- **Groundedness:** 0.39, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে ৬ মাসের পর কেন পারিবারিক খাবার প্রয়োজন? সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | Why does the mother need family food after 6 months? What will give me counseling? |
| Card, Bangla | ছয় মাসের পর শুধু মায়ের দুধ শিশুর জন্য যথেষ্ট নয়। প্রয়োজনীয় পুষ্টির জন্য শিশুকে অন্যান্য খাবার দিতে হবে। ছয় মাস বয়স থেকে অন্যান্য খাবার না দিলে শিশুর স্বাভাবিক বৃদ্ধি ঘটবে না এবং শিশু অপুষ্টিতে ভুগবে। |
| Card given to the model (ML Kit) | Six months after the mother's milk is not enough for the baby. Children should give other food for necessary nutrients. If you do not have other food from the age of six months, the child's normal growth will not happen and children will suffer from malnutrition. |
| Model's English answer | The mother needs family food after 6 months to ensure proper nutrition, as it is essential for the child's growth and development. This is a natural part of the child's development, and without it, the child may not reach their full potential and suffer from malnutrition. |
| Shown to the CHW (ML Kit back to Bangla) | মাটির বৃদ্ধির ও উন্নয়নের জন্য এটি যথাযথ পুষ্টি নিশ্চিত করার জন্য 6 মাস পর মা পরিবারের খাদ্যের প্রয়োজন হয়। এটি সন্তানের বিকাশের একটি প্রাকৃতিক অংশ, এবং এটি ছাড়া শিশু তাদের সম্পূর্ণ সম্ভাব্যতা অর্জন করতে পারে না এবং অপুষ্টি থেকে ভোগাও না। |

**Judged:** Says family food is needed after six months for nutrition, but garbles the child as 'soil' and reverses the malnutrition warning. Hallucinated phrase: “অপুষ্টি থেকে ভোগাও না”.

### uc2_q015: PARTIAL

- **Card:** `d12af13b:1` নবজাতকের বুকের দুধ খাওয়ানো
- **Groundedness:** 0.75, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | নবজাতকের বুকের দুধ খাওয়ানো নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the health worker to feed the newborn breast? |
| Card, Bangla | জন্মের প্রথম তিন দিন পর্যন্ত শালদুধ খাওয়ানো নিশ্চিত করতে হবে। মাকে বুকের দুধ খাওয়ানোর সঠিক পদ্ধতি শেখাতে হবে। ছয় মাস পর্যন্ত শুধুমাত্র বুকের দুধ দিনে দশ থেকে বারো বার খাওয়ানো উচিত। |
| Card given to the model (ML Kit) | For the first three days of birth, the feeding should be ensured. Mother needs to teach the right method of breastfeeding. For six months only breast milk should be fed to ten to twelve times a day. |
| Model's English answer | The health worker should ensure the newborn is fed breast milk 10 to 12 times a day. |
| Shown to the CHW (ML Kit back to Bangla) | স্বাস্থ্য কর্মীকে নবজাতককে বুকের দুধ খাওয়ানো উচিত 10 থেকে 1২ বার। |

**Judged:** Keeps ten to twelve feeds a day but makes the health worker the one who breastfeeds, and drops colostrum and six months of breast milk only.

### uc2_q017: PARTIAL

- **Card:** `f9cdf65f:1` গর্ভবতী মহিলার প্রাথমিক তথ্য লিপিবদ্ধকরণ
- **Groundedness:** 0.80, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | গর্ভবতী মহিলার প্রাথমিক তথ্য লিপিবদ্ধকরণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Primary information of pregnant women - what is the instruction? What will I advise? |
| Card, Bangla | গর্ভবতী মহিলার নাম এই ঘরে লিখতে হবে। গর্ভবতীর বয়স যে তারিখে গর্ভবতী মহিলা চিহ্নিত হয়েছেন সেই তারিখে নির্ণয় করে লিখতে হবে। জাতীয় পরিচয়পত্র বা টিটি টিকার কার্ড দেখে বয়স লেখা যাবে। স্বাস্থ্যকর্মী ট্যাব অনুযায়ী গর্ভবতী মহিলা যে খানায় অবস্থান করছেন সেই খানার নম্বর লিখতে হবে। গর্ভকালীন পরিচর্যা প্রদানকারী স্বাস্থ্যকর্মী তার নাম ও মোবাইল নম্বর এই ঘরে লিখবেন। গর্ভবতী মহিলার সাথে কথা বলে শেষ মাসিক শুরুর তারিখ বাংলা অথবা ইংরেজী মাস অনুসারে লিখতে হবে। প্রসবের সম্ভাব্য তারিখ শেষ মাসিক শুরুর তারিখের সাথে 9 মাস 7 দিন যোগ করে যে তারিখটি পাওয়া যাবে তা লিখতে হবে। |
| Card given to the model (ML Kit) | The pregnant woman's name will be written in this room. The pregnant age will be determined on the date that the pregnant woman has identified. Age can be written by the national identity card or T-Ticker card. According to the Health Workers Tab, the pregnant woman should write the number of the number of people who are living in the house. The pregnancy service providers will write his name and mobile number in this house. Talking to the pregnant woman, the last month's start date must be written according to Bangla or English month. The possible date of delivery should be written by adding 9 months 7 days with the last month's start date. |
| Model's English answer | The instruction is to write the name and age of the pregnant woman, and the date of delivery. |
| Shown to the CHW (ML Kit back to Bangla) | নির্দেশটি হল গর্ভবতী মহিলার নাম এবং বয়স লিখতে এবং প্রসবের তারিখ লিখতে। |

**Judged:** Name and age are right; drops the household number, provider details and last menstrual period, and adds a delivery date the card does not ask for here.

### uc2_q019: INCORRECT

- **Card:** `8528ce04:1` যক্ষ্মার লক্ষণ এবং এটি কিভাবে ছড়ায়
- **Groundedness:** 0.27, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে যক্ষ্মার লক্ষণ এবং এটি কিভাবে ছড়ায় সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | Mother tuberculosis signs and how will it be counseling about how it spreads? |
| Card, Bangla | যক্ষ্মার প্রধান লক্ষণ হলো একনাগাড়ে দুই সপ্তাহ বা তার বেশি সময় ধরে কাশি (কাশির সাথে রক্ত থাকুক বা না থাকুক)। এছাড়াও নিচের লক্ষণগুলো দেখা যেতে পারে: ওজন কমে যাওয়া এবং শরীর দিন দিন দুর্বল হয়ে যাওয়া, বিকালের দিকে অল্প অল্প জ্বর আসা এবং রাতে শরীর ঘেমে জ্বর ছেড়ে যাওয়া, খাবারে অরুচি, শ্বাসকষ্ট এবং বুকে অথবা পিঠের উপরের অংশে ব্যথা। যক্ষ্মারোগীর হাঁচি ও কাশির মাধ্যমে যক্ষ্মার জীবাণু বের হয়ে বাতাসে মেশে এবং শ্বাস প্রশ্বাসের মাধ্যমে তা সুস্থ ব্যক্তির ফুসফুসে প্রবেশ করে ও বংশবৃদ্ধি করে। সংক্রমিত লোকদের শতকরা দশ ভাগই এ রোগে আক্রান্ত হতে পারে। এটি একটি মারাত্মক সংক্রামক রোগ এবং একজন পজেটিভ রোগী (যার হাঁচি বা কাশির সাথে জীবাণু বের হয়) বছরে আরও দশ জন লোককে আক্রান্ত করে। বাংলাদেশে যক্ষ্মা প্রাপ্তবয়স্ক মৃত্যুর ক্ষেত্রে একটি অন্যতম ঘাতক ব্যাধি যা প্রতি বছর বহু লোকের মৃত্যু ঘটায়। |
| Card given to the model (ML Kit) | The main sign of tuberculosis is that coughing for two weeks or more for two weeks or more (or not to have blood with Kashi). The following symptoms can also be seen: The weight loss and weakening the body is weak in the afternoon, a small fever in the afternoon and leave the fever in the night, the pain in the food, breathless and in the chest or the upper part of the back. Through tuberculosis and cough, tuberculosis germs go out and in the air, it enters the lungs of a healthy person through breathing and breathing. Ten percent of the infected people may be infected with this disease. It is a fatal infectious disease and a positive patient (whose dust with whose nieces or coffee is germs). Tuberculosis adult death in Bangladesh is one of the most killer disorders that caused many people every year. |
| Model's English answer | The information provided does not mention how tuberculosis signs or symptoms are related to its transmission. Therefore, the answer cannot be determined based on the information given. |
| Shown to the CHW (ML Kit back to Bangla) | প্রদত্ত তথ্যটি উল্লেখ করে না যে টিউবকুলোসিস লক্ষণ বা লক্ষণগুলি তার সংক্রমণের সাথে সম্পর্কিত। অতএব, প্রদত্ত তথ্যের ভিত্তিতে উত্তরটি নির্ধারণ করা যাবে না। |

**Judged:** Claims the information does not mention TB symptoms, when the card lists them and how TB spreads. Hallucinated phrase: “প্রদত্ত তথ্যটি উল্লেখ করে না যে টিউবকুলোসিস লক্ষণ”.

### uc2_q020: INCORRECT

- **Card:** `47f4d15a:1` ডেঙ্গু আরডিটি পরীক্ষার জন্য প্রয়োজনীয় উপকরণ
- **Groundedness:** 0.32, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডেঙ্গু আরডিটি পরীক্ষার জন্য প্রয়োজনীয় উপকরণ নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the need to do health workers with the necessary materials for the dengue RDT test? |
| Card, Bangla | ডেঙ্গু আরডিটি পরীক্ষার জন্য ক্যাসেট, সুঁই বা লেনসেট, ল্যানসিং যন্ত্র, তুলা, হেক্সিসল এবং ডেঙ্গু এন্টিজেন বাফার সলুশন প্রয়োজন। |
| Card given to the model (ML Kit) | Dengue RDT test requires cassette, suite or lunset, lansing instruments, cotton, hexistical and dengue antigen buffer soluations. |
| Model's English answer | The need to do health workers with the necessary materials for the dengue RDT test is not mentioned in the information provided. |
| Shown to the CHW (ML Kit back to Bangla) | ডেঙ্গু আরডিটি পরীক্ষার প্রয়োজনীয় উপকরণ দিয়ে স্বাস্থ্যকর্মীদের প্রয়োজনীয়তা উল্লেখ করা হয়েছে। |

**Judged:** Says the materials are mentioned without naming any; the cassette, lancet, cotton, antiseptic and buffer are missing.

### uc2_q021: PARTIAL

- **Card:** `7a1f98e9:1` শ্বাসতন্ত্রের সংক্রমণ কী?
- **Groundedness:** 0.64, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | শ্বাসতন্ত্রের সংক্রমণ কী? কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is the infection of the respiratory? What? What would I say to the patient as a sk? |
| Card, Bangla | শ্বাসতন্ত্রের যে কোন অংশের হঠাৎ সংক্রমণ কে শ্বাসতন্ত্রে সংক্রমণ বলে। শ্বাসতন্ত্রের যে কোন অংশের হঠাৎ সংক্রমণ হলে তাকে এআরআই (Acute Respiratory tract Infection) বা শ্বাসতন্ত্রের তীব্র সংক্রমণ বলে। |
| Card given to the model (ML Kit) | Suddenly infections of any part of the respiratory part of the transmission of breathing infection. If any part of the respiratory is a sudden infection, he says acute respiratory tract infection or severe infection of respiratory. |
| Model's English answer | The infection of the respiratory part is a sudden infection. As a sk, I would say: "I am a health worker and I am here to help." |
| Shown to the CHW (ML Kit back to Bangla) | শ্বাসযন্ত্রের অংশ সংক্রমণ হঠাৎ সংক্রমণ। একটি স্কা হিসাবে, আমি বলব: "আমি একজন স্বাস্থ্যকর্মী এবং আমি সাহায্য করার জন্য এখানে আছি।" |

**Judged:** Gives 'sudden infection of part of the respiratory tract' but drops ARI, then adds an invented self-introduction. Hallucinated phrase: “আমি একজন স্বাস্থ্যকর্মী এবং আমি সাহায্য করার জন্য এখানে আছি”.

### uc2_q024: INCORRECT

- **Card:** `f62c103f:2` প্রসব পরবর্তী মায়ের যত্ন: ব্যক্তিগত পরিচ্ছন্নতা
- **Groundedness:** 0.26, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে প্রসব পরবর্তী মায়ের যত্ন: ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | Mother's care of the mother's care: What counseling will be given about personal cleanliness? |
| Card, Bangla | প্রসবের পর চার সপ্তাহ পর্যন্ত রক্ত মিশ্রিত স্রাব বের হয়, সেজন্য কাপড় ব্যবহার না করে ন্যাপকিন ব্যবহার করতে হবে (দিনে চারটি)। প্রতিদিন গোসল করতে হবে, স্তন পরিষ্কার করতে হবে এবং স্তনের বোটা ভিতরের দিকে দেওয়া থাকলে তেল দিয়ে মালিশ করতে হবে। পেরিনিয়ামে সেলাই থাকলে তা পরিষ্কার ও শুকনো রাখতে হবে। |
| Card given to the model (ML Kit) | After delivery, blood-mixed discharge is extracted for four weeks, so do not use napkin without using clothes (four days a day). Every day we have to get a bath, the breast should be cleaned and the nipple boots should be massaged with oil. If the perinium sewing, it should be cleaned and dry. |
| Model's English answer | The information provided does not mention any specific counseling about personal cleanliness. Therefore, the answer is: "There is no counseling about personal cleanliness." |
| Shown to the CHW (ML Kit back to Bangla) | প্রদত্ত তথ্য ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কোন নির্দিষ্ট পরামর্শের উল্লেখ করে না। অতএব, উত্তরটি হল: "ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কোন পরামর্শ নেই।" |

**Judged:** Claims the information gives no hygiene advice, when the card covers pads, daily bathing, breast care and perineal stitches. Hallucinated phrase: “ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কোন পরামর্শ নেই”.

### uc2_q025: INCORRECT

- **Card:** `0fa2eaf5:2` পারিবারিক খাবার খাওয়ানোর প্রয়োজনীয়তা ও উপকারিতা
- **Groundedness:** 0.41, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | পারিবারিক খাবার খাওয়ানোর প্রয়োজনীয়তা ও উপকারিতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the need for health workers to feed family food and benefit? |
| Card, Bangla | বাড়তি খাবার কেনার প্রয়োজন হয় না এবং খরচ কম হয়। শারীরিক বৃদ্ধি ও মানসিক বিকাশ ভালোভাবে হয়, ফলে শিশু সুস্থ ও হাসিখুশি থাকে। রোগ প্রতিরোধ ক্ষমতা বাড়ে ফলে রোগ-ব্যাধি কম হয়, শিশু অসুস্থ হলে তাড়াতাড়ি সুস্থ হয়ে উঠে। ঝামেলা কম ও সময় কম লাগে। |
| Card given to the model (ML Kit) | Additional food does not need to buy and the cost is less. Physical growth and emotional development is well, so the child is healthy and smiling. The disease increases the disease resistance, due to disease-disorders, the child is sick fast if it is sick. The trouble takes less time and less time. |
| Model's English answer | The information provided does not mention anything about the need for health workers to feed the family and benefit. |
| Shown to the CHW (ML Kit back to Bangla) | প্রদত্ত তথ্যটি স্বাস্থ্য কর্মীদের পরিবার এবং উপকারের প্রয়োজনীয়তা সম্পর্কে কিছু উল্লেখ করে না। |

**Judged:** Claims the information says nothing about the need and benefits, when the card lists cost, growth, immunity and time saved. Hallucinated phrase: “কিছু উল্লেখ করে না”.

### uc2_q027: PARTIAL

- **Card:** `952fbce0:7` ডায়রিয়ার প্রধান কারণ
- **Groundedness:** 0.70, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়ার প্রধান কারণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | The main reason for diarrhea - what is instruction? What will I advise? |
| Card, Bangla | সাধারণত জীবাণুর আক্রমণে ডায়রিয়া হয়ে থাকে। যেমন ভাইরাস (রোটাভাইরাস), ব্যাকটেরিয়া (ই কোলাই, সিগেলা, ভিবরিও কলেরা), এবং প্যারাসাইট (এন্টামিবা হিস্টোলাইটিকা, জিয়ারডিয়া)। |
| Card given to the model (ML Kit) | Generally, diarrhea is diarrhea in the attack. Such as viruses (Rotavyrus), bacteria (e-coli, cigela, villabo chiroshes), and parasites (Antamiba Histificica, Zeardia). |
| Model's English answer | The main reason for diarrhea is infections, such as viruses, bacteria, and parasites. |
| Shown to the CHW (ML Kit back to Bangla) | ডায়রিয়া জন্য প্রধান কারণ সংক্রমণ, যেমন ভাইরাস, ব্যাকটেরিয়া, এবং পরজীবী। |

**Judged:** Right cause categories (virus, bacteria, parasite) but drops every named organism the card lists, such as rotavirus, E. coli and Shigella.

### uc2_q030: PARTIAL

- **Card:** `8528ce04:2` যক্ষ্মা রোগী চিহ্নিত করার উপায় এবং কফ সংগ্রহের নিয়ম
- **Groundedness:** 0.86, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | যক্ষ্মা রোগী চিহ্নিত করার উপায় এবং কফ সংগ্রহের নিয়ম নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the way to identify tuberculosis patients and the rules of cuff collection? |
| Card, Bangla | যক্ষ্মা রোগী চিহ্নিত করার উপায় হলো কফ পরীক্ষা করে এবং বুকের এক্স-রে করে। কফ সংগ্রহের নিয়ম হলো সন্দেহজনক রোগীকে মোট দুই বার কফ দিতে হবে। প্রথমত, সকালে ঘুম থেকে উঠে মুখ ধোয়ার আগে একটি পটে কফ রাখতে হবে (সকালের কফ)। দ্বিতীয়ত, ল্যাবরেটরিতে বা স্মিয়ারিং সেন্টারে এসে আরও একটি পটে কফ দিতে হবে (স্পট কফ)। কফ বের করার জন্য রোগী পেছনে হাত রেখে গভীরভাবে শ্বাস নিয়ে বুকের গভীর থেকে কফ বের করতে হবে। মুখ থেকে থুতু দিলে হবে না। খোলা জায়গায় বাতাসের বিপরীতে মুখ করে কফ বের করতে হবে। খোলা স্থানে এবং লোকজন থেকে নিরাপদ দূরত্বে রোগী নির্দিষ্ট পাত্রে কফ সংগ্রহ করবে। |
| Card given to the model (ML Kit) | The way to identify tuberculosis patients is checking the cuff and the chest X-ray. The coffee collection rules are the suspicious patients have to pay a total of two times. First of all, before waking up in the morning, there will be a cough in a pot before wash (morning cuff). Secondly, there will be more coffee in the laboratory or in the Smearing Center (Spot Coffee). To get out of the cough, the patient will have to breathe deeply and breathe deeply from the breast. Do not spit from the mouth. In the open space, the coffee will be taken out in front of the wind. The patient will collect cuffs in a specific container in the open distance from open space and people. |
| Model's English answer | The way to identify tuberculosis patients is checking the cuff and the chest X-ray. The rules of cuff collection are based on the presence of a morning cough in a pot before washing and the collection of coffee in a specific container in an open space. |
| Shown to the CHW (ML Kit back to Bangla) | টিউবারকুলোসিস রোগীদের সনাক্ত করার উপায়টি কফ এবং বুকে এক্স-রে চেক করছে। কফ সংগ্রহের নিয়মগুলি ওয়াশিংয়ের আগে একটি পাত্রের একটি সকালের কাশিের উপস্থিতির উপর ভিত্তি করে তৈরি করা হয় এবং একটি খোলা স্থানটিতে একটি নির্দিষ্ট কন্টেইনারে কফি সংগ্রহের আগে। |

**Judged:** Sputum test and chest X-ray are right; the collection rule is garbled and drops the second spot sample and how to cough it up.

### uc2_q031: INCORRECT

- **Card:** `47f4d15a:2` ডেঙ্গু আরডিটি পরীক্ষা করার পদ্ধতি
- **Groundedness:** 0.48, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডেঙ্গু আরডিটি পরীক্ষা করার পদ্ধতি কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is the method of testing dengue RDT? What would I say to the patient as a sk? |
| Card, Bangla | প্রথমে হেক্সিসল দিয়ে পরীক্ষার জন্য নির্ধারিত আঙুলটি তুলা দিয়ে মুছে নিন এবং আঙুলটি শুকানোর জন্য এক মিনিট অপেক্ষা করুন। এরপর লেনসেট হোল্ডারে লেনসেটটি সেট করুন এবং গভীরতা নির্ণায়ক চার এ নির্ধারিত করুন। কুকিং ব্যারেল টান দিয়ে লেনসেটটি সেট করুন। ক্যাসেটটি ও ড্রপারটি বের করুন। পিছনে চার আঙুল ও সামনে বৃদ্ধাঙ্গুল দিয়ে তর্জনীকে চাপ দিয়ে ধরুন। লেনসিং যন্ত্রটি খাড়া করে ধরে আঙ্গুলের চামড়ায় স্পর্শ করুন। রিলিজ বাটনে চাপ দিন এবং আঙ্গুল চেপে মাত্র পাঁচ ফোটা রক্ত ড্রপারটিতে সংগ্রহ করুন। ক্যাসেটটির রক্ত শোষণের স্থানে তিন ফোটা রক্তের নমুনা রাখুন এবং এক ফোটা ডেঙ্গু এন্টিজেন বাফার সলুশনটি দিন। এবার দশ মিনিট অপেক্ষা করুন। |
| Card given to the model (ML Kit) | First remove the finger set for the test with Hexishol and wait a minute to dry the finger. Then set the Lenset in the Lenset Holder and set the depth criterion four. Set the Lenset with a cooking barrel tension. Find the cassette and the draper. Back to pressure the index finger with four fingers and front thongs. Touch the finger skin by steeping the lancing device. Press the release button and collect only five drops of blood drapers in the finger. Place three blown blood samples in the blood absorption of the cassette and give a fox dengue antigen buffer soluation. Wait ten minutes now. |
| Model's English answer | The method of testing dengue RDT involves using a finger set with a cooking barrel, setting the depth criterion, and using a specific procedure to collect blood samples. As a sk, I would say: "I tested the blood using the RDT method and found it to be positive." |
| Shown to the CHW (ML Kit back to Bangla) | ডেঙ্গু RDT পরীক্ষার পদ্ধতিটি একটি রন্ধন ব্যারেলের সাথে একটি আঙ্গুলের সেট ব্যবহার করে, গভীরতা মানদণ্ডের সাথে এবং রক্তের নমুনা সংগ্রহের একটি নির্দিষ্ট পদ্ধতি ব্যবহার করে। একটি স্কা হিসাবে, আমি বলব: "আমি আরডিটি পদ্ধতি ব্যবহার করে রক্ত পরীক্ষা করেছি এবং এটি ইতিবাচক বলে মনে করেছিলাম।" |

**Judged:** The method is unrecognisable ('cooking barrel', 'set of fingers') and it ends with an invented claim that a test came back positive. Hallucinated phrase: “আমি আরডিটি পদ্ধতি ব্যবহার করে রক্ত পরীক্ষা করেছি এবং এটি ইতিবাচক বলে মনে করেছিলাম”.

### uc2_q035: PARTIAL

- **Card:** `f62c103f:3` প্রসব পরবর্তী মায়ের যত্ন: পরিবার পরিকল্পনা ও বিপদ চিহ্ন
- **Groundedness:** 0.84, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী মায়ের যত্ন: পরিবার পরিকল্পনা ও বিপদ চিহ্ন নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | Care of the next mother's care: What is health work for family planning and danger mark? |
| Card, Bangla | প্রসবের ছয় সপ্তাহ পর্যন্ত স্বামী সহবাস করা যাবে না। প্রসবের পর দ্রুততম সময়ে যেকোনো জন্ম নিয়ন্ত্রণ পদ্ধতি গ্রহণ করতে হবে। প্রসব পরবর্তী বিপদ চিহ্ন দেখা দিলে সাথে সাথে হাসপাতালে বা উপজেলা স্বাস্থ্য কেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | The husband can not be sexual intercourse up to six weeks. After delivery, any birth control method must be taken at the fastest time. With the next danger mark of delivery, it should be referenced in the hospital or upazila health center. |
| Model's English answer | The health work for family planning and danger mark is to ensure that any birth control method is taken at the fastest time after delivery. |
| Shown to the CHW (ML Kit back to Bangla) | পারিবারিক পরিকল্পনা এবং বিপদ চিহ্নের জন্য স্বাস্থ্যের কাজটি নিশ্চিত করা যে কোনও জন্ম নিয়ন্ত্রণ পদ্ধতিটি বিতরণের পরে দ্রুততম সময়ে নেওয়া হয়। |

**Judged:** Keeps early birth control only; drops six weeks without intercourse and immediate referral on a danger sign.

### uc2_q037: INCORRECT

- **Card:** `d12af13b:3` নবজাতকের পরিচ্ছন্নতা ও স্বাস্থ্যবিধি
- **Groundedness:** 0.62, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | নবজাতকের পরিচ্ছন্নতা ও স্বাস্থ্যবিধি — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Newborn cleanliness and hygiene - What is the instruction? What will I advise? |
| Card, Bangla | নবজাতককে ধরার আগে ও পরে সাবান দিয়ে ভালো করে হাত ধুতে হবে। তিন দিন পর্যন্ত নবজাতককে গোসল করানো যাবে না এবং এক মাস পর্যন্ত চুল কাটা যাবে না। |
| Card given to the model (ML Kit) | Before and after the newborn, it will be washed well with soap. The newborn can not be bathed for three days and the hair can not be cut for a month. |
| Model's English answer | The instructions for newborn cleanliness and hygiene are to be followed with proper bathing and hair care. The newborn should be bathed for three days and hair cut after the first month. |
| Shown to the CHW (ML Kit back to Bangla) | নবজাতক পরিচ্ছন্নতা এবং স্বাস্থ্যবিধি এবং স্বাস্থ্যের যত্নের সাথে সঠিক স্নান এবং চুলের যত্নের সাথে অনুসরণ করা হবে। প্রথম মাসের পরে তিন দিন এবং চুল কাটা জন্য নবজাতক ধুয়ে ফেলা উচিত। |

**Judged:** Garbles the rules into washing after three days and after the first month, and drops handwashing before touching the newborn.

### uc2_q038: PARTIAL

- **Card:** `952fbce0:8` ডায়রিয়া ছড়ানোর কারণ
- **Groundedness:** 0.32, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে ডায়রিয়া ছড়ানোর কারণ দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | Seeing the cause of spreading diarrhea. What is the key? |
| Card, Bangla | ডায়রিয়া ছড়ানোর প্রধান কারণগুলো হলো: রান্না ও খাওয়ার জন্য বিশুদ্ধ পানি ব্যবহার না করা, পঁচা বা বাসি খাবার খাওয়া, খাবার ঢেকে না রাখা, মলত্যাগের পরে ও খাবার খাওয়ার আগে সাবান ও নিরাপদ পানি দিয়ে হাত না ধোয়া, স্যানিটারি ল্যাট্রিন ব্যবহার না করা, এবং মাংস, দুধ ও ডিম ভালোভাবে সিদ্ধ করে না খাওয়া। |
| Card given to the model (ML Kit) | The main causes of spreading diarrhea are: Do not use pure water for cooking and eating, do not cover the food or cover food, do not use the food, and wash hands with soap and safe water, do not use sanitary latrines, and meat, Milk and eggs do not eat well. |
| Model's English answer | The key is to ensure proper hygiene, including the use of clean water, proper washing of hands, and proper disposal of food. |
| Shown to the CHW (ML Kit back to Bangla) | কীটি সঠিক স্বাস্থ্যবিধি, পরিষ্কার জল, হাতের সঠিক ধুয়ে এবং খাদ্যের যথাযথ নিষ্পত্তি সহ সঠিক স্বাস্থ্যবিধি নিশ্চিত করা। |

**Judged:** Keeps clean water and handwashing; drops stale or uncovered food, sanitary latrines and cooking meat, milk and eggs well.

### uc2_q039: INCORRECT

- **Card:** `f9cdf65f:3` গর্ভবতী মহিলার ভিজিট ও শারীরিক পরিমাপের তথ্য
- **Groundedness:** 0.44, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে গর্ভবতী মহিলার ভিজিট ও শারীরিক পরিমাপের তথ্য সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | What counseling will be counseling about the information of pregnant women's visits and physical measurements? |
| Card, Bangla | স্বাস্থ্যকর্মী যে দিন গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সেই ভিজিটের তারিখ লিখতে হবে। স্বাস্থ্যকর্মী গর্ভের যে মাসে গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সংখ্যা অনুযায়ী সেই মাস লিখতে হবে। এএনসি চেকআপের সময় গর্ভবতী মহিলার ওজন (কেজিতে) সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। প্রথম এএনসি চেকআপে গর্ভবতী মহিলার উচ্চতা (সেন্টিমিটারে) সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। থার্মোমিটারে গর্ভবতী মহিলার শরীরের তাপমাত্রা (ফারেনহাইট ডিগ্রিতে) সঠিকভাবে মেপে সংখ্যায় লিখতে হবে। গর্ভবতী মহিলার রক্তচাপ সঠিকভাবে পরীক্ষা করে সংখ্যায় লিখতে হবে, যেমন 120 over 80 mmHg। ফিতা (মেজারিং টেপ) দিয়ে গর্ভবতী মায়ের জরায়ুর উচ্চতা (সেন্টিমিটারে) সঠিকভাবে মেপে সংখ্যায় লিখতে হবে। |
| Card given to the model (ML Kit) | Health workers will write the date of the visit to the pregnant woman who will provide ANC. In the month of health, the month of pregnancy will be written by the number according to the number of ANC services. During the ANC checkup, pregnant woman's weight (kg) must be properly determined in number. The first ANC will be properly determined by the height of the pregnant woman (centimeter) in the ACC. Pregnant woman's body temperature (Fahrenheit degree) in the thermometer must be properly written in the temperature. Pregnant woman will write the blood pressure properly and write in numbers, such as 120 Over 80 MMHG. With the ribbon (meizer tape), pregnant mother's uterine height (centimeters) must be correctly in the temperature. |
| Model's English answer | The information provided does not mention specific counseling details, but it does outline the information to be provided to the health worker. Therefore, the counseling would be about the information of the pregnant women's visits and physical measurements. |
| Shown to the CHW (ML Kit back to Bangla) | প্রদত্ত তথ্য নির্দিষ্ট পরামর্শদাতা বিশদ উল্লেখ করে না, তবে এটি স্বাস্থ্য কর্মীকে সরবরাহ করার জন্য তথ্যটি রূপরেখা করে। অতএব, কাউন্সিলিং গর্ভবতী মহিলাদের পরিদর্শন এবং শারীরিক পরিমাপের তথ্য সম্পর্কে হবে। |

**Judged:** Says the information gives no counselling detail; none of the visit date, month, weight, height or temperature entries.

### uc2_q041: PARTIAL

- **Card:** `8528ce04:3` কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে করণীয়
- **Groundedness:** 0.56, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে করণীয় কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | Where is cuff tested and tuberculosis is caught? What would I say to the patient as a sk? |
| Card, Bangla | কফ পরীক্ষা করা হয় সকল উপজেলা স্বাস্থ্যকেন্দ্র, সকল বক্ষব্যাধি ক্লিনিক ও হাসপাতাল, জেলা সদর হাসপাতাল, বিশেষায়িত হাসপাতাল, কমিউনিটি ক্লিনিক, নির্দিষ্ট এনজিও ক্লিনিক, সম্মিলিত সামরিক হাসপাতাল, সকল ইপিজেড, ফ্যাক্টরি, বিজিএমআই, বিকেএমআই এবং কারাগারসমূহে। যক্ষ্মা রোগ ধরা পড়লে দ্রুত চিকিৎসা শুরু করতে হবে। নিয়মিত এবং পূর্ণ মেয়াদে সেবিকার সামনে ওষুধ সেবন করতে হবে। চিকিৎসা চলাকালীন সময় রোগের অবস্থা বোঝার জন্য কফ পরীক্ষা করাতে হবে। যেখানে সেখানে কফ বা থুথু ফেলা যাবে না, রোগীর কফ, থুথু নির্দিষ্ট পাত্রে ফেলে পরে তা পুঁতে বা পুড়িয়ে ফেলতে হবে। হাঁচি, কাশির সময় রোগীকে মুখে রুমাল অথবা কাপড় ব্যবহার করতে হবে। |
| Card given to the model (ML Kit) | Cuff is tested all upazila health centers, all the chest clinics and hospitals, district sadar hospitals, specialized hospitals, community clinics, specific NGO clinic, collective military hospitals, all ePZ, factory, BGMI, BKMI and in prisons. If tuberculosis is diagnosed, it will be quick to start treatment. Regular and full term needs to be enjoyed in front of the nurse. During the treatment, we have to test cough to understand the condition of disease. Where there can not be coffed or spit, the patient's coffee, the spitthu has to put it in the specified container and burn it. During the sneezing, the patient will have to use handkerin or cloth in the face of cough. |
| Model's English answer | All the mentioned locations are in the area of Cuff, and if you catch tuberculosis, you can start treatment immediately. As a patient, you should be in front of the nurse during treatment. |
| Shown to the CHW (ML Kit back to Bangla) | সমস্ত উল্লিখিত অবস্থানগুলি কফের ক্ষেত্রে রয়েছে, এবং যদি আপনি ত্বককে ধরেন তবে আপনি অবিলম্বে চিকিত্সা শুরু করতে পারেন। রোগীর মতো, আপনি চিকিত্সার সময় নার্সের সামনে থাকা উচিত। |

**Judged:** Keeps prompt treatment and taking medicine in front of the provider; omits every place sputum is tested and garbles TB as 'skin'.

### uc2_q044: INCORRECT

- **Card:** `8f41be1a:3` টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা
- **Groundedness:** 0.59, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | What is the general and dangerous side effects of mama Ticker and what counseling about the management? |
| Card, Bangla | টিকা নেয়ার পর শিশুরা কি ধরণের সমস্যার সম্মুখীন হয় তা জানতে চাইবেন। তাদের বলা কথাগুলো মনযোগ সহকারে শুনবেন। পরে নিজে টিকার সাধারণ ও বিপদজনক সমস্যা এবং এর সমাধান নিয়ে আলোচনা করবেন। টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা বলতে পারবেন। |
| Card given to the model (ML Kit) | After the vaccine, the children will want to know what kind of problems are faced. They will listen to their words. Later, the negotiation of the ticker and discuss its solutions. Ticker can tell general and dangerous side effects and management. |
| Model's English answer | There is no information provided about the general and dangerous side effects of "Mama Ticker" or its management counseling. |
| Shown to the CHW (ML Kit back to Bangla) | "মামা টিকার" বা এর পরিচালনার পরামর্শের সাধারণ এবং বিপজ্জনক পার্শ্বপ্রতিক্রিয়া সম্পর্কে কোন তথ্য সরবরাহ করা নেই। |

**Judged:** Says no information is given. The expected card is itself trainer-session text with no side-effect content, so no rewrite could answer from it.

### uc2_q045: INCORRECT

- **Card:** `6818eb87:4` তাপমাত্রা পরিমাপের পদ্ধতি ও স্বাভাবিক মাত্রা
- **Groundedness:** 0.29, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | তাপমাত্রা পরিমাপের পদ্ধতি ও স্বাভাবিক মাত্রা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the health worker to treat temperature and normal levels? |
| Card, Bangla | তাপমাত্রা পরিমাপের জন্য প্রয়োজনীয় উপকরণ হলো থার্মোমিটার। তাপমাত্রা মাপার জন্য থার্মোমিটার এর কাঁচের অংশে ধরতে হবে (রুপালী অংশে ধরা যাবে না)। থার্মোমিটার ব্যবহার করার আগে অবশ্যই ঝাঁকিয়ে পারদ নিচের দিকে নামিয়ে আনতে হবে। থার্মোমিটারের মাথার রুপালী অংশ বগলের নীচে চাপ দিয়ে ধরে রাখতে হবে, বগলের নিচে যেন কোন কাপড় না থাকে। থার্মোমিটার শরীরে দুই মিনিট ধরে রাখতে হবে। তাপমাত্রা দেখার সময় থার্মোমিটারের তিন দেয়ালের সাদা রঙের অংশে ভাল ভাবে লক্ষ্য করতে হবে। সাদা রঙের উপর প্রসারিত পারদ চকচক করে উঠবে (রুপালী চিকন দাগ)। প্রসারিত পারদের শেষ মাথা যে দাগ বরাবর থাকবে, সেটাই সঠিক তাপমাত্রা। স্বাভাবিক তাপমাত্রা ৯৭° ফারেনহাইট থেকে ৯৯° ফারেনহাইট (৩৭° সেঃ)। |
| Card given to the model (ML Kit) | The equipment required for temperature measurement is thermometer. To measure the temperature, it will be captured in the glass of thermometer (not caught in the silver section). Before using the thermometer, it must be shown down to the bottom of the mercury. The silver part of the thermometer's head should be pressed under the bottom of the armpit, there is no cloth below the armpit. The thermometer body should be kept for two minutes. While viewing the temperature, the three walls of thermometer should be well-colored in the white color. Stretched mercury on white color will rise (silver chikan spots). The last temperature that will remain along the last head of the expanded cans. Normal temperature 97 ° Fahrenheit from 99 ° Fahrenheit (37 ° C). |
| Model's English answer | The information provided does not mention any specific health workers or treatments related to temperature measurement or normal body temperature. Therefore, there is no health worker mentioned in the answer. |
| Shown to the CHW (ML Kit back to Bangla) | প্রদত্ত তথ্য কোন নির্দিষ্ট স্বাস্থ্য কর্মী বা তাপমাত্রা পরিমাপ বা স্বাভাবিক শরীরের তাপমাত্রা সম্পর্কিত চিকিত্সা উল্লেখ করে না। অতএব, উত্তর কোন স্বাস্থ্যকর্মী উল্লেখ আছে। |

**Judged:** Claims the information does not cover temperature measurement, when the card gives the thermometer steps. Hallucinated phrase: “প্রদত্ত তথ্য কোন নির্দিষ্ট স্বাস্থ্য কর্মী বা তাপমাত্রা পরিমাপ”.

### uc2_q046: PARTIAL

- **Card:** `a6032a14:0` প্রসব পরবর্তী জটিলতা: অতিরিক্ত রক্তক্ষরণ
- **Groundedness:** 1.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী জটিলতা: অতিরিক্ত রক্তক্ষরণ কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | Next complexity of delivery: What is extra bleeding? What would I say to the patient as a sk? |
| Card, Bangla | প্রসবের পর অতিরিক্ত রক্তক্ষরণ একটি বিপদজনক লক্ষণ। যদি প্রসবের পর যোনিপথ দিয়ে অতিরিক্ত রক্তক্ষরণ হয়, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | A dangerous signs of excess bleeding after delivery. If there is extra bleeding with vaginals after delivery, it is a complication. In this case, refer to the health center quickly. |
| Model's English answer | Extra bleeding after delivery is a complication. |
| Shown to the CHW (ML Kit back to Bangla) | ডেলিভারি পরে অতিরিক্ত রক্তপাত একটি জটিলতা। |

**Judged:** Says heavy bleeding after delivery is a complication; drops that it is a danger sign needing urgent referral.

### uc2_q048: PARTIAL

- **Card:** `d12af13b:4` নবজাতকের টিকাদান
- **Groundedness:** 0.44, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে নবজাতকের টিকাদান দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | Seeing the newborn's immunization. What is the key? |
| Card, Bangla | জন্মের পর যত তাড়াতাড়ি সম্ভব বিসিজি টিকা দিতে হবে। দেড় মাস থেকে পনেরো থেকে আঠারো মাস পর্যন্ত মোট পাঁচ বার টিকা কেন্দ্রে যেতে হবে এবং দশটি রোগের টিকা নিতে হবে। |
| Card given to the model (ML Kit) | BCG vaccine should be done as soon as possible. From one and a half months to fifteen to eighteen months, there will be a total of five vaccine or vaccine in total and take ten diseases. |
| Model's English answer | The key is to ensure the BCG vaccine is administered as soon as possible, ideally between 1.5 months and 18 months to 18 months old. |
| Shown to the CHW (ML Kit back to Bangla) | কীটি হ'ল বিসিজি ভ্যাকসিন যত তাড়াতাড়ি সম্ভব পরিচালিত হয় তা নিশ্চিত করা, আদর্শভাবে 1.5 মাস এবং 18 মাসের মধ্যে 18 মাস বয়সী। |

**Judged:** BCG as soon as possible is right; the schedule is garbled and it drops the five visits and ten diseases.

### uc2_q050: INCORRECT

- **Card:** `f9cdf65f:4` গর্ভস্থ শিশুর নড়াচড়া ও ইডিমা লিপিবদ্ধকরণ
- **Groundedness:** 0.29, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | গর্ভস্থ শিশুর নড়াচড়া ও ইডিমা লিপিবদ্ধকরণ নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the health worker to do with the movement of the pregnant child's movement and Edema? |
| Card, Bangla | গর্ভবতী মহিলার সাথে কথা বলে গর্ভস্থ শিশু সাধারণত দৈনিক (অর্থাৎ 24 ঘন্টায়) যতবার নড়াচড়া করে তা সংখ্যায় লিখতে হবে। 20 সপ্তাহ (5 মাস) থেকে গর্ভস্থ শিশুর নড়াচড়া অনুভব করা যায় এবং এক্ষেত্রে সংশ্লিষ্ট ঘর পূরণ করতে হবে। স্বাভাবিক অবস্থায় গর্ভস্থ শিশু দিনে 8 থেকে 10 বার নড়াচড়া করে। নড়াচড়া না করলে অথবা কম নড়াচড়া করলে হাসপাতালে রেফার করতে হবে। এএনসি চেকআপের সময় সঠিকভাবে পরীক্ষা করে গর্ভবতী মহিলার ইডিমা পাওয়া গেলে '+' চিহ্ন দিতে হবে। অন্যথায় '-' চিহ্ন দিতে হবে। |
| Card given to the model (ML Kit) | Talking to the pregnant woman, the pregnant child will usually write down the number of times in the daily (ie 24 hours). From 20 weeks (5 months), the movement of the pregnant child can be felt and in this case, the relevant house must be filled. In normal condition, the child, a day, moves 8 to 10 times a day. If you do not move or move less, then you have to refer to the hospital. At the time of the ANC checkup, the pregnant woman's Edima will be given '+'. Otherwise the mark should be given. |
| Model's English answer | The health worker should ensure that the movement of the pregnant child is recorded and that the Edema is appropriately addressed. |
| Shown to the CHW (ML Kit back to Bangla) | স্বাস্থ্য কর্মী নিশ্চিত করা উচিত যে গর্ভবতী সন্তানের আন্দোলনটি রেকর্ড করা হয়েছে এবং এডমা যথাযথভাবে সম্বোধন করা হয়েছে। |

**Judged:** Says to record movement and address oedema without any content: no 8 to 10 movements a day, no 20 weeks, no referral on reduced movement.

### uc2_q051: PARTIAL

- **Card:** `dcde24a9:4` ম্যালেরিয়ার সাধারণ উপসর্গ
- **Groundedness:** 0.89, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ম্যালেরিয়ার সাধারণ উপসর্গ কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is the common symptom of malaria? What would I say to the patient as a sk? |
| Card, Bangla | ম্যালেরিয়ার প্রধান লক্ষণ হচ্ছে জ্বর। বর্তমানে জ্বর থাকতেও পারে আবার নাও থাকতে পারে। তবে জ্বরের ইতিহাস (বিগত 48 ঘন্টার মধ্যে জ্বর) থাকবে। তবে অন্যান্য অসুখেও এই ধরণের জ্বর হতে পারে যেমন: সর্দিকাশি, গলাব্যথা, কানপাকা, প্রস্রাবে জ্বালাপোড়া ইত্যাদি। ম্যালেরিয়া প্রবণ এলাকায় কোন রোগীর জ্বর থাকলে কিংবা জ্বরের ইতিহাস থাকলে এবং অন্য কোন রোগের লক্ষণ না থাকলে তাকে ম্যালেরিয়া বলে সন্দেহ করতে হবে। তাছাড়া, ম্যালেরিয়াপ্রবণ এলাকায় বসবাসরত কারো প্রচন্ড মাথাব্যথা, পেট ব্যথা বা ডায়রিয়া শুরু হলে বা কোন ব্যক্তি হঠাৎ অজ্ঞান হয়ে পড়লে ম্যালেরিয়া হয়েছে বলে সন্দেহ করতে হবে। |
| Card given to the model (ML Kit) | The main signs of malaria are fever. Currently there may be fever may not have again. But there will be fever history (fever in the last 48 hours). However, such a fever can also be such a fever, such as: False, Golapata, Kanpaka, urine etc. If there is a patient fever in malaria areas or if there is a fever history or if there is no sign of any other disease, then he will have to suspect malaria. Moreover, if someone living in malaria-printed areas, the abdominal pain or diarrhea should be suspected when there is a sudden unconscious or if a person becomes unconscious, malaria has been suspected. |
| Model's English answer | The common symptom of malaria is fever. If a patient has a fever in the last 48 hours, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it is a sign of malaria. If there is a fever, it |
| Shown to the CHW (ML Kit back to Bangla) | ম্যালেরিয়া সাধারণ উপসর্গ জ্বর। গত 48 ঘণ্টার মধ্যে একজন রোগীর জ্বর থাকলে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। যদি জ্বর থাকে তবে এটি ম্যালেরিয়ার একটি চিহ্ন। |

**Judged:** Fever and a fever history in the last 48 hours are right, then the sentence repeats in a loop; drops ruling out other causes of fever.

### uc2_q052: PARTIAL

- **Card:** `8528ce04:4` যক্ষ্মার চিকিৎসায় রোগীর ধরণ
- **Groundedness:** 0.52, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | যক্ষ্মার চিকিৎসায় রোগীর ধরণ — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Patient type in tuberculosis - what is the guidance? What will I advise? |
| Card, Bangla | যক্ষ্মার চিকিৎসায় রোগীকে দুই ধরণের শ্রেণীতে ভাগ করা হয়: নতুন রোগী এবং পুরাতন রোগী। নতুন রোগী হলো কফে জীবাণুযুক্ত নতুন রোগী এবং যিনি কখনো যক্ষ্মার চিকিৎসা নেননি অথবা এক মাসের কম সময় ধরে চিকিৎসা করেছেন। এছাড়াও কফ পরীক্ষায় নেগেটিভ কিন্তু এক্স-রে দ্বারা চিহ্নিত নতুন রোগী এবং ফুসফুস বহির্ভূত নতুন যক্ষ্মা রোগীও নতুন রোগীর অন্তর্ভুক্ত। পুরাতন রোগী হলো যারা যক্ষ্মার চিকিৎসা গ্রহণের পর সুস্থ হয়েছিলেন কিন্তু পরবর্তীতে আবার যক্ষ্মা রোগী হিসাবে চিহ্নিত হয়েছেন। যেসব রোগী পূর্বে চিকিৎসায় এক মাসের বেশি যক্ষ্মার ঔষধ খেয়েছে, দুই থেকে পাঁচ মাস চিকিৎসা করার পর কফে জীবাণু পাওয়া গেছে এমন রোগী বা চিকিৎসা ফেইলর রোগী, এবং ট্রিটমেন্ট আফটার লস্ট টু ফলোআপ বা অন্যান্য রোগীও পুরাতন রোগীর অন্তর্ভুক্ত। |
| Card given to the model (ML Kit) | In the treatment of tuberculosis, the patient is divided into two types of classes: new patient and old patient. The new patient is a new patient with a believer and who never treated tuberculosis or treated for less than one month. Also negative in the cough test, but the new patients and non-tuberculosis of the lungs are also known as X-rays are also included in the patient. The old patient is who healed after taking treatment of tuberculosis but later again the tuberculosis was identified as patient. The patients who have eaten more than one month in treatment, have been treated for more than two to five months, patients or medical fails patients who have been found in the Biggle Germs, and Treatment Off Lost to follow-up or other patients also include old patients. |
| Model's English answer | The guidance is to classify patients into "new patient" and "old patient" based on their treatment duration and subsequent TB status. New patients are those who have been treated for less than one month and have negative results in the TB test, while old patients are those who have been treated and have been followed up. Those who have eaten more than one month in treatment have been treated for more than two to five months, and those who have had treatment and medical failure are included in the "Old patient" category. |
| Shown to the CHW (ML Kit back to Bangla) | নির্দেশিকা তাদের চিকিত্সা সময়কাল এবং পরবর্তী টিবি স্ট্যাটাসের উপর ভিত্তি করে "নতুন রোগী" এবং "পুরানো রোগীর" রোগীদের শ্রেণীবদ্ধ করা। নতুন রোগী যারা এক মাসেরও কম সময়ের জন্য চিকিত্সা করা হয়েছে এবং টিবি পরীক্ষায় নেতিবাচক ফলাফল পেয়েছে, তখন পুরোনো রোগীরা যারা চিকিত্সা করা হয়েছে এবং অনুসরণ করা হয়েছে। যারা এক মাসেরও বেশি সময় ধরে খাওয়া হয়েছে তাদের দুই থেকে পাঁচ মাসেরও বেশি সময় ধরে চিকিত্সা করা হয়েছে, এবং যারা চিকিত্সা ও চিকিৎসা ব্যর্থতা আছে তাদের "পুরানো রোগী" বিভাগে অন্তর্ভুক্ত করা হয়েছে। |

**Judged:** New and old patient categories are right, but it defines new patients by a negative test and garbles the old-patient rule.

### uc2_q053: INCORRECT

- **Card:** `47f4d15a:4` ডেঙ্গু প্রতিরোধে করণীয়
- **Groundedness:** 0.63, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে ডেঙ্গু প্রতিরোধে করণীয় দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | We saw the dengue to prevent dengue. What is the key? |
| Card, Bangla | ডেঙ্গু প্রতিরোধে বাড়ির চারপাশ পরিষ্কার রাখুন। তিন দিনে এক দিন জমা পানি ফেলে দিন। ঘরের বাইরে ও ভেতরে কোন কৌটা বা ভাঙা হাড়ি, টায়ার, ফুলের টব, পরিত্যক্ত টায়ার, ডাবের খোসা, প্লাস্টিকের পাত্র, ভাঙ্গা বালতি, মাটির পাত্র, ফ্রিজের তলায় ইত্যাদিতে পানি জমিয়ে রাখবেন না। অব্যবহৃত পাত্র নষ্ট করে ফেলতে হবে বা উপুর করে রাখতে হবে যাতে পানি জমতে না পারে। মশারি টানিয়ে ঘুমান। বাইরে যাওয়ার সময় ফুলহাতা জামা কাপড় পড়ুন ও হাতে পায়ে রেপিলেন্ট লোশন লাগান। ঘরে মশা মারার ওষধ বা কয়েল ব্যবহার করুন। |
| Card given to the model (ML Kit) | Keep the surroundings of the house to prevent dengue. Throw the water deposited in three days. Do not waste water in the house or broken horizon, tire, tire, flower tub, abandoned tire, dub peel, plastic pot, broken bucket, soil pot, refrigerators, etc. in the house outside and inside the house. The unused vessel should be destroyed or kept on the fold so that the water can not get up. Sleeping by dragging the mosquito. Read the flowers of the flower shirt while going out and apply the repellent lotion on hand. Use drugs or coils to mosquitoes in the house. |
| Model's English answer | The key is to prevent the spread of dengue by keeping the water in the house and not letting it get out. |
| Shown to the CHW (ML Kit back to Bangla) | চাবিটি হাউসে পানি রাখার মাধ্যমে ডেঙ্গু ছড়িয়ে দেওয়ার এবং এটি বেরিয়ে আসার অনুমতি দেয় না। |

**Judged:** One garbled sentence; none of the prevention steps about stagnant water, containers or mosquito nets.

### uc2_q054: PARTIAL

- **Card:** `7a1f98e9:4` শ্বাসতন্ত্রের সংক্রমণের কারণ
- **Groundedness:** 0.98, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে শ্বাসতন্ত্রের সংক্রমণের কারণ সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | What counseling will be given about the cause of breathing infection? |
| Card, Bangla | শ্বাসতন্ত্রের সংক্রমণের প্রত্যক্ষ কারণ হলো ব্যাকটেরিয়া এবং ভাইরাস উভয়ই, যার মধ্যে অধিকাংশ ক্ষেত্রে ব্যাকটেরিয়ার কারণে নিউমোনিয়া হয়ে থাকে। পরোক্ষ কারণগুলোর মধ্যে রয়েছে শিশুর জন্মকালীন ওজন যদি দুই দশমিক পাঁচ কেজির কম হয়, শিশুকে যদি জন্মের পর পর গোসল দেয়া হয়, শিশু যদি পুষ্টিহীনতায় ভোগে, শিশুকে যদি জন্মের পর শাল দুধ খাওয়ানো না হয়, শিশুকে যদি ছয় মাস পর্যন্ত শুধুমাত্র বুকের দুধ খাওয়ানো না হয়, শিশুকে যদি রোগ প্রতিষেধক টীকা না দেয়া হয়, শিশুর যদি ভিটামিন এ এর অভাব থাকে, বাড়ির ভেতরের আবহাওয়া যদি বদ্ধ, ঠান্ডা, ভেজা বা স্যাঁতস্যাঁতে হয়, এবং যদি এক ঘরের মধ্যে অনেক লোক একসাথে বসবাস করে (ঘন বসতি)। |
| Card given to the model (ML Kit) | The direct reason for the infection of the breathing is both bacteria and viruses, in which most of which are pneumonia due to bacteria. Indirect causes include two decimal five kgs, if the child is less than five kg, if the child is given a bath after birth, if the child suffers from nurture, if the child is not fed after birth, if the child is not fed after six months of breast milk If not fed, if the child is not given to the disease antidote, if the child is lacking in Vitamin A, if the inner weather is closed, cold, wet or damp, and if many people live together (dense settlement). |
| Model's English answer | The direct reason for the infection of the breathing is both bacteria and viruses, in which most of which are pneumonia due to bacteria. The indirect causes include: / - If the child is less than five kg, / - If the child is given a bath after birth, / - If the child is not fed after birth, / - If the child is not fed after six months of breast milk, / - If the child is not given to the disease antidote, / - If the child is lacking in Vitamin A, / - If the inner weather is closed, cold, wet, or damp, / - If many people live together (dense settlements). |
| Shown to the CHW (ML Kit back to Bangla) | শ্বাসের সংক্রমণের সরাসরি কারণটি ব্যাকটেরিয়া এবং ভাইরাস উভয়ই, যার মধ্যে বেশিরভাগই ব্যাকটেরিয়া কারণে নিউমোনিয়া। পরোক্ষ কারণগুলির মধ্যে রয়েছে: - যদি শিশুটি পাঁচ কেজি কম হয় - যদি সন্তান জন্মের পরে স্নান করা হয় তবে - যদি শিশু জন্মের পরে খাওয়া হয় না তবে - যদি শিশুটি বুকের দুধের ছয় মাসের পরে খাওয়া হয় না - যদি শিশুটি রোগের এন্টিডোটে দেওয়া হয় না তবে - যদি শিশুটি ভিটামিন এতে অভাব রয়েছে, যদি অভ্যন্তরীণ আবহাওয়া বন্ধ থাকে, ঠান্ডা, ভিজা, বা স্যাঁতসেঁতে থাকে - যদি অনেক লোক একসাথে থাকে (ঘন বসতি স্থাপন)। |

**Judged:** Direct causes are right; the indirect list turns 2.5 kg birth weight into five kg and garbles the other factors. Hallucinated phrase: “যদি শিশুটি পাঁচ কেজি কম হয়”.

### uc2_q057: PARTIAL

- **Card:** `a6032a14:1` প্রসব পরবর্তী জটিলতা: জ্বর
- **Groundedness:** 0.43, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী জটিলতা: জ্বর — নির্দেশনা কী? আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Next complications of delivery: Fever - What is the instruction? What will I advise? |
| Card, Bangla | প্রসবের পর জ্বর একটি বিপদজনক লক্ষণ। যদি প্রসবের পর মায়ের জ্বর আসে, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | A dangerous signs of fever after delivery. If the mother fever comes after delivery, but it's a complication. In this case, refer to the health center quickly. |
| Model's English answer | The instruction is to refer to the health center immediately. |
| Shown to the CHW (ML Kit back to Bangla) | নির্দেশ অবিলম্বে স্বাস্থ্য কেন্দ্র পড়ুন হয়। |

**Judged:** Keeps quick referral to a health centre in broken grammar; drops that fever after delivery is a danger sign.

### uc2_q058: INCORRECT

- **Card:** `0fa2eaf5:5` বয়স অনুযায়ী শিশুর খাবারের পরিমাণ ও বার
- **Groundedness:** 0.39, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে বয়স অনুযায়ী শিশুর খাবারের পরিমাণ ও বার দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | According to the age of the village, I saw the amount of baby food. What is the key? |
| Card, Bangla | ছয় মাস থেকে আট মাস পূর্ণ শিশুর খাবার ২৫০ মিলি লিটার বাটির অর্ধেক বাটি দিনে দুই বার সাথে পুষ্টিকর নাশতা এক থেকে দুই বার। নয় মাস থেকে এগারো মাস পূর্ণ শিশুর খাবার ২৫০ মিলি লিটার বাটির অর্ধেক বাটি দিনে তিন বার সাথে পুষ্টিকর নাশতা এক থেকে দুই বার। বারো মাস থেকে তেইশ মাস পূর্ণ শিশুর খাবার ২৫০ মিলি লিটার বাটির এক বাটি দিনে তিন বার সাথে পুষ্টিকর নাশতা এক থেকে দুই বার। বাড়তি খাবারের সঙ্গে কমপক্ষে দুই বছর বয়স পর্যন্ত মায়ের দুধ খাওয়াতে হবে। |
| Card given to the model (ML Kit) | Six months to eight months full child food 250 ml liter bowl half bowls two times a day with nutritious destruction with two times a day. Nine months from eleven months full child food 250 ml liter of electricity, half a bowl with three times a day, nutritious breakfast with three times a day. Twelve months from twelve months, full child food for 250 ml liter bowl is one bowl of nutrients with three times a day to two times. With an additional food, milk will be eaten up to at least two years old. |
| Model's English answer | The key is to ensure that the child is getting the right amount of food at the right times according to their age and developmental stage. |
| Shown to the CHW (ML Kit back to Bangla) | কীটি তাদের বয়স এবং উন্নয়নমূলক পর্যায়ে সঠিক সময়ে সঠিক সময়ে সঠিক পরিমাণে খাদ্যের সঠিক পরিমাণে খাদ্য সরবরাহ করছে তা নিশ্চিত করা। |

**Judged:** A repetitive generic sentence; none of the bowl sizes or meal counts by age.

### uc2_q060: INCORRECT

- **Card:** `e660517c:5` ডায়রিয়া হলে স্যালাইন খাওয়ানোর নিয়ম
- **Groundedness:** 0.47, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়া হলে স্যালাইন খাওয়ানোর নিয়ম নিয়ে স্বাস্থ্যকর্মীর করণীয় কী? |
| Question given to the model (ML Kit) | What is the health worker to do with the rules of feeding saline if diarrhea? |
| Card, Bangla | বয়স ভেদে ডায়রিয়ার চিকিৎসা নিম্নরূপ: স্বাভাবিক খাবার অর্থাৎ ডায়রিয়া হওয়ার আগে রোগী যে খাবার খেত, সেই খাবার খাওয়াতে হবে এবং যেসব শিশু মায়ের দুধ খায় তাদেরকে দুধ খাওয়ানো চালিয়ে যেতে হবে। |
| Card given to the model (ML Kit) | Diariar's treatment is as follows: Normal foods, ie before diarrhea, the food that eats the food, and the children who eat mother's milk should continue to feed them. |
| Model's English answer | The health worker should follow the rules of feeding saline, using only the information provided. |
| Shown to the CHW (ML Kit back to Bangla) | স্বাস্থ্য কর্মী শুধুমাত্র প্রদত্ত তথ্য ব্যবহার করে লবণাক্ততা খাওয়ানোর নিয়ম অনুসরণ করা উচিত। |

**Judged:** Says to follow the saline rule without stating it; the card's point, to continue normal food and breastfeeding, is missing.

### uc2_q061: INCORRECT

- **Card:** `f9cdf65f:5` গর্ভবতী মহিলার রক্ত ও প্রস্রাব পরীক্ষার ফলাফল
- **Groundedness:** 0.26, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | রোগী গর্ভবতী মহিলার রক্ত ও প্রস্রাব পরীক্ষার ফলাফল জানতে চেয়েছে। মূল কথাগুলো (গর্ভবতী ও মহিলার) কী বলব? |
| Question given to the model (ML Kit) | The patient wanted to know the results of the pregnant woman's blood and urine test. What is the main words (pregnant and woman)? |
| Card, Bangla | এএনসি চেকআপের সময় গ্লুকোমিটারে গর্ভবতীর গ্লুকোজ নির্ণয় করা হয়ে থাকলে (অথবা প্যাথলজিক্যাল পরীক্ষার রিপোর্ট থাকলে) ফলাফল লিখতে হবে, যেমন 7.0 মিলিমোল/লিটার। এএনসি চেকআপের সময় হিমোগ্লোবিনোমিটারে গর্ভবতীর রক্তের হিমোগ্লোবিনের মাত্রা নির্ণয় করে সংখ্যায় লিখতে হবে, যেমন 12.5 gm/dl। ইউরিন স্ট্রিপের মাধ্যমে গর্ভবতীর প্রস্রাব পরীক্ষা করে প্রাপ্ত ফলাফল চিহ্ন দিয়ে লিখতে হবে। যেমন - অস্বাভাবিক/পজেটিভ হলে কৌটার নির্দেশনা অনুযায়ী টিক চিহ্ন দিয়ে (+/++/+++) ফলাফল লিখতে হবে। ফলাফল স্বাভাবিক থাকলে (-) টিক চিহ্ন দিয়ে ফলাফল লিখতে হবে। বিলিরুবিন পরীক্ষার ক্ষেত্রেও একই পদ্ধতি অনুসরণ করতে হবে। |
| Card given to the model (ML Kit) | If the pregnant glucose of glucometer is determined during the AC checkup (or if there is a pathological test report), such as 7.0 millimol / liter. During the AC checkup, in hemoglobinometer, it should be written in the number of hemoglobin levels of pregnant blood, such as 12.5 GM / DL. Through the Urine Strip, the results of pregnant piss will be written by the results. For example - if unusual / positive, you must enter the results of (+ / ++ / +++) with the coach guidelines. If the results are normal (-) to write results with check marks. The same procedure must be followed in the case of Bilirubin examination. |
| Model's English answer | The main words "pregnant" and "woman" are mentioned in the context of blood and urine tests. The health worker should directly state these words as part of the information provided. |
| Shown to the CHW (ML Kit back to Bangla) | রক্ত এবং প্রস্রাব পরীক্ষার প্রসঙ্গে "গর্ভবতী" এবং "নারী" প্রধান শব্দগুলি উল্লেখ করা হয়েছে। স্বাস্থ্য কর্মী সরাসরি প্রদত্ত তথ্যের অংশ হিসাবে সরাসরি এই শব্দগুলি রাষ্ট্রকে রাষ্ট্রকে অবশ্যই বলে। |

**Judged:** Talks about the words 'pregnant' and 'woman' instead of the glucose, haemoglobin and urine results to record.

### uc2_q063: INCORRECT

- **Card:** `8528ce04:5` ডট (DOT) কী এবং এর কৌশলসমূহ
- **Groundedness:** 0.40, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | পরিবারকে ডট (DOT) কী এবং এর কৌশলসমূহ বলতে হবে। কৌশলসমূহ ও হলো নিয়ে কী নির্দেশনা দেব? |
| Question given to the model (ML Kit) | The family will say dot (dot) and its strategies. What will I do with the strategies? |
| Card, Bangla | ডট (DOT) হলো রোগী নিয়মিত ডাক্তার, স্বাস্থ্যকর্মী বা স্বাস্থ্য সেবিকার সামনে সরাসরি পর্যবেক্ষণের মাধ্যমে চিকিৎসার পুরো মেয়াদে ওষুধ সেবন করা। ডট এর কৌশলসমূহ হলো: মাঠ কার্যক্রমে ফিল্ড অর্গানাইজার (FO) এবং কমিউনিটি হেলথ ওয়ার্কার (CHW) দের কাজে সহযোগিতা করা। সম্ভাব্য যক্ষ্মা রোগীর কফ সংগ্রহ করা এবং টিউবারকুলোসিস ডায়াগনোসিস সেন্টার (TDC) তে রোগী রেফার করা। যক্ষ্মা ধরা পড়লে ডটস পদ্ধতিতে চিকিৎসা প্রদান করা। ওষুধ সেবনের পর যদি কোন রোগীর পার্শ্ব-প্রতিক্রিয়া দেখা দেয় তাহলে অফিসে যোগাযোগ করা এবং রোগী রেফার করা। নিয়মিত রিফ্রেসার্সে অংশগ্রহণ করা। সকল ক্যাটাগরিতেই ইনটেনসিভ ফেজে এবং কন্টিনিউয়েশন ফেজে প্রতিদিন সকালে খালি পেটে রোগী সেবিকার বাড়িতে এলে স্বাস্থ্যসেবিকা তাকে সেবন বিধি মোতাবেক ওষুধ খাইয়ে দিবেন। যদি কোন নির্দিষ্ট দিনে রোগী সেবিকার বাড়িতে না আসে তবে সঙ্গে সঙ্গে সেবিকা রোগীর বাড়িতে গিয়ে, খোঁজ নিয়ে অনুপস্থিতির কারণ জেনে সমস্যা সমাধান করে ঐ দিনের ওষুধ খাইয়ে দেবেন। পার্শ্ব প্রতিক্রিয়ার খবর পাওয়ার পরপরই রোগীকে সঙ্গে সঙ্গে নিকটস্থ স্বাস্থ্যকেন্দ্রে রেফার করতে হবে অথবা ব্র্যাক পিওকে জানাতে হবে। |
| Card given to the model (ML Kit) | Dot (DOT) is to enjoy medicines for the entire period of treatment through direct observation of patients, health workers or health nurseries. The techniques of dot are: Field Organizer (FO) and community Health Worker (CHW) in field activities. To collect potential tuberculosis patient cuffs and refer to the patient in Tuberculosis Diagnosis Center (TDC). Tuberculosis is treated in Dots Systems. If a patient comes after the medication, contact the office and refer to the patient. Participate in regular refreshers. In all the categories, the healthcare will feed the medicine according to the rules of enjoying him in an intensive phase and continuing phase in the patient. If a patient does not come to the house of a day, then the nurse will go to the patient's home, find the reason for the absence of the absence, and solve the day of the day. Immediately after the news of the side effect, the patient should be referred to nearest health centers or to tell BRAC PO. |
| Model's English answer | Use the information provided to implement the strategies as a health worker. |
| Shown to the CHW (ML Kit back to Bangla) | স্বাস্থ্য কর্মী হিসাবে কৌশল বাস্তবায়নের জন্য প্রদত্ত তথ্য ব্যবহার করুন। |

**Judged:** Says to use the provided information; no definition of DOT and none of its strategies.

### uc2_q064: PARTIAL

- **Card:** `e65337d3:0` ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের প্রস্তুতি
- **Groundedness:** 0.49, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | SK হিসেবে ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের প্রস্তুতি কাউন্সেলিংয়ে ডেঙ্গু কীভাবে ব্যাখ্যা করব? |
| Question given to the model (ML Kit) | How do I explain Dengue in the counseling of the dengue Rapid Diagnostic Test (RDT) Kit as SK? |
| Card, Bangla | ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের আগে নিশ্চিত করুন যে কিটটি ঘরের তাপমাত্রায় আছে। যদি কিটটি ফ্রিজে রাখা থাকে, তবে ব্যবহারের কমপক্ষে 30 মিনিট আগে ফ্রিজ থেকে বের করে ঘরের তাপমাত্রায় আনুন। কিটটি খোলার আগে প্যাকেটের মেয়াদ উত্তীর্ণের তারিখ পরীক্ষা করুন। মেয়াদ উত্তীর্ণ কিট ব্যবহার করবেন না। কিটটি খোলার পর যত দ্রুত সম্ভব ব্যবহার করুন। |
| Card given to the model (ML Kit) | Before using the Dengue Rapid Diagnostic Test (RDT) kit, make sure that the kit is at home temperature. If the kit is kept in the refrigerator, bring the use of the refrigerator at least 30 minutes before the use of the cell temperature. Check the expiration date of the packet before opening the kit. Do not use expired kit. Use as quickly as possible after opening the kit. |
| Model's English answer | Dengue is a severe and potentially life-threatening disease that can be diagnosed quickly using the Dengue Rapid Diagnostic Test (RDT) kit. If the kit is at home, you should check the temperature first. If the temperature is not correct, the test may not work properly. Make sure the kit is used correctly, including checking the expiration date and using it as soon as possible. |
| Shown to the CHW (ML Kit back to Bangla) | ডেঙ্গু একটি গুরুতর এবং সম্ভাব্য জীবন-হুমকিযুক্ত রোগ যা ডেঙ্গু র্যাপিড ডায়াগনস্টিক টেস্ট (আরডিটি) কিট ব্যবহার করে দ্রুত নির্ণয় করতে পারে। যদি কিট বাড়িতে থাকে তবে আপনাকে প্রথমে তাপমাত্রা পরীক্ষা করা উচিত। তাপমাত্রা সঠিক না হলে, পরীক্ষা সঠিকভাবে কাজ করতে পারে না। মেয়াদ শেষ হওয়ার তারিখটি পরীক্ষা করে এবং যত তাড়াতাড়ি সম্ভব এটি ব্যবহার করে কিটটি সঠিকভাবে ব্যবহার করা হয় তা নিশ্চিত করুন। |

**Judged:** Keeps checking temperature and expiry and using the kit promptly; drops the 30 minutes out of the fridge and adds a severity claim not on the card. Hallucinated phrase: “ডেঙ্গু একটি গুরুতর এবং সম্ভাব্য জীবন-হুমকিযুক্ত রোগ”.

### uc2_q068: PARTIAL

- **Card:** `0fa2eaf5:6` শিশুর জন্য পুষ্টিকর পারিবারিক খাবার তৈরির নিয়ম
- **Groundedness:** 0.60, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | পরিবারকে শিশুর জন্য পুষ্টিকর পারিবারিক খাবার তৈরির নিয়ম বলতে হবে। শিশুর ও পুষ্টিকর নিয়ে কী নির্দেশনা দেব? |
| Question given to the model (ML Kit) | The family will have to say the rules for making nutritious family foods for the baby. What do you guidelines with children and nutritious? |
| Card, Bangla | প্রথমে সাবান ও নিরাপদ পানি দিয়ে ভালোভাবে হাত ধুয়ে নিন এবং ২৫০ মিলি লিটার মাপের বাটি ধুয়ে নিন। বাটিতে একটুকরা মাছ, মাংস, ডিম, কলিজা যে কোন একটি নিয়ে চটকে নিন। এরপর বাটিতে ঘন ডাল, সবজি এবং বাকি টুকু ভাত দিয়ে পূরণ করুন। সবকিছু নেওয়ার পর একসঙ্গে মাখান। খেয়াল রাখতে হবে মোট তৈরি করা খাবারের পরিমাণ যাতে বয়স অনুযায়ী ঠিক থাকে। প্রতিদিন একবেলার খাবারের সঙ্গে পুষ্টিকণা মেশাতে হবে। |
| Card given to the model (ML Kit) | First wash hands with soap and safe water and wash the size of 250 ml liter. Take a lot of fish, meat, eggs, colossies in the bowl. Then fill in the thick pulse, vegetables and the rest of the tuku with the rice. Together after taking everything. Keep in mind that the amount of food made is that it is all right according to age. Every day you need to mix nutritious with food. |
| Model's English answer | The guidelines for making nutritious family foods for the baby are: / - Wash hands with soap and safe water. / - Prepare a meal with 250 ml of liquid (e.g., vegetable soup, rice, etc.). / - Include a variety of foods (fish, meat, eggs, vegetables, rice) in a balanced meal. / - Ensure the food is prepared according to the child's age and needs. / - Mix the food together daily to ensure it's nutritious. |
| Shown to the CHW (ML Kit back to Bangla) | শিশুর জন্য পুষ্টিকর পরিবারের খাবার তৈরির নির্দেশিকাগুলি হল: - সাবান এবং নিরাপদ পানির সাথে হাত ধুয়ে নিন। - 250 মিলিমিটার তরল (যেমন, উদ্ভিজ্জ স্যুপ, চাল, ইত্যাদি) দিয়ে একটি খাবার প্রস্তুত করুন। - একটি সুষম খাবারের মধ্যে বিভিন্ন খাবার (মাছ, মাংস, ডিম, শাকসবজি, চাল) অন্তর্ভুক্ত করুন। - শিশুর বয়স এবং চাহিদা অনুযায়ী খাদ্য প্রস্তুত করা নিশ্চিত করুন। - এটি পুষ্টিকর নিশ্চিত করার জন্য প্রতিদিন খাদ্যটি মিশ্রিত করুন। |

**Judged:** Handwashing, a 250 ml bowl and the food groups are there, but it turns the bowl into 250 ml of liquid such as soup. Hallucinated phrase: “উদ্ভিজ্জ স্যুপ”.

### uc2_q069: PARTIAL

- **Card:** `d12af13b:6` কম জন্ম ওজনের শিশুর বিশেষ যত্ন
- **Groundedness:** 0.72, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | SK হিসেবে কম জন্ম ওজনের শিশুর বিশেষ যত্ন কাউন্সেলিংয়ে জন্ম কীভাবে ব্যাখ্যা করব? |
| Question given to the model (ML Kit) | How do I explain the birth of special care counseling in the special care counseling of less born child? |
| Card, Bangla | কম জন্ম ওজনের শিশুর জন্য কিছু বিশেষ সেবা রয়েছে। শিশুর ওজন স্বাভাবিক না হওয়া পর্যন্ত শিশুকে মায়ের বুকের ত্বকের সঙ্গে লাগিয়ে রাখুন। পরিষ্কার ও নরম কাপড় দিয়ে নবজাতকের মাথাসহ সমস্ত শরীর মুড়ে দিন। ঘন ঘন মায়ের বুকের দুধ খাওয়ান। সাত দিনের আগে শিশুকে গোসল করাবেন না। শিশুকে ধরার পূর্বে সাবান দিয়ে ভালোভাবে দুই হাত ধুয়ে নিন। মায়ের দুধ টেনে খেতে না পারলে চেপে দুধ বের করে বাটিতে করে চামচ দিয়ে খাওয়ান। |
| Card given to the model (ML Kit) | There are some special services for less born children. Keep the baby with the skin of the mother's chest until the child's weight is normal. Clear all the body with clean and soft cloth with newborn heads. Frequent mother breastfeeding. Do not bathe the child seven days ago. Before catching the child, wash two hands well with soap. If you can not drag the milk of the mother, feed the milk and feed it with a spoon. |
| Model's English answer | Special care for a less-birth child involves: / - Keeping the baby with the mother's chest skin. / - Washing the baby with clean and soft cloth. / - Frequent breastfeeding. / - Not bathing the baby seven days ago. / - Washing hands before feeding. / - Feeding with a spoon. /  / These are the key points of special care for a less-birth child. |
| Shown to the CHW (ML Kit back to Bangla) | একটি কম জন্মের সন্তানের জন্য বিশেষ যত্ন জড়িত থাকে: - শিশুর মায়ের বুকে ত্বকের সাথে রাখা। - পরিষ্কার এবং নরম কাপড় দিয়ে শিশুর ধুয়ে নিন। - ঘন ঘন breastfeeding। সাত দিন আগে শিশুর স্নান না। - খাওয়ানোর আগে হাত ধুয়ে নিন। - একটি চামচ দিয়ে খাওয়ানো। এটি একটি কম জন্মের সন্তানের জন্য বিশেষ যত্নের মূল পয়েন্ট। |

**Judged:** Skin-to-skin, frequent breastfeeding, no bath before 7 days, handwashing and spoon feeding are right; 'wrap in a soft cloth' became 'wash with a cloth'.

### uc2_q070: INCORRECT

- **Card:** `e660517c:6` ডায়রিয়া হলে বেবী জিংক খাওয়ানোর নিয়ম
- **Groundedness:** 0.75, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়া হলে বেবী জিংক খাওয়ানোর নিয়ম নিয়ে প্রশ্ন এসেছে। ডায়রিয়া ও হলে কী বলব? |
| Question given to the model (ML Kit) | If diarrhea, there is a question about the rules of Babi Jink feeding. What would you say if diarrhea? |
| Card, Bangla | ডায়রিয়া হলে স্যালাইনের পাশাপাশি প্রতিদিন একটি করে মোট দশটি বড়ি খাওয়াতে হবে। একটা চামচে একটু পানি নিয়ে একটা জিংক দিতে হবে। বড়ি গলে গেলে শিশুকে খাওয়াতে হবে। শিশু বমি করে দিলে ত্রিশ মিনিট অপেক্ষা করে আবার একটি বড়ি খাওয়াতে হবে। |
| Card given to the model (ML Kit) | If diarrhea, you have to eat ten pills every day as well as saline. A jink will be given a little water with a spoon. If the pill is melted, you have to feed the baby. If the child vomits thirty minutes, then you have to eat a pill. |
| Model's English answer | If diarrhea, there is a question about the rules of Babi Jink feeding. |
| Shown to the CHW (ML Kit back to Bangla) | ডায়রিয়া যদি বাবু জেঙ্কের ফিডিংয়ের নিয়ম সম্পর্কে একটি প্রশ্ন থাকে। |

**Judged:** Restates the question; none of the zinc dose, the ten days or what to do after vomiting.

### uc2_q071: PARTIAL

- **Card:** `f9cdf65f:6` আল্ট্রাসোনোগ্রাম, টিটি টিকা ও আয়রন/ক্যালসিয়াম ট্যাবলেট
- **Groundedness:** 0.92, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | আল্ট্রাসোনোগ্রাম, টিটি টিকা ও আয়রন/ক্যালসিয়াম ট্যাবলেট — key counselling points কী? |
| Question given to the model (ML Kit) | Ultrasonogram, TT vaccine and iron / calcium tablet - key counseling points? |
| Card, Bangla | আল্ট্রাসোনোগ্রাম করা হয়ে থাকলে টিক (✓) চিহ্ন দিতে হবে। 20 সপ্তাহের মাঝে আল্ট্রাসোনোগ্রাম করার জন্য উৎসাহিত করা। আল্ট্রাসোনোগ্রাম করা না হয়ে থাকলে (X) চিহ্ন দিতে হবে। গর্ভবতী মহিলার সাথে কথা বলে অথবা টিটি টিকার কার্ড দেখে লিখতে হবে। টিকার কার্ড না থাকলে টিটি টিকার সম্পর্কিত ডোজ সংখ্যায় লিখে পাশে '+' দিতে হবে। পূর্বে টিটি টিকার ডোজ সম্পূর্ণ করে থাকলে কার্ডে সম্পূর্ণ কথাটি লিখতে হবে। এএনসি চেকআপের সময় গর্ভবতী মহিলাকে সেবা প্রদান করার সময় আয়রন বড়ি নিয়ম অনুযায়ী খাচ্ছে কিনা তা জিজ্ঞাসা করে এই ঘরে টিক (✓) চিহ্ন দিতে হবে। আয়রন বড়ি না খেলে (X) চিহ্ন দিতে হবে। আয়রন বড়ি অনিয়মিত খেলে ঘরে টিক (✓) চিহ্ন দিয়ে অনিয়মিত কথাটি লিখতে হবে। ক্যালসিয়াম ট্যাবলেটের ক্ষেত্রেও একই পদ্ধতি অনুসরণ করতে হবে। |
| Card given to the model (ML Kit) | Tick (✓) sign should be given if ultrasonogram is done. Encourage for Ultrasonogram in 20 weeks. If ultrasonogram is not done (x) should be given. Talk to pregnant woman or write the T-Ticker Card. If you do not have a ticker card, then you must enter the Dosage of the T-Ticker and send it to '+'. If you complete the TT Ticker Dose earlier, then you have to write the whole word on the card. This cell must be given by asking whether Iron is eating as per the rules of the pregnant woman during the AC checkup. Iron pills will not play (x) mark. Iron pills should write irregular words with tick (✓) marks in irregular. The same procedure must be followed for calcium tablets. |
| Model's English answer | - If ultrasonogram is done, a tick should be given. / - Encourage ultrasonogram in 20 weeks. / - If ultrasonogram is not done, a tick should be given. / - Talk to pregnant women or write the T-Ticker Card. / - If you don't have a T-Ticker, enter the Dosage and send it to '+'. / - If you complete the T-Ticker Dose earlier, write the whole word on the card. |
| Shown to the CHW (ML Kit back to Bangla) | - ultrasonogram সম্পন্ন করা হয়, একটি টিক দেওয়া উচিত। - 20 সপ্তাহের মধ্যে আল্ট্রাসোনোগ্রাম উত্সাহিত করুন। - ultrasonogram সম্পন্ন না হলে, একটি টিক দেওয়া উচিত। - গর্ভবতী মহিলাদের সাথে কথা বলুন বা টি-টিকার কার্ড লিখুন। - যদি আপনার কোনও টি-টিকার না থাকে তবে ডোজটি প্রবেশ করুন এবং এটি '+' এ পাঠান। - আপনি যদি টি-টিকার ডোজটি আগেটি সম্পূর্ণ করেন তবে কার্ডে পুরো শব্দটি লিখুন। |

**Judged:** Follows the card's recording steps, but tells the worker to put a tick when no ultrasound was done, where the card says a cross. Hallucinated phrase: “ultrasonogram সম্পন্ন না হলে, একটি টিক দেওয়া উচিত”.

### uc2_q074: INCORRECT

- **Card:** `e65337d3:1` ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের পদ্ধতি
- **Groundedness:** 0.38, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের পদ্ধতি danger signs ও করণীয় কী? |
| Question given to the model (ML Kit) | What is the use of dengue Rapid Diagnostic Test (RDT) kit? |
| Card, Bangla | প্রথমে রোগীর আঙুল থেকে রক্ত সংগ্রহ করুন। সংগৃহীত রক্তের এক ফোঁটা কিটের গোলাকার গর্তে (স্যাম্পল ওয়েল) দিন। এরপর বাফার সলিউশনের দুই ফোঁটা একই গোলাকার গর্তে দিন। 15 থেকে 20 মিনিট অপেক্ষা করুন। এই সময়ের মধ্যে ফলাফল দেখা যাবে। 20 মিনিটের বেশি সময় ধরে ফলাফল দেখলে তা সঠিক নাও হতে পারে। |
| Card given to the model (ML Kit) | First collect blood from the patient's finger. Give a scarlet of blood collected in a spot of kit (sample well). Then two drops of buffer solutions are in the same round hole. Wait 15 to 20 minutes. The results can be seen in this period. Seeing results for more than 20 minutes, it may not be right. |
| Model's English answer | The use of the Dengue Rapid Diagnostic Test (RDT) kit is to detect the presence of dengue virus in a blood sample. The information provided indicates that the test is used to identify whether a patient has dengue, and if so, to determine the status of the disease. |
| Shown to the CHW (ML Kit back to Bangla) | ডেঙ্গু র্যাপিড ডায়াগনস্টিক টেস্ট (আরডিটি) কিট ব্যবহার রক্তের নমুনায় ডেঙ্গু ভাইরাসের উপস্থিতি সনাক্ত করা। প্রদত্ত তথ্যটি নির্দেশ করে যে রোগীর ডেঙ্গু আছে কিনা তা সনাক্ত করার জন্য পরীক্ষাটি ব্যবহার করা হয় এবং যদি এটি রোগের অবস্থা নির্ধারণ করতে হয়। |

**Judged:** Says what the test detects; none of the steps: blood drop, two buffer drops, 15 to 20 minutes, invalid after 20.

### uc2_q075: PARTIAL

- **Card:** `7a1f98e9:6` শিশুকে কখন হাসপাতালে রেফার করতে হবে?
- **Groundedness:** 0.86, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | Patient কে শিশুকে কখন হাসপাতালে রেফার করতে হবে? নিয়ে কী advice দিব? |
| Question given to the model (ML Kit) | When will Patient who have to refer to the hospital? What advice? |
| Card, Bangla | শিশুকে হাসপাতালে রেফার করতে হবে যদি দ্রুত শ্বাসের সাথে বুকের পাঁজর ডেবে যায়, শিশুকে খাওয়ানো না যায়, অস্বাভাবিক ঘুম ঘুম ভাব থাকে, শ্বাস-প্রশ্বাসের সময় সাঁই সাঁই বা গড় গড় শব্দ হয়, খিঁচুনি হয়, অতিরিক্ত জ্বর বা স্বাভাবিকের চেয়ে তাপমাত্রা কমে যায়, অথবা অতিরিক্ত বমি হয়। |
| Card given to the model (ML Kit) | Children should be referred to a hospital if the chest ribs with a fast breath, the child does not have to be fed, there is a unusual sleep, during the breathing, there is a mean-a-average word, seizure, the temperature is reduced, or extra than normal Vomiting. |
| Model's English answer | When a child has symptoms like chest ribs with a fast breath, unusual sleep, mean-a-average word, seizure, temperature reduced, or vomiting, they should be referred to the hospital. |
| Shown to the CHW (ML Kit back to Bangla) | যখন একটি শিশুর একটি দ্রুত শ্বাস, অস্বাভাবিক ঘুম, গড়-গড় শব্দ, জব্দ, তাপমাত্রা হ্রাস বা উল্টানো, তারা হাসপাতালে উল্লেখ করা উচিত। |

**Judged:** Fast breathing, abnormal sleepiness, noisy breathing, convulsions and low temperature are there; drops chest indrawing, inability to feed, high fever and vomiting.

### uc2_q076: PARTIAL

- **Card:** `6818eb87:7` ইডিমা দেখার নিয়ম ও করণীয়
- **Groundedness:** 0.82, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ইডিমা দেখার নিয়ম ও করণীয় — key counselling points কী? |
| Question given to the model (ML Kit) | Conditions of EDLLY and Do - What is the key counseling points? |
| Card, Bangla | গর্ভবতীকে শুতে বলতে হবে। পায়ের নিচের অংশের কাপড় কিছুটা উপরে উঠাতে বলতে হবে। পায়ের গোড়ালির ভেতরের দিকে যে উঁচু চ্যাপ্টা হাড় আছে তা চিহ্নিত করতে হবে। চ্যাপ্টা হাড়ের চামড়ার উপর দুই হাতের বৃদ্ধাঙ্গুলি দিয়ে সমানভাবে চাপ দিয়ে ৬০ সেকেন্ড বা ১ মিনিট ধরে রাখতে হবে। তারপর ছেড়ে দিয়ে কয়েক সেকেন্ড (৫ থেকে ১০ সেকেন্ড) অপেক্ষা করতে হবে। জায়গাটি ডেবে আছে কি না তা দেখতে হবে। ডেবে গেলে ধরা যেতে পারে যে ইডিমা রয়েছে। ইডিমার সাথে সম্পর্কিত পরীক্ষা হলো প্রস্রাবে অ্যালবুমিন এবং রক্তচাপ পরিমাপ। এই দুটো পরীক্ষার ফলাফল যদি স্বাভাবিক থাকে তাহলে শুধু পরামর্শ দিন। এই দুটো পরীক্ষার ফলাফল যদি অস্বাভাবিক থাকে তাহলে রেফার করুন। পরামর্শগুলো হলো: পাতে আলগা লবণ না খাওয়া, পা ঝুলিয়ে না বসা, পায়ের নিচে বালিশ দিয়ে পা উচু করে রাখা, আমিষ জাতীয় খাবার খাওয়া। |
| Card given to the model (ML Kit) | Pregnant should say to sleep. The lower part of the foot should be asked to raise some of the top. To be able to identify the high flat bone in the ankle of the foot. With the thumb of two-hand strains on the skin of the flat bone, it should be kept equally 60 seconds or 1 minutes. Then leave a few seconds (5 to 10 seconds) to leave. See what the place is dedicated. There may be Idima that can be caught in when you can be caught. The test related to EDMA is the measurement of urine albumin and blood pressure. Just advise if these two test results are normal. If these two test results are unusual then refer to. The suggestions are: Do not eat loose salt, sitting without hanging, keeping the feet with pillow under the feet, eat the food of food, eat food. |
| Model's English answer | Pregnant women should sleep, and the lower part of the foot should be raised. The test to identify the high flat bone in the ankle is done with the thumb of two hands on the skin of the flat bone, keeping it for 60 seconds or 1 minute, and leaving a few seconds. The test to catch Idima is done by checking the place where the Idima is located. If the results are normal, then the test results are normal. If the results are unusual, then the test results are unusual. The suggestions are: Do not eat loose salt, sit without hanging, keep the feet with a pillow under the feet, and eat food. |
| Shown to the CHW (ML Kit back to Bangla) | গর্ভবতী মহিলাদের ঘুমানো উচিত, এবং পায়ের নীচের অংশটি উত্থাপিত করা উচিত। গোড়ালিটিতে উচ্চ সমতল হাড়টি সনাক্ত করার জন্য টেস্টটি ফ্ল্যাট হাড়ের ত্বকের উপর দুটি হাতের অঙ্গের সাথে সম্পন্ন করা হয়, এটি 60 সেকেন্ড বা 1 মিনিটের জন্য এবং কয়েক সেকেন্ড ছাড়তে থাকে। আইডিমা ধরার পরীক্ষাটি ইডিমা অবস্থিত যেখানে জায়গাটি চেক করে সম্পন্ন হয়। ফলাফল স্বাভাবিক হলে, পরীক্ষার ফলাফল স্বাভাবিক। ফলাফল অস্বাভাবিক হলে, পরীক্ষার ফলাফল অস্বাভাবিক। পরামর্শগুলি হল: আলগা লবণ খাবেন না, ঝুলন্ত ছাড়া বসুন, পায়ে পায়ে পাউলের সাথে পা রাখুন এবং খাবার খাও। |

**Judged:** Keeps lying down, finding the ankle bone and pressing for 60 seconds; garbles the leg instruction and the result reading, and is cut off.

### uc2_q077: INCORRECT

- **Card:** `a6032a14:3` প্রসব পরবর্তী জটিলতা: খিঁচুনি বা ফিট
- **Groundedness:** 0.43, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | SK হিসেবে প্রসব পরবর্তী জটিলতা: খিঁচুনি বা ফিট management কীভাবে করব? |
| Question given to the model (ML Kit) | Next complexity of delivery as SK: How do we do seizures or fits management? |
| Card, Bangla | প্রসবের পর খিঁচুনি বা ফিট একটি বিপদজনক লক্ষণ। যদি প্রসবের পর মায়ের খিঁচুনি বা ফিট হয়, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | A dangerous sign of convulsions or fits after delivery. If the mother's seizure or fit after delivery, it is a complication. In this case, refer to the health center quickly. |
| Model's English answer | It is a complication, so the health worker should address it directly. |
| Shown to the CHW (ML Kit back to Bangla) | এটি একটি জটিলতা, তাই স্বাস্থ্য কর্মী সরাসরি এটি ঠিকানা করা উচিত। |

**Judged:** Says the health worker should address the convulsion directly, replacing the card's instruction to refer urgently to a health centre. Hallucinated phrase: “স্বাস্থ্য কর্মী সরাসরি এটি ঠিকানা করা উচিত”.

### uc2_q083: INCORRECT

- **Card:** `8528ce04:7` যক্ষ্মার ওষুধের পার্শ্বপ্রতিক্রিয়া
- **Groundedness:** 0.47, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | যক্ষ্মার ওষুধের পার্শ্বপ্রতিক্রিয়া থাকলে referral করা উচিত কি? |
| Question given to the model (ML Kit) | Should tuberculosis medicine side effects should be referral? |
| Card, Bangla | যক্ষ্মার ওষুধ খেলে রোগীর প্রস্রাবের রং কমলা বা লাল হলে ভয়ের কোন কারণ নেই, ওষুধ শেষ হলে প্রস্রাব আবার আগের মতো হয়ে যাবে। এছাড়াও কিছু কিছু মারাত্মক সমস্যা হতে পারে যা হলে সরাসরি হাসপাতালে যেতে হবে। কোন কোন গুরুতর পার্শ্ব-প্রতিক্রিয়া হলে সঙ্গে সঙ্গে হাসপাতালে যেতে হবে: চোখে ঝাপসা দেখলে, কানে ভোঁভোঁ করলে, চোখ হলুদ হয়ে গেলে, খেতে না পারলে, বমি হলে (জন্ডিস), রোগী অস্বাভাবিক আচরণ করলে, পেটে তীব্র ব্যথা হলে, প্রস্রাব বন্ধ হয়ে গেলে, চামড়ার নিচে লাল লাল চাকা হলে। |
| Card given to the model (ML Kit) | There is no reason for a scary or redness in the patient's urine or red in playing tuberculosis medicine, when the medicine is finished, the urine will again become as before. There may also be some serious problems, which will go directly to the hospital. Some serious side-reactions should go to the hospital immediately after seeing the hospital, if you look at the eyes, if the eyes are yellow, if you can not eat, if you have a vomiting (jaundice), if the patient behaves abnormally, if the stomach is closed, urine is closed, If the red-red wheel of the skin. |
| Model's English answer | There is no reason to refer for a serious condition, and the symptoms mentioned are not related to tuberculosis medicine. |
| Shown to the CHW (ML Kit back to Bangla) | একটি গুরুতর অবস্থা পড়ার কোন কারণ নেই, এবং উল্লিখিত উপসর্গগুলি ত্বক রোগের সাথে সম্পর্কিত নয়। |

**Judged:** Says there is no reason for concern and mentions skin disease; omits the orange urine reassurance and every serious side effect that needs the hospital. Hallucinated phrase: “উল্লিখিত উপসর্গগুলি ত্বক রোগের সাথে সম্পর্কিত নয়”.

### uc2_q085: PARTIAL

- **Card:** `cb824f82:0` যোনীপথে ফোঁটা ফোঁটা রক্তক্ষরণ
- **Groundedness:** 0.68, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | Patient কে যোনীপথে ফোঁটা ফোঁটা রক্তক্ষরণ নিয়ে কী advice দিব? |
| Question given to the model (ML Kit) | What advice will be advice on the droplet bloating bloating in the Patient? |
| Card, Bangla | যদি গর্ভবতী মায়ের যোনীপথে ফোঁটা ফোঁটা রক্তক্ষরণ হয়, তাহলে তাকে বিছানায় শুয়ে সম্পূর্ণরূপে বিশ্রাম নিতে বলুন। তাকে ভারী কাজ করতে নিষেধ করুন। পরিষ্কার স্যানিটারী ন্যাপকিন পরে থাকতে বলুন। ঘন ঘন তরল খাবার যেমন স্যালাইন, পানি, শরবত, ইত্যাদি খেতে বলুন। যদি অল্প সময়ের মধ্যে রক্তক্ষরণ না কমে বা বেড়ে যায়, তাহলে তাকে দ্রুত হাসপাতালে রেফার করুন। |
| Card given to the model (ML Kit) | If pregnant mother blossom droplets in the vagina are bleeding, then tell him to rest in bed completely. Forbid him to do heavy work. Say clear sanitary napkin to stay. Tell frequent liquid foods such as saline, water, sarata, etc. If bleeding or increases in a short time, then refer him to the hospital quickly. |
| Model's English answer | The advice will be: "If the droplet bloating is in the vagina, rest and avoid heavy work. Use a sanitary napkin and drink frequent liquid foods like saline, water, and sarata." |
| Shown to the CHW (ML Kit back to Bangla) | পরামর্শটি হবে: "যদি ড্রপল্ট ফুসকুড়িটি কোষে থাকে তবে বিশ্রাম এবং ভারী কাজ এড়াতে থাকে। একটি স্যানিটারি ন্যাপকিন ব্যবহার করুন এবং লবণাক্ত, পানি এবং সারতনের মতো ঘন ঘন তরল খাবার পান করুন।" |

**Judged:** Rest, no heavy work, a sanitary pad and frequent fluids are there; drops referral if bleeding does not stop and garbles the opening.

### uc2_q087: PARTIAL

- **Card:** `b5f5fa15:0` শিশুর বৃদ্ধি ও বিকাশ কী?
- **Groundedness:** 0.41, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | শিশুর বৃদ্ধি ও বিকাশ কী? নিয়ে কী জানতে হবে? SK হিসেবে করণীয় কী? |
| Question given to the model (ML Kit) | What is the growth and development of the child? What do you need to know? What is SK? |
| Card, Bangla | শিশুর বৃদ্ধি হলো শরীরের বিভিন্ন অঙ্গের আকারে বড় ও লম্বা হওয়া এবং ওজন বাড়া। শিশুর বিকাশ হলো শিশুর বিভিন্ন অঙ্গের ব্যবহার, কথা বলার, অনুভূতি ও ভাবের আদান-প্রদানের দক্ষতা, যা শিশু বুদ্ধিতে বাড়া বা বিকাশ। |
| Card given to the model (ML Kit) | Baby growth is big and tall in the form of different parts of the body and grow weight. Baby's development is the use of children's various organs, talking, feeling and thinking, which is the development of child intellectualism. |
| Model's English answer | The growth and development of the child are described as "big and tall in the form of different parts of the body and grow weight." This indicates that the child's physical and mental development are being discussed. The information provided does not mention "SK," so it cannot be determined from the given text. |
| Shown to the CHW (ML Kit back to Bangla) | সন্তানের বৃদ্ধি ও বিকাশকে "শরীরের বিভিন্ন অংশের আকারে বড় এবং লম্বা এবং ওজন বাড়ছে।" এটি ইঙ্গিত দেয় যে সন্তানের শারীরিক ও মানসিক বিকাশের আলোচনা করা হচ্ছে। প্রদত্ত তথ্যটি "SK," উল্লেখ করে না, তাই এটি প্রদত্ত পাঠ্য থেকে নির্ধারণ করা যায় না। |

**Judged:** Defines growth correctly; drops the definition of development and adds that the text does not mention SK.

### uc2_q089: PARTIAL

- **Card:** `e660517c:8` ডায়রিয়া প্রতিরোধে করণীয়: নিরাপদ পানি ও খাবার
- **Groundedness:** 1.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়া প্রতিরোধে করণীয়: নিরাপদ পানি ও খাবার — নির্দেশনা কী? |
| Question given to the model (ML Kit) | Diarrhea to prevent: What is safe water and food - instruction? |
| Card, Bangla | রান্না ও খাওয়াসহ গৃহস্থালি সকল কাজে সব সময় নিরাপদ পানি ব্যবহার করতে হবে। সব সময় টাটকা ও গরম খাবার খেতে হবে। খাবার সবসময় ঢেকে রাখতে হবে যাতে মাছি, তেলাপোকা খাবার নষ্ট করতে না পারে। কাঁচা শাকসবজি ও ফলমূল কাটা ও খাওয়ার আগে নিরাপদ পানি দিয়ে ভালোভাবে ধুয়ে নিতে হবে। মাংস, দুধ ও ডিম ভালো করে সিদ্ধ করে খেতে হবে, অর্ধসিদ্ধ খাওয়া যাবে না। |
| Card given to the model (ML Kit) | All chars including cooking and feeding should be used safe water all the time. All the time will eat fresh and hot food. The food must always be covered so that the flies, cockroach can not waste food. Before raw vegetables and fruits and fruits, we need to wash well with safe water. Meat, milk and eggs must be cooked well, can not be eaten half. |
| Model's English answer | All the time, safe water and food are used. |
| Shown to the CHW (ML Kit back to Bangla) | সব সময়, নিরাপদ জল এবং খাদ্য ব্যবহার করা হয়। |

**Judged:** Keeps always using safe water and food; drops fresh hot food, covering food, washing produce and cooking meat, milk and eggs.

### uc2_q090: INCORRECT

- **Card:** `f9cdf65f:8` নবজাতকের জন্মগত ত্রুটি ও খাওয়ানোর তথ্য
- **Groundedness:** 0.44, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | আজ নবজাতকের জন্মগত ত্রুটি ও খাওয়ানোর তথ্য দেখেছি। পরবর্তী পদক্ষেপ কী? |
| Question given to the model (ML Kit) | Today saw the information of the newborn's birth and feeding information. What is the next step? |
| Card, Bangla | নবজাতকের কোন দৃশ্যমান জন্মগত ত্রুটি (তালু কাটা, মুগুর পা ইত্যাদি) স্বাস্থ্যকর্মী নিজে পর্যবেক্ষণ করে অথবা অন্য কোন ত্রুটি থাকলে চিকিৎসকের ব্যবস্থাপত্র অনুযায়ী স্পষ্টভাবে লিখতে হবে। প্রসূতি মা/যত্নকারির সাথে আলাপ করে নবজাতককে জন্মের পর প্রথম 1 ঘন্টায় যে খাবার (মায়ের বুকের দুধ, পানি, চিনির পানি, গরুর দুধ ইত্যাদি) খাওয়ানো হয়েছে তা সংক্ষেপে লিখতে হবে। প্রসূতি মা/যত্নকারির সাথে আলাপ করে নবজাতককে গত 24 ঘন্টায় যে খাবার (মায়ের বুকের দুধ, পানি, চিনির পানি, গরুর দুধ ইত্যাদি) খাওয়ানো হয়েছে তা সংক্ষেপে লিখতে হবে। |
| Card given to the model (ML Kit) | The newborn has a visible birth control error (palate, mugur legs, etc.) of the health workers or if there is any other error, it will clearly write according to the doctor's prescription. After being born with maternal mother / career, the food (mother's breast milk, water, water, water, water, cow's water, cow's milk etc.) have to be written in the first 1 hour after birth. Talking to Maternity Mother / Career, the newborn that has been fed to the last 24 hours (mother's breast milk, water, sugar water, cow's water, cow milk etc. |
| Model's English answer | The information about the newborn's birth and feeding has already been provided, so the next step is to follow up with the health worker using the information available. |
| Shown to the CHW (ML Kit back to Bangla) | নবজাতকের জন্ম ও খাওয়ানোর বিষয়ে তথ্য ইতিমধ্যে সরবরাহ করা হয়েছে, তাই পরবর্তী পদক্ষেপটি উপলব্ধ তথ্য ব্যবহার করে স্বাস্থ্য কর্মীকে অনুসরণ করা। |

**Judged:** Says the information is already provided and to follow it; nothing about recording birth defects or the first-hour feeds.

### uc2_q091: PARTIAL

- **Card:** `dcde24a9:8` মশার কামড় থেকে আত্মরক্ষার উপায়
- **Groundedness:** 0.80, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মশার কামড় থেকে আত্মরক্ষার উপায় — ki guidance dibo? |
| Question given to the model (ML Kit) | Self-defense from mosquito bite - Ki Guidance Dibo? |
| Card, Bangla | ম্যালেরিয়া প্রতিরোধের উল্লেখযোগ্য ব্যবস্থার মধ্যে মশার কামড় থেকে আত্মরক্ষা করা একটি গুরুত্বপূর্ণ উপায়। মশার কামড় থেকে আত্মরক্ষার উপায়গুলি হচ্ছে: কীটনাশকযুক্ত মশারি (ITN) প্রতিদিন সন্ধ্যা হওয়ার সঙ্গে সঙ্গে টানানো ও ব্যবহার করা। জুম চাষ করতে বা বনে বাঁশ বা কাঠ কাটতে বাড়ির বাইরে অবস্থান বা রাত্রিযাপন করতে হলে ঘুমানোর সময় কীটনাশকযুক্ত মশারি (ITN) ব্যবহার করা। মশা তাড়াবার ধোঁয়া, স্প্রে ইত্যাদি ব্যবহার করা। যতটুক সম্ভব শরীর ঢেকে রাখা (ফুল হাতা শার্ট পরিধান ইত্যাদি)। সম্ভব হলে শরীরে অনাবৃত অংশে মশা বিতারক ক্রীম বা লোশন এবং শোবার ঘরে দরজা জানালায় মশা প্রতিবন্ধক জাল (Mosquito Proof Net) ব্যবহার করা। |
| Card given to the model (ML Kit) | In a significant way of malaria resistance, an important way of self-defense from mosquitoes bite. The ways of self-defense from mosquito bite are: Pirate-made mosquito (ITN) tie and use it as well as evening. To make zoom cultivation or in the forest bamboo or wood cutting outside the house, use pesticide mosquito (ITN) while sleeping. Use the smoke, spray etc. of mosquitoes. As much as possible to cover the body (flower sleeve shirts etc.). If possible, in the upper part of the mosquito distribution cream or lotion and lotion in the bedroom, the mosquito proof Net) used in mosquito mesh (Mosquito Proof Net). |
| Model's English answer | The ways of self-defense from mosquito bite are: using ITN mosquito ties, pesticide, and mosquito-proof nets. |
| Shown to the CHW (ML Kit back to Bangla) | মশার কামড় থেকে আত্ম-প্রতিরক্ষা উপায় হল: আইটিএন মশার সম্পর্ক, কীটনাশক, এবং মশার-প্রমাণ নেট ব্যবহার করে। |

**Judged:** Mentions insecticide-treated nets in garbled form; drops hanging them every evening and the outdoor-sleeping advice.

---

## 2. The 18 rewrites the groundedness check rejected

The CHW saw the card verbatim, so these rows count as CORRECT for the CHW. The model's answer is shown so its failures can be read alongside section 1.

### uc2_q001: rewrite rejected, card shown

- **Card:** `6818eb87:0` গর্ভকালীন চেকআপের গুরুত্ব
- **Groundedness:** 0.19, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | গর্ভকালীন চেকআপের গুরুত্ব কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is the importance of pregnancy checkup? What would I say to the patient as a sk? |
| Card, Bangla | গর্ভকালীন সময়ে চেকআপের গুরুত্ব নিয়ে আলোচনা করুন এবং একজন গর্ভবতী মা কমপক্ষে কয়বার ও কোথায় সেবা নিবেন তা জানান। |
| Card given to the model (ML Kit) | Discuss the importance of checkup during pregnancy and tell a pregnant mother at least many times and where to take care of. |
| Model's English answer | The importance of pregnancy checkups includes ensuring the health of the mother and baby, identifying possible issues early, and providing proper care. As a health worker, I would say: "I understand that checkups are important for both your health and the health of your baby. Let me know if you need anything!" |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q003: rewrite rejected, card shown

- **Card:** `0fa2eaf5:0` পারিবারিক খাবার কি?
- **Groundedness:** 0.18, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে পারিবারিক খাবার কি? দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | What is the family meal of the visits? Saw. What is the key? |
| Card, Bangla | শিশুর ছয় মাস পূর্ণ হবার পর মায়ের দুধের পাশাপাশি পরিবারের সবার জন্য রান্না করা খাবার থেকে যে খাবার নিয়ে শিশুকে খাওয়ানো হয় তাকে পারিবারিক খাবার বলে। |
| Card given to the model (ML Kit) | After six months of the child, the mother's milk as well as the food that is fed with the food that is fed to the child from cooking food for all children, and the family is fed. |
| Model's English answer | The family meal is not mentioned, so we cannot determine the key. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q009: rewrite rejected, card shown

- **Card:** `47f4d15a:0` ডেঙ্গু র‍্যাপিড ডায়াগনোস্টিক টেস্ট (RDT) কী?
- **Groundedness:** -, floor -

| Stage | Text |
|---|---|
| Question, Bangla | মাকে ডেঙ্গু র‍্যাপিড ডায়াগনোস্টিক টেস্ট (RDT) কী? সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | What is the dengue Rapid Diagnostic Test (RDT)? What will give me counseling? |
| Card, Bangla | র‍্যাপিড ডায়াগনোস্টিক টেস্ট বা আরডিটি হলো দ্রুত সনাক্তকরণ পরীক্ষা। এই পরীক্ষার মাধ্যমে ডেঙ্গু এনএস-১ এন্টিজেন, আইজিজি এবং আইজিএম সনাক্ত করা যায়। |
| Card given to the model (ML Kit) | Rapid Diagnostic Test or RDT is a quick detection test. Through this test, Dengue NS-1 Antigen, AGG and IGM can be detected. |
| Model's English answer | The Dengue Rapid Diagnostic Test (RDT) is a quick test to detect the Dengue NS-1 Antigen, AGG, and IGM. If you have results, please give me counseling. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q016: rewrite rejected, card shown

- **Card:** `e660517c:1` ডায়রিয়া কি?
- **Groundedness:** 0.11, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ডায়রিয়া কি? কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is diarrhea? What? What would I say to the patient as a sk? |
| Card, Bangla | চব্বিশ ঘন্টায় তিন বারের বেশি নরম থকথকে পাতলা (মলে পানির পরিমাণ বেশি) বা পানির মতো ঘন ঘন পায়খানা হওয়াকে (তিন বারের বেশি) ডায়রিয়া বলে। |
| Card given to the model (ML Kit) | Twenty-four hours, more than three times soft thukphat (more than the amount of water in the mall) or water, as well as in diarrhea (more than three times) diarrhea. |
| Model's English answer | Diarrhea is a common health condition, and as a sk, I would say: "I am a health worker and I am here to help you." |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q018: rewrite rejected, card shown

- **Card:** `dcde24a9:1` ম্যালেরিয়া কীভাবে ছড়ায় এবং মশার বৈশিষ্ট্য
- **Groundedness:** 0.20, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে ম্যালেরিয়া কীভাবে ছড়ায় এবং মশার বৈশিষ্ট্য দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | Visit Malaria spreads and saw the properties of mosquito. What is the key? |
| Card, Bangla | যখন একটি ম্যালেরিয়া রোগীকে মশা কামড় দেয় তখন রোগীর রক্তের সাথে সেই জীবাণু মশার পাকস্থলীতে বা পেটে চলে যায়। পরবর্তী সময়ে সেই মশা যখন সুস্থ মানুষের শরীরে কামড় দেয় তখন মশার লালাগ্রন্থী থেকে সেই জীবাণু মশার লালার মাধ্যমে সুস্থ মানুষের শরীরে প্রবেশ করে। এই সুস্থ মানুষ পরবর্তী সময়ে ম্যালেরিয়া রোগী হয়ে যায়। ম্যালেরিয়ার মশা সাধারণত সূর্যাস্ত থেকে সূর্যোদয় পর্যন্ত মানুষকে কামড়িয়ে থাকে। অ্যানোফিলিস জাতীয় স্ত্রী মশা সাধারণত স্থির বা ধীরগতিসম্পন্ন পরিষ্কার পানিতে ডিম পাড়ে ও বংশ বৃদ্ধি করে। কিউলেক্স মশা ময়লা পানিতে এবং এডিস মশা জমে থাকা পানিতে বংশ বৃদ্ধি করে। |
| Card given to the model (ML Kit) | When a malaria bites the patient's mosquitoes, the patient's blood goes into the stomach or stomach in the womb. Later, when the mosquito bites the healthy man's body, the mosquito germs enter the body of a healthy man through mosquito lust. These healthy people later became malaria patients. Masha of malaria is usually bitten by people from sunset to sunrise. Anophilis National Wife Mosquito usually increases eggs and dynasties in the stable or slow clean water. Kulsex mosquito is in the dirt water and increases the dynasty in the water of Edis Mosquito. |
| Model's English answer | The key is to identify the information provided and summarize it concisely. The information given discusses the transmission of malaria through mosquitoes, the role of mosquito bites in spreading the disease, and the characteristics of mosquitoes involved in disease transmission. The key is to summarize this information effectively. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q028: rewrite rejected, card shown

- **Card:** `f9cdf65f:2` পূর্বের গর্ভের জটিলতার তথ্য লিপিবদ্ধকরণ
- **Groundedness:** 0.15, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | ভিজিটে পূর্বের গর্ভের জটিলতার তথ্য লিপিবদ্ধকরণ দেখেছি। করণীয় কী? |
| Question given to the model (ML Kit) | We saw the entry of the complications of the preliminary pregnancy. What is the key? |
| Card, Bangla | গর্ভবতী মহিলার সাথে আলাপ করে কার্ডে উল্লেখিত সমস্যাগুলির (সিজারিয়ান অপারেশন, গর্ভপাত, প্রসব পূর্ব বা পরবর্তী রক্তক্ষরণ, প্রিএকলাম্পশিয়া, একলাম্পশিয়া) কোনটির ইতিহাস থাকলে তার উপর টিক (√) চিহ্ন দিতে হবে। |
| Card given to the model (ML Kit) | Talking to the pregnant woman (Caesarian operation, abortion, prejudice east or subsequent bleeding, pre-millions, anilamasia) should be ticked (√). |
| Model's English answer | The key is to address the health worker directly, using only the information provided. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q029: rewrite rejected, card shown

- **Card:** `dcde24a9:2` ম্যালেরিয়া প্রবণ জেলাসমূহ
- **Groundedness:** 0.17, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে ম্যালেরিয়া প্রবণ জেলাসমূহ সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | What counseling is about Malaria prone districts? |
| Card, Bangla | বাংলাদেশের কক্সবাজার, চট্টগ্রাম, পাবর্ত্য চট্টগ্রাম (বান্দরবান, রাঙ্গামাটি, খাগড়াছড়ি), সিলেট, হবিগঞ্জ, মৌলভীবাজার, সুনামগঞ্জ, কুড়িগ্রাম, নেত্রকোণা, শেরপুর ও ময়মনসিংহ সহ এই 13টি জেলায় ম্যালেরিয়া বেশী হয়। |
| Card given to the model (ML Kit) | Cox's Bazar of Bangladesh, Chittagong, Dold, Chittagong (Bandarban, Rangamati, Khagrachari), Sylhet, Habiganj, Moulvibazar, Sunamganj, Kurigram, Netrokona, Sherpur and Mymensingh In this 13 districts, malaria are more than that. |
| Model's English answer | The counseling about Malaria-prone districts would include information on the prevalence of malaria in these districts, the impact on communities, and the health measures to control and manage the disease. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q049: rewrite rejected, card shown

- **Card:** `952fbce0:9` ডায়রিয়া ছড়ানোর পদ্ধতি: 5F
- **Groundedness:** 0.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে ডায়রিয়া ছড়ানোর পদ্ধতি: 5F সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | Mother to spread the diarrhea: What will give me counseling about 5F? |
| Card, Bangla | ডায়রিয়া রোগ 5F এর মাধ্যমে ছড়ায়: Food (খাদ্য), Finger (হাত), Fly (মাছি), Faeces (মল), Fomite (রোগীর ব্যবহৃত বাসনপত্র বা সচরাচর ব্যবহৃত অন্যান্য জিনিসপত্র)। |
| Card given to the model (ML Kit) | Diarrhea disorders spread through 5F: Food (food), Finger (hand), faeces, faeces (stools), fomite (used in patients or other accessories used in the use of the utensils). |
| Model's English answer | The information provided does not include a specific list of 5F (likely a typo or misinterpretation). If it were a list, the answer would be based on that information. If it's a standard 5F (e.g., 5F in the context of a health care setting), the information provided is not sufficient to answer the question. If it's a list of 5F, the answer would be based on that list. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it's a 5F in the context of a health care setting, the information provided is not sufficient to answer the question. If it |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q056: rewrite rejected, card shown

- **Card:** `6818eb87:5` অস্বাভাবিক তাপমাত্রা ও করণীয়
- **Groundedness:** -, floor -

| Stage | Text |
|---|---|
| Question, Bangla | অস্বাভাবিক তাপমাত্রা ও করণীয় কী? SK হিসেবে আমি রোগীকে কী বলব? |
| Question given to the model (ML Kit) | What is an unusual temperature and what? What would I say to the patient as a sk? |
| Card, Bangla | অস্বাভাবিক তাপমাত্রা হলো ৯৯° ফারেনহাইট এর বেশি (জ্বর) বা ৯৭° ফারেনহাইট এর কম (হাইপোথারমিয়া)। জ্বর হলে চিকিৎসকের পরামর্শ অনুযায়ী ট্যাবলেট নাপা বা প্যারাসিটামল খেতে বলুন। স্বাভাবিক পানিতে বা কুসুম গরম পানিতে কাপড় ভিজিয়ে সমস্ত শরীর মুছে দিন। বেশি বেশি তরল জাতীয় খাবার খেতে হবে। জ্বর না কমলে চিকিৎসকের পরামর্শ নিন। |
| Card given to the model (ML Kit) | Unusual temperature is 99 ° Fahrenheit's low (fever) or less than 97 ° Fahrenheit (hypothermia). If you have a fever, tell tablet nap or paracetamal according to the doctor's advice. Delete all the body to wet clothes in normal water or yolk water. Must eat more liquid foods. If you do not have fever, then consult the doctor. |
| Model's English answer | Unusual temperature is 99°F (low) or less than 97°F (high). If you have a fever, tell the patient to take a tablet or nap. If you don't have a fever, then consult the doctor. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q059: rewrite rejected, card shown

- **Card:** `d12af13b:5` কম জন্ম ওজনের শিশু কারা?
- **Groundedness:** 0.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | মাকে কম জন্ম ওজনের শিশু কারা? সম্পর্কে কী কাউন্সেলিং দিব? |
| Question given to the model (ML Kit) | Who is a child weight loss? What will give me counseling? |
| Card, Bangla | যে সকল শিশুদের ওজন দুই দশমিক পাঁচ কেজির নিচে তাদেরকে কম জন্ম ওজনের শিশু বলা হয়। |
| Card given to the model (ML Kit) | Children who are below two decimal five kgs are called less born children. |
| Model's English answer | The information provided does not specify who is a child weight loss. Therefore, I cannot answer "Who is a child weight loss?" or "What will give me counseling?" based on the given information. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q065: rewrite rejected, card shown

- **Card:** `7a1f98e9:5` দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা
- **Groundedness:** 0.15, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা নিয়ে প্রশ্ন এসেছে। দ্রুত ও শ্বাস কী বলব? |
| Question given to the model (ML Kit) | Questions have been questioned by the rate of breathing and breathing rapidly. What to say fast and breathing? |
| Card, Bangla | শ্বাসের গতি স্বাভাবিকের চেয়ে বেড়ে গেলে তাকে দ্রুত শ্বাস বলে। একবার শ্বাস নেয়া ও ছাড়াকে শ্বাস-প্রশ্বাস বলা হয়, এভাবে এক মিনিটে যতবার শ্বাস নেয়া ও ছাড়া হয় সেই সংখ্যাকে শ্বাস-প্রশ্বাসের হার বলা হয়। শ্বাস গণনার সময় শিশুকে শান্ত অবস্থায় রাখতে হবে, ঘরে যথেষ্ট আলোর ব্যবস্থা থাকতে হবে। সেকেন্ডের কাঁটাযুক্ত ঘড়ি বা টাইমার শিশুর বুকের উপর রাখতে হবে। আগে ঘড়ি দেখতে হবে পরে শ্বাস গণনা করতে হবে। বুক বা পেটের যে কোন অংশের উঠানামা লক্ষ্য করতে হবে। |
| Card given to the model (ML Kit) | If the speed of breathing is increased than normal, he says quick breath. Once breathing and breathing, breathing, it is said that in one minute breathing and without breathing, the number is called breathing rate. During breathing, the child must keep quiet in the house, there must be enough light arrangements in the house. The second-spindle clock or timer should be kept on the baby's chest. Breaks should be counted after the watch will see before. To be aimed at any part of the chest or stomach. |
| Model's English answer | The information provided does not mention anything about the health worker's response to the rate of breathing and the speed of breathing. Therefore, the answer cannot be determined based on the given information. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q067: rewrite rejected, card shown

- **Card:** `a6032a14:2` প্রসব পরবর্তী জটিলতা: যোনিপথে দুর্গন্ধযুক্ত স্রাব
- **Groundedness:** 0.10, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী জটিলতা: যোনিপথে দুর্গন্ধযুক্ত স্রাব বুঝিয়ে দিতে হবে — প্রসব সম্পর্কে কী পরামর্শ? |
| Question given to the model (ML Kit) | Next complications of delivery: What would be the discharge of the bad stomached discharge - what advice about delivery? |
| Card, Bangla | প্রসবের পর যোনিপথে দুর্গন্ধযুক্ত স্রাব একটি বিপদজনক লক্ষণ। যদি প্রসবের পর যোনিপথ দিয়ে দুর্গন্ধযুক্ত স্রাব হয়, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | After delivery, a dangerous signs of stinking discharge of vaginal stomach. If there is a bad discharge with vaginals after delivery, it is a complexity. In this case, refer to the health center quickly. |
| Model's English answer | The information provided does not include any details about the next complications or advice regarding the discharge. Therefore, I cannot provide a specific answer based on the information given. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q080: rewrite rejected, card shown

- **Card:** `e660517c:7` ডায়রিয়ার রোগীকে হাসপাতালে রেফার করার মানদণ্ড
- **Groundedness:** 0.09, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | Patient কে ডায়রিয়ার রোগীকে হাসপাতালে রেফার করার মানদণ্ড নিয়ে কী advice দিব? |
| Question given to the model (ML Kit) | What advice will be advised to referring to the diarrhea of the diarrhea to Patient? |
| Card, Bangla | পাতলা পায়খানার সাথে বমি হলে, মলে রক্ত থাকলে, প্রস্রাব বন্ধ হলে, জ্বর ও খিঁচুনি হলে, তিনদিন বা বাহাত্তর ঘণ্টায় পায়খানা বন্ধ না হলে রোগীকে অবশ্যই হাসপাতালে পাঠাতে হবে। |
| Card given to the model (ML Kit) | If you vomit with thin robbery, if there is blood in the mall, if urine is closed, if there is a fever and convulsion, the patient must send to the hospital for three days or seventy hours. |
| Model's English answer | There is no direct advice to be given to a patient based on the information provided. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q081: rewrite rejected, card shown

- **Card:** `f9cdf65f:7` নবজাতকের জন্ম ও শারীরিক অবস্থার তথ্য
- **Groundedness:** 0.18, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | নবজাতকের জন্ম ও শারীরিক অবস্থার তথ্য — key counselling points কী? |
| Question given to the model (ML Kit) | Newborn's birth and physical condition information - what is key countelling points? |
| Card, Bangla | নবজাতকের জন্ম তারিখ লিখতে হবে। চিকিৎসক/স্বাস্থ্যকর্মীর ব্যবস্থাপত্র দেখে অথবা প্রসূতি বা পরিবারের অন্য সদস্যদের সাথে আলাপ করে নবজাতকের জন্ম ওজন লিখতে হবে। নবজাতকের শ্বাসের হার ফাঁকা ঘরে লিখতে হবে। নবজাতকের শ্বাস অস্বাভাবিক থাকলে অর্থাৎ শ্বাসকষ্ট অথবা শ্বাসের অস্বাভাবিক শব্দ যেমন ঘড়ঘড় বা বাঁশির মত শাঁইশাঁই আওয়াজ থাকলে এবং শান্ত অবস্থায় শ্বাসের গতি মিনিটে 60 বারের বেশি থাকলে চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের নাভী শুকনো ও স্বাভাবিক থাকলে টিক (√) চিহ্ন দিতে হবে। নাভী অস্বাভাবিক থাকলে অস্বাভাবিক এর উপর (✔) চিহ্ন দিতে হবে এবং চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের চোখ স্বাভাবিক হলে টিক (√) চিহ্ন দিতে হবে। অস্বাভাবিক থাকলে চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের ত্বক স্বাভাবিক হলে স্বাভাবিক এর উপর টিক (√) চিহ্ন দিতে হবে। অস্বাভাবিক থাকলে চিকিৎসকের কাছে রেফার করতে হবে। |
| Card given to the model (ML Kit) | Newborn's birth date should be written. Need to write newborn's birth weight by viewing the prescription of physicians / health workers or talking to other members of the family. Newborn breathing rate will be written in the blank cell. If the breath's breath is unusual, then there will be 60 times in a minute in the shortage of breathing or bamboo, such as a voice of breathing or bamboo, and if there is more than 60 times in calm in calm, then it will be referenced to the doctor. Tick (√) sign should be given if the newborn's uli is dry and normal. If the ultimate is unusual, it must be given (✔) marks on unusual and refer to the doctor. Tick (√) should be given if the newborn's eyes are normal. If unusual, you must refer to the doctor. If the newborn skin is normal, tick (√) should be given on normal. If unusual, you must refer to the doctor. |
| Model's English answer | None of the information provided is key counting points. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q086: rewrite rejected, card shown

- **Card:** `a6032a14:4` প্রসব পরবর্তী জটিলতা: চোখে ঝাপসা দেখা
- **Groundedness:** 0.06, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী জটিলতা: চোখে ঝাপসা দেখা সম্পর্কে বিস্তারিত বলুন — আমি কী পরামর্শ দিব? |
| Question given to the model (ML Kit) | Next complexity of delivery: Tell me details about seeing the eye in the eye - what will I advise? |
| Card, Bangla | প্রসবের পর চোখে ঝাপসা দেখা একটি বিপদজনক লক্ষণ। যদি প্রসবের পর মায়ের চোখে ঝাপসা দেখা যায়, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | A dangerous signs seen in the eye after delivery. If the mother is seen in the eyes of the mother, it is a complication. In this case, refer to the health center quickly. |
| Model's English answer | The information provided is incomplete and unclear. Please provide more details about the situation involving the eye. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q093: rewrite rejected, card shown

- **Card:** `e65337d3:3` ডেঙ্গু প্রতিরোধের উপায়
- **Groundedness:** 0.21, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | bolun ডেঙ্গু প্রতিরোধের উপায় er upor ki koronio? |
| Question given to the model (ML Kit) | Bolun Dengue Resistance means ER UPOR KI KORONIO? |
| Card, Bangla | ডেঙ্গু প্রতিরোধের জন্য মশার প্রজনন স্থান ধ্বংস করতে হবে। মশার কামড় থেকে বাঁচতে মশারি ব্যবহার করুন। দিনের বেলায়ও মশারি ব্যবহার করা উচিত, কারণ ডেঙ্গু মশা দিনের বেলায় কামড়ায়। মশা তাড়ানোর স্প্রে বা লোশন ব্যবহার করুন। বাড়ির আশেপাশে পানি জমতে দেবেন না এবং নিয়মিত পরিষ্কার রাখুন। |
| Card given to the model (ML Kit) | Must have to destroy mosquitoes for dengue resistance. Use mosquitoes to live from mosquito bite. The mosquito should also be used during the day, because dengue mosquitoes bite in the day. Use mosquitoes to repair spray or lotion. Do not let the water around the house and keep clean regularly. |
| Model's English answer | The information provided does not mention anything about "Bolun Dengue Resistance" or "ER UPOR KI KORONIO." Therefore, I cannot answer the question based on the given information. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q094: rewrite rejected, card shown

- **Card:** `cb824f82:1` যোনীপথে বেশী রক্তক্ষরণ ও তলপেটে ব্যথা
- **Groundedness:** 0.00, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | যোনীপথে বেশী রক্তক্ষরণ ও তলপেটে ব্যথা somporke ki jante hobe SK hishebe? |
| Question given to the model (ML Kit) | More bleeding and abdominal pain in vagina, Somporke Ki Jante Hobe Sk Hishebe? |
| Card, Bangla | যদি গর্ভবতী মায়ের যোনীপথে বেশী রক্তক্ষরণ হয় এবং তলপেটে ব্যথা থাকে, তাহলে তাকে দ্রুত হাসপাতালে রেফার করুন। তাকে পরিষ্কার স্যানিটারী ন্যাপকিন পরে থাকতে বলুন এবং ঘন ঘন তরল খাবার যেমন স্যালাইন, পানি, শরবত, ইত্যাদি খেতে বলুন। |
| Card given to the model (ML Kit) | If pregnant mother is more bleeding in vagina and there is a painful pain, then refer him to the hospital quickly. Ask him to stay after the clean sanitary napkin and frequently tell fluid foods such as saline, water, sarata, etc.. |
| Model's English answer | Please consult a healthcare professional immediately. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

### uc2_q095: rewrite rejected, card shown

- **Card:** `a6032a14:5` প্রসব পরবর্তী জটিলতা: তীব্র মাথাব্যথা
- **Groundedness:** 0.18, floor 0.25

| Stage | Text |
|---|---|
| Question, Bangla | প্রসব পরবর্তী জটিলতা: তীব্র মাথাব্যথা — pls ki poramorsho? |
| Question given to the model (ML Kit) | Next Complexity: Acute Headaches - Pls Ki Poramorsho? |
| Card, Bangla | প্রসবের পর তীব্র মাথাব্যথা একটি বিপদজনক লক্ষণ। যদি প্রসবের পর মায়ের তীব্র মাথাব্যথা হয়, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে। |
| Card given to the model (ML Kit) | A dangerous signs of intense headaches after delivery. If the mother is a strong headache after delivery, it is a complication. In this case, refer to the health center quickly. |
| Model's English answer | The information provided does not mention any specific details about a health center or a particular case in the context of acute headaches. Therefore, it cannot be determined with certainty. |
| Shown to the CHW | the card verbatim; the answer above was rejected |

---

## 3. The 8 rewrites judged CORRECT in the first run

`uc2_q007`, `uc2_q012`, `uc2_q032`, `uc2_q042`, `uc2_q047`, `uc2_q072`, `uc2_q082`, `uc2_q084`. Not rerun; their texts are in `runs/full-assisted/report.md`.

