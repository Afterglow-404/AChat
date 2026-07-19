package com.aftglw.devapi.core.storage.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aftglw.devapi.core.storage.room.migration.LegacyMigrator

/**
 * 全局 Room 数据库。
 *
 * 包含 4 张表：单聊列表、消息（单聊+群聊共享）、群聊列表、世界书。
 *
 * 首次访问时通过 [LegacyMigrator] 从旧 SharedPreferences/JSON 一次性迁移历史数据，
 * 并在 wechat_settings 中写入 `room_migrated_v1` 标记避免重复迁移。
 *
 * 单例：通过 [get] 获取。同步 DAO 方法可直接调用，调用方负责切换到 IO 线程
 * （现有 manager 已在内部用 runBlocking(Dispatchers.IO) 包装）。
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class, GroupEntity::class, WorldbookEntity::class],
    version = 4,
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

        private fun build(context: Context): AppDatabase {
            val ctx = context.applicationContext
            // 首次创建后立即从旧数据迁移
            val db = Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            // 同步执行一次性迁移（在 IO 线程内 runBlocking）
            LegacyMigrator.migrateIfFirstRun(ctx, db)
            return db
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
    }
}
