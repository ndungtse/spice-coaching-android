package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.dao.ChatMessageDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwModuleCompletionDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwModulePartialCompletionDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.ConfigThresholdDao
import com.medtroniclabs.microcoaching.data.db.dao.DigitalProficiencyEventDao
import com.medtroniclabs.microcoaching.data.db.dao.LlmTraceDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.MorningCardCacheDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleTriggerBindingDao
import com.medtroniclabs.microcoaching.data.db.dao.TriggerDefinitionDao
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModulePartialCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_14_15
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_15_16
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_16_17
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_17_18

/**
 * SDK-owned Room database. Completely separate from SPICE's NCDMergerDatabase.
 *
 * Database name: `microcoaching.db`
 * Version: 18 (v18 bundles two module-sync additions: (a) thumbnail columns on
 *           module_cache — has_thumbnail, thumbnail_url,
 *           thumbnail_expires_at_epoch_sec — for cached presigned thumbnail URLs;
 *           (b) rich source-document refs — module_cache.source_documents_json
 *           + chat_messages.source_documents_json, carrying per-document
 *           title/original_filename for chat citation chips;
 *           v17 combines two changes: (a) behavioural_gap_cache.detection_rule
 *           — JSON envelope for SDK-side gap-rule evaluation; (b) source-document
 *           attribution — module_cache.source_document_ids_json,
 *           chat_messages.source_document_ids_json,
 *           chat_messages.grounding_module_family_id — so assistant replies
 *           carry per-message citation pointers into the matched module's
 *           training PDFs;
 *           v16: chw_module_partial_completion table for cross-device CHW
 *           progress recovery via server's authoritative incomplete_quiz_ids;
 *           v15: chat_messages.chw_id + conversation_id; v14: dropped legacy
 *           scenario_id column + index from coaching_event; canonical key is
 *           module_family_id, v3.3 alignment; v13: W5-A — chw_gap_profile_local
 *           primary key column renamed scenario_id → behavioural_gap_id; v12:
 *           morning_card_cache table; v11: behavioural_gap_id on coaching_event;
 *           v10: backend-shape alignment.)
 *
 * Migration strategy: destructive re-creation for pre-release versions.
 */
/**
 * Public schema version, mirrored from [Database.version] so callers (e.g.
 * `MicroCoachingSDK.init`) can detect destructive migrations and reset
 * SharedPreferences-based watermarks accordingly.
 */
const val MICRO_COACHING_ROOM_VERSION: Int = 18

@Database(
    entities = [
        ChatMessageEntity::class,
        CoachingEventEntity::class,
        LlmTraceEntity::class,
        DigitalProficiencyEventEntity::class,
        ChwGapProfileEntity::class,
        ModuleEntity::class,
        BehaviouralGapEntity::class,
        TriggerDefinitionEntity::class,
        ModuleTriggerBindingEntity::class,
        ConfigThresholdEntity::class,
        ChwModuleCompletionEntity::class,
        ChwModulePartialCompletionEntity::class,
        MorningCardCacheEntity::class,
    ],
    version = MICRO_COACHING_ROOM_VERSION,
    exportSchema = false,
)
abstract class MicroCoachingDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun coachingEventDao(): CoachingEventDao
    abstract fun llmTraceDao(): LlmTraceDao
    abstract fun digitalProficiencyEventDao(): DigitalProficiencyEventDao
    abstract fun chwGapProfileDao(): ChwGapProfileDao
    abstract fun moduleDao(): ModuleDao
    abstract fun behaviouralGapDao(): BehaviouralGapDao
    abstract fun triggerDefinitionDao(): TriggerDefinitionDao
    abstract fun moduleTriggerBindingDao(): ModuleTriggerBindingDao
    abstract fun configThresholdDao(): ConfigThresholdDao
    abstract fun chwModuleCompletionDao(): ChwModuleCompletionDao
    abstract fun chwModulePartialCompletionDao(): ChwModulePartialCompletionDao
    abstract fun morningCardCacheDao(): MorningCardCacheDao

    companion object {
        private const val DATABASE_NAME = "microcoaching.db"

        @Volatile
        private var instance: MicroCoachingDatabase? = null

        fun getInstance(context: Context): MicroCoachingDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): MicroCoachingDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MicroCoachingDatabase::class.java,
                DATABASE_NAME,
            )
                // Explicit migrations preserve user data (chat history, modules,
                // coaching events) across schema bumps. The destructive fallback
                // below is a safety net for unanticipated future bumps that ship
                // before a migration is written.
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
