package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BracJulyRegressionTest {

    private fun module(
        moduleId: String,
        moduleFamilyId: String,
        titleEn: String,
        cardsJson: String,
    ) = moduleEntityFixture(
        moduleId = moduleId,
        moduleFamilyId = moduleFamilyId,
        titleEn = titleEn,
        cardsJson = cardsJson,
    )

    @Test
    fun `code mixed referral query selects the English clinical card over generic advice`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    moduleId = "m-generic",
                    moduleFamilyId = "fam-generic",
                    titleEn = "Generic counselling",
                    cardsJson = """
                        [{
                          "title_bn":"রোগীকে কী বলব",
                          "body_bn":"রোগী ও পরিবারের সঙ্গে ভাল ব্যবহার করুন এবং প্রয়োজনীয় পরামর্শ দিন।",
                          "title_en":"What should I tell the patient",
                          "body_en":"Counsel the patient and family with general advice."
                        }]
                    """.trimIndent(),
                ),
                module(
                    moduleId = "m-diabetes",
                    moduleFamilyId = "fam-diabetes",
                    titleEn = "Diabetes Identification and Management",
                    cardsJson = """
                        [{
                          "title_bn":"রোগীর অত্যন্ত উচ্চ রক্তের শর্করার জন্য জরুরী রেফারেল",
                          "body_bn":"রক্ত বা মূত্রে শর্করার মাত্রা ১৮ mmol/l বা তার বেশি হলে দ্রুত নিকটস্থ উপজেলা স্বাস্থ্য কমপ্লেক্সে যান।",
                          "title_en":"Urgent Referral for Very High Blood Sugar",
                          "body_en":"Refer urgently to the nearest health complex when blood sugar is very high."
                        }]
                    """.trimIndent(),
                ),
            ),
        )

        val selection = GroundingSelector.select(
            nativeQuery = "রোগীকে Urgent Referral for Very High Blood Sugar সম্পর্কে কী বলব?",
            englishQuery = "What should I say about urgent referral for very high blood sugar?",
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )

        assertTrue(selection.hits.isNotEmpty())
        assertEquals(
            "Urgent Referral for Very High Blood Sugar",
            selection.primary?.titleEn,
        )
    }

    @Test
    fun `sibling rerank prefers the specific danger signs card over overview`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    moduleId = "m-sibling",
                    moduleFamilyId = "fam-sibling",
                    titleEn = "Danger signs module",
                    cardsJson = """
                        [
                          {
                            "title_en":"Danger Signs Overview",
                            "body_en":"This module covers danger signs, referral, counselling, and prevention."
                          },
                          {
                            "title_en":"Danger Signs During Pregnancy",
                            "body_en":"Refer immediately for vaginal bleeding, convulsions, severe abdominal pain, and high fever."
                          }
                        ]
                    """.trimIndent(),
                ),
            ),
        )

        val selection = GroundingSelector.select(
            nativeQuery = "What are the danger signs during pregnancy?",
            englishQuery = "What are the danger signs during pregnancy?",
            nativeLanguage = ModuleKnowledgeIndex.Lang.EN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )

        assertTrue(selection.hits.isNotEmpty())
        assertEquals(
            "Danger Signs During Pregnancy",
            selection.primary?.titleEn,
        )
    }

    @Test
    fun `weak nutrition style hit is rejected as fallback serve`() {
        val weakHit = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam-nutrition",
            positionalId = 0,
            titleEn = "Nutrition counselling",
            bodyEn = "Advise families about healthy food and cooking.",
            titleBn = "পুষ্টি পরামর্শ",
            bodyBn = "স্বাস্থ্যকর খাবার ও রান্না সম্পর্কে পরিবারের সদস্যদের পরামর্শ দিন।",
            score = 17.12f,
        )
        val clinicalTerms = setOf("nutrition", "diet", "পুষ্টি", "খাদ্য")

        assertTrue(
            OffTopicGuard.bestFallbackHit(
                query = "How do I cook chicken biryani?",
                hits = listOf(weakHit),
                clinicalTerms = clinicalTerms,
                minScore = 20f,
            ) == null,
        )
    }

    @Test
    fun `pnc urgent referral query ranks referral card above gaps module`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    moduleId = "m-pnc-referral",
                    moduleFamilyId = "cbf9f968",
                    titleEn = "PNC visit data collection",
                    cardsJson = """
                        [
                          {
                            "title_bn":"পোস্টনেটাল কেয়ার (PNC) ভিজিটের তথ্য সংগ্রহ",
                            "body_bn":"PNC মডিউলে মায়ের স্বাস্থ্য সম্পর্কিত তথ্য সংগ্রহ করা হয়।"
                          },
                          {
                            "title_bn":"PNC ভিজিটে জরুরি রেফারেলের কারণ: রক্তপাত ও ব্যথা",
                            "body_bn":"নিম্নলিখিত লক্ষণগুলো দেখা গেলে জরুরি রেফারেল প্রয়োজন: অতিরিক্ত রক্তপাত। তীব্র পেটে ব্যথা।"
                          }
                        ]
                    """.trimIndent(),
                ),
                module(
                    moduleId = "m-pnc-gaps",
                    moduleFamilyId = "88b407fb",
                    titleEn = "PNC gaps module",
                    cardsJson = """
                        [
                          {
                            "title_bn":"প্রসবোত্তর যত্ন (PNC) মডিউলের তথ্য সংগ্রহ",
                            "body_bn":"PNC মডিউলে মায়ের স্বাস্থ্য সম্পর্কিত তথ্য সংগ্রহ করা হয়।"
                          },
                          {
                            "title_bn":"PNC-তে নন-জরুরি রেফারেলের কারণসমূহ",
                            "body_bn":"নিম্নলিখিত লক্ষণ বা পরিস্থিতি দেখা দিলে নন-জরুরি রেফারেল প্রয়োজন: মাঝারি রক্তস্বল্পতা।"
                          }
                        ]
                    """.trimIndent(),
                ),
            ),
        )

        val selection = GroundingSelector.select(
            nativeQuery = "PNC ভিজিটে জরুরি রেফারেলের কারণ: রক্তপাত ও ব্যথা নিয়ে কী জানতে হবে?",
            englishQuery = "PNC visit urgent referral reasons bleeding and pain",
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )

        assertTrue(selection.hits.isNotEmpty())
        assertEquals("cbf9f968", selection.primary?.moduleFamilyId)
        assertEquals(1, selection.primary?.positionalId)
    }

    @Test
    fun `pnc data collection query prefers pnc module over antenatal sibling`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    moduleId = "m-anc",
                    moduleFamilyId = "cd36e4f1",
                    titleEn = "ANC visit data collection",
                    cardsJson = """
                        [{
                          "title_bn":"এন্টিনেটাল কেয়ার (ANC) ভিজিট: তথ্য সংগ্রহ",
                          "body_bn":"এন্টিনেটাল কেয়ার (ANC) মডিউল গর্ভবতী মায়ের বিভিন্ন তথ্য সংগ্রহ করে।"
                        }]
                    """.trimIndent(),
                ),
                module(
                    moduleId = "m-pnc",
                    moduleFamilyId = "cbf9f968",
                    titleEn = "PNC visit data collection",
                    cardsJson = """
                        [{
                          "title_bn":"পোস্টনেটাল কেয়ার (PNC) ভিজিটের তথ্য সংগ্রহ",
                          "body_bn":"PNC মডিউলে একজন মায়ের স্বাস্থ্য সম্পর্কিত বিভিন্ন তথ্য সংগ্রহ করা হয়।"
                        }]
                    """.trimIndent(),
                ),
            ),
        )

        val selection = GroundingSelector.select(
            nativeQuery = "রোগীকে পোস্টনেটাল কেয়ার (PNC) ভিজিটের তথ্য সংগ্রহ সম্পর্কে কী বলব?",
            englishQuery = "What should I say about postnatal care PNC visit data collection?",
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )

        assertEquals("cbf9f968", selection.primary?.moduleFamilyId)
    }

    @Test
    fun `urgent pnc query does not boost non-urgent referral title via substring`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    moduleId = "m-pnc-urgent",
                    moduleFamilyId = "cbf9f968",
                    titleEn = "PNC urgent referral",
                    cardsJson = """
                        [{
                          "title_bn":"PNC ভিজিটে জরুরি রেফারেলের কারণ: রক্তপাত ও ব্যথা",
                          "body_bn":"নিম্নলিখিত লক্ষণগুলো দেখা গেলে জরুরি রেফারেল প্রয়োজন: অতিরিক্ত রক্তপাত। তীব্র পেটে ব্যথা।"
                        }]
                    """.trimIndent(),
                ),
                module(
                    moduleId = "m-pnc-non-urgent",
                    moduleFamilyId = "88b407fb",
                    titleEn = "PNC non-urgent referral",
                    cardsJson = """
                        [{
                          "title_bn":"PNC-তে নন-জরুরি রেফারেলের কারণসমূহ",
                          "body_bn":"নিম্নলিখিত লক্ষণ বা পরিস্থিতি দেখা দিলে নন-জরুরি রেফারেল প্রয়োজন: মাঝারি রক্তস্বল্পতা।"
                        }]
                    """.trimIndent(),
                ),
            ),
        )

        val selection = GroundingSelector.select(
            nativeQuery = "PNC ভিজিটে জরুরি রেফারেলের কারণ: রক্তপাত ও ব্যথা নিয়ে কী জানতে হবে?",
            englishQuery = "PNC visit urgent referral bleeding pain",
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )

        assertEquals("cbf9f968", selection.primary?.moduleFamilyId)
    }

    @Test
    fun `code mixed condoms query keeps bm25 rank1 over sibling access card`() {
        val accessCondoms = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "cbb372e5",
            positionalId = 4,
            titleEn = "Where to Access Condoms and Oral Pills",
            bodyEn = "Condoms and oral pills are available at community clinics and pharmacies.",
            titleBn = "কনডোম ও খাবার বড়ি কোথায় পাওয়া যায়",
            bodyBn = "কমিউনিটি ক্লিনিক ও ফার্মেসিতে কনডোম ও খাবার বড়ি পাওয়া যায়।",
            score = 362.58f,
        )
        val accessInjections = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "cbb372e5",
            positionalId = 5,
            titleEn = "Where to Access Injections, IUDs, and Implants",
            bodyEn = "Injections and IUDs are available at upazila health complexes and district hospitals.",
            titleBn = "ইনজেকশন, আইইউডি ও ইমপ্লান্ট কোথায় পাওয়া যায়",
            bodyBn = "উপজেলা স্বাস্থ্য কমপ্লেক্স ও জেলা হাসপাতালে ইনজেকশন ও আইইউডি পাওয়া যায়।",
            score = 229.61f,
        )
        val selected = OffTopicGuard.selectLowEndServeHit(
            query = "কনডর্ ও খাবাি বয়ি রকাথাি পাওিা যাি? — what are the key steps?",
            hits = listOf(accessCondoms, accessInjections),
            clinicalTerms = setOf("condom", "oral", "pill", "কনডোম", "পিল"),
        )
        assertEquals(4, selected?.positionalId)
    }
}
