package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.medtroniclabs.microcoaching.data.db.dao.AssignedModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.AssignedVideoDao
import com.medtroniclabs.microcoaching.data.db.dao.BadgeDao
import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.dao.ChatMessageDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwQuizQuestionStateDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwModuleCompletionDao
import com.medtroniclabs.microcoaching.data.db.dao.CachedAssetDao
import com.medtroniclabs.microcoaching.data.db.dao.DashboardCacheDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwModulePartialCompletionDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.ConfigThresholdDao
import com.medtroniclabs.microcoaching.data.db.dao.DigitalProficiencyEventDao
import com.medtroniclabs.microcoaching.data.db.dao.LlmTraceDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.MorningCardCacheDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleTriggerBindingDao
import com.medtroniclabs.microcoaching.data.db.dao.ChatFaqDao
import com.medtroniclabs.microcoaching.data.db.dao.PublishedSourceDocumentDao
import com.medtroniclabs.microcoaching.data.db.dao.RequestedModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.TriggerDefinitionDao
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity
import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.CachedAssetEntity
import com.medtroniclabs.microcoaching.data.db.entity.DashboardCacheEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwQuizQuestionStateEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModulePartialCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity
import com.medtroniclabs.microcoaching.data.db.entity.RequestedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_14_15
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_15_16
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_16_17
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_17_18
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_18_19
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_19_20
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_20_21
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_21_22
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_22_23
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_23_24
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_24_25
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_25_26
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_26_27
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_27_28
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_28_29
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_29_30
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_30_31
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_31_32
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_32_33
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_33_34
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_34_35

/**
 * Public schema version, mirrored from [Database.version] so callers (e.g.
 * `MicroCoachingSDK.init`) can detect destructive migrations and reset
 * SharedPreferences-based watermarks accordingly.
 */
const val MICRO_COACHING_ROOM_VERSION: Int = 35

/**
 * SDK-owned Room database (`microcoaching.db`), entirely separate from SPICE's
 * NCDMergerDatabase.
 *
 * Every schema version has an explicit `Migration` in
 * `data.db.migration`, each documenting the change it makes — that package, not this
 * KDoc, is the per-version record. `fallbackToDestructiveMigration(dropAllTables = true)`
 * is the backstop for any gap: the tables are a cache of synced backend state, so a wipe
 * costs a full re-pull rather than data loss. [MICRO_COACHING_ROOM_VERSION] is mirrored
 * out so callers can notice a destructive migration and clear their sync watermarks —
 * without that, a prefs watermark outlives the wiped tables and progress never rehydrates.
 */
@Database(
    entities = [
        AssignedModuleEntity::class,
        AssignedVideoEntity::class,
        ChatMessageEntity::class,
        CoachingEventEntity::class,
        LlmTraceEntity::class,
        DigitalProficiencyEventEntity::class,
        ChwGapProfileEntity::class,
        ChwQuizQuestionStateEntity::class,
        ModuleEntity::class,
        BehaviouralGapEntity::class,
        TriggerDefinitionEntity::class,
        ModuleTriggerBindingEntity::class,
        ConfigThresholdEntity::class,
        ChwModuleCompletionEntity::class,
        ChwModulePartialCompletionEntity::class,
        MorningCardCacheEntity::class,
        CachedAssetEntity::class,
        SourceDocumentThumbnailEntity::class,
        PublishedSourceDocumentEntity::class,
        ChatFaqEntity::class,
        DashboardCacheEntity::class,
        RequestedModuleEntity::class,
        BadgeEntity::class,
    ],
    version = MICRO_COACHING_ROOM_VERSION,
    exportSchema = false,
)
abstract class MicroCoachingDatabase : RoomDatabase() {

    abstract fun assignedModuleDao(): AssignedModuleDao
    abstract fun assignedVideoDao(): AssignedVideoDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun coachingEventDao(): CoachingEventDao
    abstract fun llmTraceDao(): LlmTraceDao
    abstract fun digitalProficiencyEventDao(): DigitalProficiencyEventDao
    abstract fun chwGapProfileDao(): ChwGapProfileDao
    abstract fun chwQuizQuestionStateDao(): ChwQuizQuestionStateDao
    abstract fun moduleDao(): ModuleDao
    abstract fun behaviouralGapDao(): BehaviouralGapDao
    abstract fun triggerDefinitionDao(): TriggerDefinitionDao
    abstract fun moduleTriggerBindingDao(): ModuleTriggerBindingDao
    abstract fun configThresholdDao(): ConfigThresholdDao
    abstract fun chwModuleCompletionDao(): ChwModuleCompletionDao
    abstract fun chwModulePartialCompletionDao(): ChwModulePartialCompletionDao
    abstract fun morningCardCacheDao(): MorningCardCacheDao
    abstract fun cachedAssetDao(): CachedAssetDao
    abstract fun publishedSourceDocumentDao(): PublishedSourceDocumentDao
    abstract fun chatFaqDao(): ChatFaqDao
    abstract fun dashboardCacheDao(): DashboardCacheDao
    abstract fun requestedModuleDao(): RequestedModuleDao
    abstract fun badgeDao(): BadgeDao

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
                .addMigrations(
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                    MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                    MIGRATION_29_30,
                    MIGRATION_30_31,
                    MIGRATION_31_32,
                    MIGRATION_32_33,
                    MIGRATION_33_34,
                    MIGRATION_34_35,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
