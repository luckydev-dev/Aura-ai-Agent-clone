package com.example.core.memory.database

import android.content.Context
import androidx.room.*
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
    @Query("SELECT * FROM user_facts ORDER BY category ASC, `key` ASC")
    fun getAll(): Flow<List<UserFactEntity>>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): UserFactEntity?

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
        RoutineEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun userFactDao(): UserFactDao
    abstract fun taskExecutionDao(): TaskExecutionDao
    abstract fun routineDao(): RoutineDao

    companion object {
        @Volatile
        private var INSTANCE: AuraDatabase? = null

        fun getInstance(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
