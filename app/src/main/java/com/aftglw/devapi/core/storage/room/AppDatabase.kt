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
 * 包含 4 张表：单聊列表、消息（单聊+群聊共享）、群聊列表、世界书。
 *
 * 一次性历史数据迁移由 [WispApplication.onCreate] 在 IO 协程中异步调用 [preInit] 触发，
 * 不再在 [build] 中同步执行（旧实现阻塞主线程数秒）。
 * 迁移完成后在 wechat_settings 中写入 `room_migrated_v1` 标记避免重复迁移。
 *
 * 单例：通过 [get] 获取。同步 DAO 方法可直接调用，调用方负责切换到 IO 线程
 * （现有 manager 已在内部用 runBlocking(Dispatchers.IO) 包装）。
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class, GroupEntity::class, WorldbookEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun worldbookDao(): WorldbookDao

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
            // 仅对历史版本 1-4 允许破坏性迁移；版本 5+ 必须显式迁移，避免静默丢数据
            return Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
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
    }
}
