package com.example.core.memory.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val speaker: String,
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

@Entity(
    tableName = "user_facts",
    indices = [Index(value = ["keyNormalized"], unique = true)]
)
data class UserFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String,
    @ColumnInfo(defaultValue = "''") val keyNormalized: String = key.trim().lowercase(Locale.ROOT),
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false
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

@Entity(tableName = "task_steps")
data class TaskStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val step: Int,
    val thought: String,
    val action: String,
    val paramsJson: String,
    val result: String,
    val verified: Boolean,
    val screenSignature: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val triggerPhrase: String = "",
    val stepsJson: String,
    val scheduledTime: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
