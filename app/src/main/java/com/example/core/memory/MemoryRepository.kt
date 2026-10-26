package com.example.core.memory

import android.content.Context
import com.example.core.memory.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MemoryRepository(context: Context) {
    private val database = AuraDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val actionLogDao = database.actionLogDao()
    private val userFactDao = database.userFactDao()
    private val taskExecutionDao = database.taskExecutionDao()
    private val routineDao = database.routineDao()

    // Conversations
    fun getRecentConversations(limit: Int = 100): Flow<List<ConversationEntity>> =
        conversationDao.getRecent(limit)

    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        conversationDao.searchConversations(query)

    fun getConversationsBySession(sessionId: String): Flow<List<ConversationEntity>> =
        conversationDao.getBySession(sessionId)

    suspend fun getRecentTurnsSync(limit: Int = 10): List<ConversationEntity> =
        conversationDao.getRecentTurnsSync(limit)

    suspend fun logConversation(
        speaker: String,
        text: String,
        sessionId: String = "",
        language: String = "Hinglish",
        toolsUsed: String = "",
        audioDurationSec: Float = 0f
    ) {
        if (text.isBlank()) return
        conversationDao.insert(
            ConversationEntity(
                sessionId = sessionId,
                speaker = speaker,
                text = text.trim(),
                language = language,
                toolsUsed = toolsUsed,
                audioDurationSec = audioDurationSec
            )
        )
    }

    suspend fun clearConversations() = conversationDao.clearAll()
    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)
    suspend fun deleteSession(sessionId: String) = conversationDao.deleteBySession(sessionId)

    // Action Logs
    fun getActionLogs(limit: Int = 100): Flow<List<ActionLogEntity>> =
        actionLogDao.getRecentLogs(limit)

    fun searchLogs(query: String): Flow<List<ActionLogEntity>> =
        actionLogDao.searchLogs(query)

    suspend fun logAction(
        toolName: String,
        argsJson: String,
        output: String,
        success: Boolean,
        durationMs: Long
    ) {
        actionLogDao.insert(
            ActionLogEntity(
                toolName = toolName,
                argsJson = argsJson,
                output = output,
                success = success,
                durationMs = durationMs
            )
        )
    }

    suspend fun clearActionLogs() = actionLogDao.clearAll()
    suspend fun deleteActionLog(id: Long) = actionLogDao.deleteById(id)

    // User Facts & Memory
    fun getAllFacts(): Flow<List<UserFactEntity>> = userFactDao.getAll()

    suspend fun saveFact(key: String, value: String, category: String = "fact") {
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        require(cleanKey.isNotBlank() && cleanValue.isNotBlank()) { "Fact key and value are required" }
        require(cleanKey.length <= 120) { "Fact key is too long (max 120 characters)" }
        require(cleanValue.length <= 8000) { "Fact value is too long (max 8000 characters)" }
        val existing = userFactDao.findByKeyNormalized(cleanKey)
        userFactDao.insert(UserFactEntity(id = existing?.id ?: 0, key = cleanKey, value = cleanValue, category = category.trim().lowercase().ifBlank { "fact" }))
    }

    suspend fun updateFact(id: Long, key: String, value: String, category: String = "fact") {
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        require(cleanKey.isNotBlank() && cleanValue.isNotBlank()) { "Fact key and value are required" }
        require(cleanKey.length <= 120) { "Fact key is too long (max 120 characters)" }
        require(cleanValue.length <= 8000) { "Fact value is too long (max 8000 characters)" }
        val conflict = userFactDao.findByKeyNormalized(cleanKey)
        require(conflict == null || conflict.id == id) { "A fact with this key already exists" }
        val existing = userFactDao.getById(id) ?: return
        userFactDao.insert(existing.copy(key = cleanKey, value = cleanValue, category = category.trim().lowercase().ifBlank { "fact" }))
    }

    suspend fun getFactById(id: Long): UserFactEntity? = userFactDao.getById(id)

    suspend fun findFact(key: String): String? = userFactDao.findByKeyNormalized(key.trim())?.value

    suspend fun deleteFact(id: Long) = userFactDao.deleteById(id)
    suspend fun clearAllFacts() = userFactDao.clearAll()

    /**
     * Builds a compact string of user facts & aliases to inject into the Gemini system prompt.
     */
    suspend fun buildMemoryContextSummary(): String {
        return try {
            val facts = userFactDao.getAll().first()
            if (facts.isEmpty()) return ""
            val sb = StringBuilder("User Known Facts & Aliases:\n")
            facts.forEach { fact ->
                sb.append("- [${fact.category}] ${fact.key}: ${fact.value}\n")
            }
            sb.toString()
        } catch (e: Exception) {
            android.util.Log.e("Aura", "Failed to build memory context summary", e)
            ""
        }
    }

    // Task Executions
    fun getRecentTasks(limit: Int = 50): Flow<List<TaskExecutionEntity>> =
        taskExecutionDao.getRecentTasks(limit)

    suspend fun logTask(task: TaskExecutionEntity) {
        taskExecutionDao.insert(task)
    }

    suspend fun deleteTask(id: Long) = taskExecutionDao.deleteById(id)
    suspend fun clearTasks() = taskExecutionDao.clearAll()

    // Routines
    fun getAllRoutines(): Flow<List<RoutineEntity>> = routineDao.getAllRoutines()

    suspend fun findRoutine(name: String): RoutineEntity? = routineDao.findByName(name.trim())

    suspend fun saveRoutine(routine: RoutineEntity): Long = routineDao.insert(routine)

    suspend fun updateRoutine(routine: RoutineEntity) = routineDao.update(routine)

    suspend fun deleteRoutineByName(name: String) = routineDao.deleteByName(name.trim())

    suspend fun deleteRoutineById(id: Long) = routineDao.deleteById(id)

    suspend fun getActiveRoutinesSync(): List<RoutineEntity> = routineDao.getActiveRoutinesSync()
}
