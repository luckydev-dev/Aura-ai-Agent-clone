package com.example.core.memory.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val speaker: String, // "user" or "aura"
    val text: String,
    val language: String = "Hinglish",
    val toolsUsed: String = "",
    val audioDurationSec: Float = 0f
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val argsJson: String,
    val output: String,
    val success: Boolean,
    val durationMs: Long
)

@Entity(tableName = "user_facts")
data class UserFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String // "alias", "contact", "preference", "fact"
)

@Entity(tableName = "task_executions")
data class TaskExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val goal: String,
    val stepsJson: String,
    val outcome: String,
    val success: Boolean,
    val durationMs: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val triggerPhrase: String = "",
    val stepsJson: String, // serialized JSON list of tool invocations
    val scheduledTime: String = "", // e.g. "08:30" or empty
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
