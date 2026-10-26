package com.example

import com.example.core.root.CommandSafetyAssessment
import com.example.core.root.SafetyLayer
import org.junit.Assert.*
import org.junit.Test

class SafetyLayerTest {

    @Test
    fun testCatastrophicCommandsAreBlocked() {
        val catastrophicCommands = listOf(
            "rm -rf /",
            "rm -f -r /",
            "rm -rf / --no-preserve-root",
            "rm --no-preserve-root -rf /",
            "rm --recursive --force /",
            "rm -rf /data",
            "rm -rf /data/*",
            "rm -rf /system",
            "rm -rf /system/*",
            "rm -rf /sdcard/..",
            "dd if=/dev/zero of=/dev/block/bootdevice",
            "dd if=/dev/random of=/dev/sda",
            "mkfs.ext4 /dev/block/sda",
            "fastboot flash boot boot.img",
            "flash_image boot boot.img",
            "blkdiscard /dev/block/sda",
            "wipe data",
            "recovery --wipe_data",
            "setprop persist.sys.foo bar",
            "pm uninstall com.android.settings",
            "cmd package uninstall com.google.android.gms",
            "echo c > /proc/sysrq-trigger"
        )

        for (cmd in catastrophicCommands) {
            assertTrue("Expected '$cmd' to be detected as catastrophic", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = false)
            assertTrue(
                "Expected catastrophic assessment for '$cmd'",
                assessment is CommandSafetyAssessment.CatastrophicBlocked
            )
        }
    }

    @Test
    fun testChainedAndSubshellCatastrophicCommands() {
        val chainedCommands = listOf(
            "ls -la; rm -rf /",
            "a; rm -rf /",
            "a && rm -rf /system",
            "$(rm -rf /)",
            "echo $(rm -rf /)",
            "echo 'hello' && rm -rf /data/*",
            "dumpsys battery || rm -rf /system",
            "cat `rm -rf /data`",
            "echo safe \n rm -rf /boot"
        )

        for (cmd in chainedCommands) {
            assertTrue("Expected chained '$cmd' to be blocked", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = false)
            assertTrue(
                "Expected catastrophic assessment for chained '$cmd'",
                assessment is CommandSafetyAssessment.CatastrophicBlocked
            )
        }
    }

    @Test
    fun testRequestedSafetyVerificationCases() {
        // Explicit cases required by user prompt
        val requiredCases = listOf(
            "rm -rf /data/*",
            "a; rm -rf /",
            "a && rm -rf /system",
            "$(rm -rf /)",
            "rm --no-preserve-root -rf /"
        )

        for (cmd in requiredCases) {
            assertTrue("Required safety case '$cmd' must be catastrophic", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = false)
            assertTrue(
                "Required safety case '$cmd' must be CatastrophicBlocked",
                assessment is CommandSafetyAssessment.CatastrophicBlocked
            )
        }
    }

    @Test
    fun testRiskyCommandsRequireConfirmation() {
        val riskyCommands = listOf(
            "reboot",
            "reboot recovery",
            "shutdown",
            "poweroff",
            "pm uninstall com.whatsapp",
            "cmd package uninstall com.spotify.music",
            "pm clear com.google.android.youtube",
            "rm -rf /sdcard/Downloads/old_file.txt",
            "settings put secure enabled_accessibility_services com.example"
        )

        for (cmd in riskyCommands) {
            assertTrue("Expected '$cmd' to be marked as risky", SafetyLayer.isRisky(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = true)
            assertTrue(
                "Expected confirmation requirement for '$cmd'",
                assessment is CommandSafetyAssessment.RequiresConfirmation
            )
        }
    }

    @Test
    fun testSafeCommandsPassDirectly() {
        val safeCommands = listOf(
            "ls -la /sdcard",
            "dumpsys battery",
            "input tap 500 1000",
            "input text hello",
            "am start -a android.intent.action.VIEW",
            "cat /proc/meminfo",
            "echo 'Aura ready'"
        )

        for (cmd in safeCommands) {
            assertFalse("Expected '$cmd' not to be catastrophic", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = true)
            assertEquals("Expected '$cmd' to be safe", CommandSafetyAssessment.Safe, assessment)
        }
    }
}
