package com.example.core.root

import java.util.Locale

object SafetyLayer {

    // Absolute hard blocklist that cannot be bypassed under any circumstances
    private val CATASTROPHIC_PATTERNS = listOf(
        Regex("""\bdd\b.*(\bof=/dev/(block|mmcblk|sd|sda|sdb|bootdevice))"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bformat\s+/dev/"""),
        Regex("""\bfastboot\b"""),
        Regex("""\bflash_image\b"""),
        Regex("""\bblkdiscard\b"""),
        Regex("""\bwipe\s+(data|system|all|\*|cache)"""),
        Regex("""\bwipe\b"""),
        Regex("""\brecovery\b.*--wipe"""),
        Regex("""\bsetprop\s+persist\.sys\."""),
        Regex("""(\bpm\s+uninstall\b|\bcmd\s+package\s+uninstall\b).*\b(com\.android\.|com\.google\.android\.gms|android\b)"""),
        Regex("""/proc/sysrq-trigger""")
    )

    // Patterns for operations requiring explicit confirmation when confirmation is enabled
    private val RISKY_PATTERNS = listOf(
        Regex("""\breboot\b"""),
        Regex("""\bshutdown\b"""),
        Regex("""\bpoweroff\b"""),
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\bcmd\s+package\s+uninstall\b"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\brm\b.*(-r|--recursive)"""),
        Regex("""\brmdir\b"""),
        Regex("""\bsettings\s+put\s+(secure|global)\b""")
    )

    private val PROTECTED_ROOT_PATHS = setOf(
        "/", "/data", "/system", "/vendor", "/boot", "/dev", "/dev/block",
        "/etc", "/bin", "/sbin", "/product", "/system_ext", "/apex"
    )

    /**
     * Splits chained commands and extracts backtick and $(...) expressions.
     */
    fun extractSubCommands(command: String): List<String> {
        val subcommands = mutableListOf<String>()

        // 1. Extract $(...) expressions
        val dollarParenRegex = Regex("""\$\(([^)]+)\)""")
        dollarParenRegex.findAll(command).forEach { match ->
            subcommands.addAll(extractSubCommands(match.groupValues[1]))
        }

        // 2. Extract backtick `...` expressions
        val backtickRegex = Regex("""`([^`]+)`""")
        backtickRegex.findAll(command).forEach { match ->
            subcommands.addAll(extractSubCommands(match.groupValues[1]))
        }

        // 3. Remove captured subshells from command and split on operators
        var sanitized = command.replace(dollarParenRegex, " ")
        sanitized = sanitized.replace(backtickRegex, " ")

        // Split on ;, &&, ||, |, \n
        val parts = sanitized.split(Regex("""(;|&{1,2}|\|{1,2}|\n)"""))
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotBlank()) {
                subcommands.add(trimmed)
            }
        }

        return subcommands
    }

    /**
     * Checks if a single command or any sub-command in a chained string is catastrophic.
     */
    fun isCatastrophic(command: String): Boolean {
        val subCommands = extractSubCommands(command)
        if (subCommands.isEmpty()) return isCatastrophicSingle(command)
        return subCommands.any { isCatastrophicSingle(it) }
    }

    private fun isCatastrophicSingle(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.ROOT)

        // Check CATASTROPHIC_PATTERNS
        if (CATASTROPHIC_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return true
        }

        // Check pipes into shell (e.g. curl ... | sh or echo ... | su)
        if (Regex("""\|\s*(sh|su|bash)\b""").containsMatchIn(normalized)) {
            // Pipes into shell with unverified input is blocked
            return true
        }

        // Check rm commands targeting protected root paths
        if (Regex("""\brm\b""").containsMatchIn(normalized)) {
            val hasRecursive = normalized.contains("-r") ||
                    normalized.contains("-f") ||
                    normalized.contains("--recursive") ||
                    normalized.contains("--force") ||
                    normalized.contains("--no-preserve-root")

            if (hasRecursive) {
                // Tokenize words
                val tokens = normalized.split(Regex("""\s+""")).filter { !it.startsWith("-") && it != "rm" }
                for (token in tokens) {
                    val cleanPath = normalizeTargetPath(token)
                    if (cleanPath in PROTECTED_ROOT_PATHS || cleanPath.startsWith("/data/") || cleanPath.startsWith("/system/")) {
                        // If targeting root, all data, all system or wildcards
                        if (token == "/" || token == "/data" || token == "/system" ||
                            token == "/data/*" || token == "/system/*" || token == "/*" ||
                            token.contains("/..") || cleanPath in PROTECTED_ROOT_PATHS
                        ) {
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    private fun normalizeTargetPath(path: String): String {
        var p = path.trim()
        if (p.endsWith("/*")) {
            p = p.substring(0, p.length - 2)
        }
        while (p.length > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length - 1)
        }
        // Normalize /sdcard/.. to /
        if (p == "/sdcard/.." || p == "/storage/emulated/0/.." || p == "/data/..") {
            p = "/"
        }
        return p
    }

    /**
     * Checks if a command or any sub-command requires explicit confirmation.
     */
    fun isRisky(command: String): Boolean {
        val subCommands = extractSubCommands(command)
        if (subCommands.isEmpty()) return isRiskySingle(command)
        return subCommands.any { isRiskySingle(it) }
    }

    private fun isRiskySingle(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.ROOT)
        if (isCatastrophicSingle(normalized)) return true
        return RISKY_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /**
     * Evaluates command safety and returns assessment.
     */
    fun evaluateCommand(command: String, requireConfirmation: Boolean): CommandSafetyAssessment {
        if (isCatastrophic(command)) {
            return CommandSafetyAssessment.CatastrophicBlocked(
                "Command contains a strictly prohibited catastrophic pattern that cannot be executed."
            )
        }
        if (requireConfirmation && isRisky(command)) {
            return CommandSafetyAssessment.RequiresConfirmation(
                "This action will modify system state or delete data. Explicit confirmation required."
            )
        }
        return CommandSafetyAssessment.Safe
    }
}

sealed class CommandSafetyAssessment {
    object Safe : CommandSafetyAssessment()
    data class RequiresConfirmation(val message: String) : CommandSafetyAssessment()
    data class CatastrophicBlocked(val reason: String) : CommandSafetyAssessment()
}
