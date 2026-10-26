package com.example.core.root

data class RootCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean
)
