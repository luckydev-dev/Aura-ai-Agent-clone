package com.example.core.memory.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurnsSync(limit: Int = 10): List<ConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE toolName LIKE '%' || :query || '%' OR output LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchLogs(query: String): Flow<List<ActionLogEntity>>

    @Insert
    suspend fun insert(log: ActionLogEntity): Long

    @Query("DELETE FROM action_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()
}

@Dao
interface UserFactDao {
    @Query("SELECT * FROM user_facts ORDER BY pinned DESC, category ASC, `key` ASC")
    fun getAll(): Flow<List<UserFactEntity>>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): UserFactEntity?

    @Query("SELECT * FROM user_facts WHERE keyNormalized = LOWER(TRIM(:key)) LIMIT 1")
    suspend fun findByKeyNormalized(key: String): UserFactEntity?

    @Query("SELECT * FROM user_facts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UserFactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFactEntity): Long

    @Query("DELETE FROM user_facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_facts")
    suspend fun clearAll()
}

@Dao
interface TaskExecutionDao {
    @Query("SELECT * FROM task_executions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTasks(limit: Int = 50): Flow<List<TaskExecutionEntity>>

    @Query("SELECT * FROM task_executions WHERE taskId = :taskId LIMIT 1")
    suspend fun findById(taskId: String): TaskExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskExecutionEntity): Long

    @Query("DELETE FROM task_executions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_executions")
    suspend fun clearAll()
}

@Dao
interface TaskStepDao {
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY step ASC")
    fun getByTaskId(taskId: String): Flow<List<TaskStepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(step: TaskStepEntity): Long

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY createdAt DESC")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE isEnabled = 1")
    suspend fun getActiveRoutinesSync(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE LOWER(name) = LOWER(:name)")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM routines")
    suspend fun clearAll()
}

@Database(
    entities = [
        ConversationEntity::class,
        ActionLogEntity::class,
        UserFactEntity::class,
        TaskExecutionEntity::class,
        TaskStepEntity::class,
        RoutineEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun userFactDao(): UserFactDao
    abstract fun taskExecutionDao(): TaskExecutionDao
    abstract fun taskStepDao(): TaskStepDao
    abstract fun routineDao(): RoutineDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_executions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        goal TEXT NOT NULL,
                        stepsJson TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        success INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS routines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        triggerPhrase TEXT NOT NULL,
                        stepsJson TEXT NOT NULL,
                        scheduledTime TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_facts ADD COLUMN keyNormalized TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE user_facts ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_facts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_facts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("DELETE FROM user_facts WHERE id NOT IN (SELECT MAX(id) FROM user_facts GROUP BY LOWER(TRIM(`key`)))")
                database.execSQL("UPDATE user_facts SET keyNormalized = LOWER(TRIM(`key`))")
                database.execSQL("UPDATE user_facts SET createdAt = CASE WHEN createdAt = 0 THEN CAST(strftime('%s','now') AS INTEGER) * 1000 ELSE createdAt END")
                database.execSQL("UPDATE user_facts SET updatedAt = CASE WHEN updatedAt = 0 THEN createdAt ELSE updatedAt END")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_facts_keyNormalized ON user_facts(keyNormalized)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_steps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId TEXT NOT NULL,
                        step INTEGER NOT NULL,
                        thought TEXT NOT NULL,
                        action TEXT NOT NULL,
                        paramsJson TEXT NOT NULL,
                        result TEXT NOT NULL,
                        verified INTEGER NOT NULL,
                        screenSignature TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        @Volatile
        private var INSTANCE: AuraDatabase? = null

        fun getInstance(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
