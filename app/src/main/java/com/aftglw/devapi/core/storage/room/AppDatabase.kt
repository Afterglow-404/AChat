package com.aftglw.devapi.core.storage.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aftglw.devapi.core.storage.room.migration.LegacyMigrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局 Room 数据库。
 *
 * 包含 6 张表：单聊列表、消息（单聊+群聊共享）、群聊列表、世界书、未完成事件、已处理事件。
 *
 * 一次性历史数据迁移由 [WispApplication.onCreate] 在 IO 协程中异步调用 [preInit] 触发，
 * 不再在 [build] 中同步执行（旧实现阻塞主线程数秒）。
 * 迁移完成后在 wechat_settings 中写入 `room_migrated_v1` 标记避免重复迁移。
 *
 * 单例：通过 [get] 获取。同步 DAO 方法可直接调用，调用方负责切换到 IO 线程
 * （现有 manager 已在内部用 runBlocking(Dispatchers.IO) 包装）。
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class, GroupEntity::class, WorldbookEntity::class, PendingEventEntity::class, ProcessedEventEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun worldbookDao(): WorldbookDao
    abstract fun pendingEventDao(): PendingEventDao
    abstract fun processedEventDao(): ProcessedEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        private const val DB_NAME = "wisp.db"

        @JvmStatic
        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        /**
         * 异步预热：在 Application.onCreate 的 IO 协程中调用。
         * 触发 LegacyMigrator 一次性迁移（如尚未迁移）。
         */
        suspend fun preInit(ctx: Context) {
            withContext(Dispatchers.IO) {
                val db = get(ctx)
                LegacyMigrator.migrateIfFirstRun(ctx.applicationContext, db)
            }
        }

        private fun build(context: Context): AppDatabase {
            val ctx = context.applicationContext
            // 显式迁移路径 1→2→3→4→5；不再用 fallbackToDestructiveMigrationFrom，
            // 因为 Room 2.x+ 禁止对同一 start version 同时声明 addMigration + fallback（build 时抛 IllegalArgumentException）。
            // 若未来 schema 变更未配迁移，让 Room 直接抛异常暴露问题，而不是静默丢数据。
            return Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
        }

        /** 测试用：注入内存数据库 */
        @JvmStatic
        fun setForTest(db: AppDatabase?) {
            instance = db
        }

        /** 测试用：重置单例 */
        @JvmStatic
        fun resetForTest() {
            instance = null
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN voice_path TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN voice_duration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN voice_transcript TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN mode TEXT NOT NULL DEFAULT 'free'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN member_settings TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_error INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN retry_prompt TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sticker_path TEXT DEFAULT NULL")
            }
        }

        /**
         * v6 → v7：AffectiveField 体系新增两张表。
         * - pending_events：未完成事件（设计文档 2.3.7）
         * - processed_events：幂等键，防止手机/Desktop/服务器重复处理同一条消息（设计文档 14.8）
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        chat_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        trigger_text TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 0.5,
                        closure_type TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        last_attempt_at INTEGER,
                        resolved INTEGER NOT NULL DEFAULT 0,
                        archived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_events_chat_name_resolved_archived ON pending_events(chat_name, resolved, archived)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS processed_events (
                        event_id TEXT NOT NULL PRIMARY KEY,
                        processed_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_processed_events_processed_at ON processed_events(processed_at)"
                )
            }
        }

        /** v7 -> v8：保留消息的外部来源事件 ID，支持主动消息审计和幂等追踪。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN source_event_id TEXT DEFAULT NULL")
            }
        }
    }
}
