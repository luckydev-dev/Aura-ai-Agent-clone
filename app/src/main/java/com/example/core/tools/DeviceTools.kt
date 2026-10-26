package com.example.core.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.flow.first
import java.util.Locale

class DeviceTools(
    private val context: Context,
    private val rootManager: RootManager
) {

    val setWifiTool = object : Tool {
        override val name = "set_wifi"
        override val description = "Turns Wi-Fi on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc wifi enable || cmd wifi set-wifi-enabled enabled" else "svc wifi disable || cmd wifi set-wifi-enabled disabled"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Wi-Fi turned on" else "Wi-Fi turned off")
        }
    }

    val setBluetoothTool = object : Tool {
        override val name = "set_bluetooth"
        override val description = "Turns Bluetooth on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc bluetooth enable || cmd bluetooth_manager enable" else "svc bluetooth disable || cmd bluetooth_manager disable"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Bluetooth turned on" else "Bluetooth turned off")
        }
    }

    val setMobileDataTool = object : Tool {
        override val name = "set_mobile_data"
        override val description = "Turns cellular mobile data on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc data enable" else "svc data disable"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Mobile data enabled" else "Mobile data disabled")
        }
    }

    val setNetworkTypeTool = object : Tool {
        override val name = "set_network_type"
        override val description = "Sets cellular network type/mode using root access (e.g., '4g_only' / 'lte_only', '5g_only' / 'nr_only', '5g_auto', '4g_auto', '3g_only', '2g_only')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "network_type" to mapOf(
                    "type" to "string",
                    "description" to "The target cellular network type: '4g_only' (or 'lte_only'), '5g_only' (or 'nr_only'), '5g_auto', '4g_auto', '3g_only', '2g_only'."
                )
            ),
            "required" to listOf("network_type")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val rawType = args["network_type"]?.toString()?.lowercase(java.util.Locale.ROOT)?.trim() ?: ""
            val (modeCode, label) = when {
                rawType.contains("5g_only") || rawType.contains("nr_only") || rawType.contains("only 5g") || rawType.contains("only nr") || rawType.contains("5g single") -> Pair(20, "5G NR Only")
                rawType.contains("4g_only") || rawType.contains("lte_only") || rawType.contains("only 4g") || rawType.contains("only lte") || rawType.contains("4g single") -> Pair(11, "4G LTE Only")
                rawType.contains("5g") -> Pair(33, "5G / 4G Auto")
                rawType.contains("4g") || rawType.contains("lte") -> Pair(9, "4G / 3G / 2G Auto")
                rawType.contains("3g") -> Pair(2, "3G Only")
                rawType.contains("2g") -> Pair(1, "2G Only")
                else -> Pair(11, "4G LTE Only")
            }

            val commands = listOf(
                "cmd phone set-preferred-network-type $modeCode",
                "cmd phone set-preferred-network-type 0 $modeCode",
                "cmd phone set-preferred-network-type 1 $modeCode",
                "settings put global preferred_network_mode $modeCode",
                "settings put global preferred_network_mode0 $modeCode",
                "settings put global preferred_network_mode1 $modeCode",
                "settings put global user_preferred_network_mode $modeCode",
                "setprop persist.telephony.default_network $modeCode,$modeCode",
                "setprop persist.radio.prefernetwork $modeCode",
                "service call phone 80 i32 0 i32 $modeCode",
                "service call phone 80 i32 1 i32 $modeCode",
                "service call phone 83 i32 0 i32 $modeCode",
                "service call phone 83 i32 1 i32 $modeCode"
            )

            val rootCmd = commands.joinToString(" ; ")
            val res = rootManager.execute(rootCmd)

            // Pulse telephony refresh
            rootManager.execute("cmd connectivity radio wifi || true")

            return ToolExecutionResult(
                success = true,
                output = "Cellular network mode set to $label (Mode Code $modeCode) via root."
            )
        }
    }

    val setAirplaneModeTool = object : Tool {
        override val name = "set_airplane_mode"
        override val description = "Turns airplane mode on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) {
                "cmd connectivity airplane-mode enable || (settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true)"
            } else {
                "cmd connectivity airplane-mode disable || (settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false)"
            }
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Airplane mode turned on" else "Airplane mode turned off")
        }
    }

    val setFlashlightTool = object : Tool {
        override val name = "set_flashlight"
        override val description = "Turns the camera flashlight/torch on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
                cameraManager.setTorchMode(cameraId, on)
                ToolExecutionResult(true, if (on) "Flashlight turned on" else "Flashlight turned off")
            } catch (e: Exception) {
                val cmd = if (on) "echo 255 > /sys/class/leds/flashlight/brightness" else "echo 0 > /sys/class/leds/flashlight/brightness"
                val res = rootManager.execute(cmd)
                ToolExecutionResult(res.success, if (on) "Flashlight enabled" else "Flashlight disabled")
            }
        }
    }

    val setHotspotTool = object : Tool {
        override val name = "set_hotspot"
        override val description = "Turns portable Wi-Fi hotspot on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "cmd connectivity start-tethering wifi" else "cmd connectivity stop-tethering wifi"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Hotspot enabled" else "Hotspot disabled")
        }
    }

    val setLocationTool = object : Tool {
        override val name = "set_location"
        override val description = "Turns GPS / location services on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val mode = if (on) 3 else 0
            val res = rootManager.execute("settings put secure location_mode $mode")
            return ToolExecutionResult(res.success, if (on) "Location turned on" else "Location turned off")
        }
    }

    val setDoNotDisturbTool = object : Tool {
        override val name = "set_do_not_disturb"
        override val description = "Sets Do Not Disturb mode ('on', 'off', or 'priority')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'on', 'off', or 'priority'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "off"
            val zenMode = when (mode) {
                "on", "alarms", "total" -> 2
                "priority" -> 1
                else -> 0
            }
            val res = rootManager.execute("settings put global zen_mode $zenMode")
            return ToolExecutionResult(res.success, "DND set to $mode")
        }
    }

    val setAutoRotateTool = object : Tool {
        override val name = "set_auto_rotate"
        override val description = "Toggles auto-rotate screen orientation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val res = rootManager.execute("settings put system accelerometer_rotation ${if (on) 1 else 0}")
            return ToolExecutionResult(res.success, if (on) "Auto-rotate turned on" else "Auto-rotate turned off")
        }
    }

    val setDarkModeTool = object : Tool {
        override val name = "set_dark_mode"
        override val description = "Toggles system dark mode theme ('yes', 'no', or 'auto')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'yes', 'no', or 'auto'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "yes"
            val res = rootManager.execute("cmd uimode night $mode")
            return ToolExecutionResult(res.success, "System dark mode set to $mode")
        }
    }

    val setBrightnessTool = object : Tool {
        override val name = "set_brightness"
        override val description = "Sets screen brightness percentage (0 to 100)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("percent" to mapOf("type" to "integer", "description" to "0 to 100.")),
            "required" to listOf("percent")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val percent = (args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: return ToolExecutionResult(false, "Missing percent")
            val raw = (percent * 255 / 100).coerceIn(1, 255)
            val res = rootManager.execute("settings put system screen_brightness_mode 0 && settings put system screen_brightness $raw")
            return ToolExecutionResult(res.success, "Brightness set to $percent%")
        }
    }

    val setVolumeTool = object : Tool {
        override val name = "set_volume"
        override val description = "Sets volume percentage for stream (media, ring, alarm, notification, call)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "stream" to mapOf("type" to "string", "description" to "Stream name: 'media', 'ring', 'alarm', 'call'."),
                "percent" to mapOf("type" to "integer", "description" to "0 to 100.")
            ),
            "required" to listOf("percent")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val stream = args["stream"]?.toString()?.lowercase(Locale.ROOT) ?: "media"
            val percent = (args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: return ToolExecutionResult(false, "Missing percent")
            val streamId = when (stream) {
                "ring" -> 2
                "call", "voice" -> 0
                "alarm" -> 4
                "notification" -> 5
                else -> 3 // media
            }
            val res = rootManager.execute("cmd media_session volume --stream $streamId --set ${(percent * 15 / 100).coerceIn(0, 15)}")
            return ToolExecutionResult(res.success, "Set $stream volume to $percent%")
        }
    }

    val setRingerModeTool = object : Tool {
        override val name = "set_ringer_mode"
        override val description = "Sets ringer mode ('normal', 'vibrate', 'silent')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'normal', 'vibrate', or 'silent'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "normal"
            val ringerVal = when (mode) {
                "silent" -> 0
                "vibrate" -> 1
                else -> 2
            }
            val res = rootManager.execute("settings put global mode_ringer $ringerVal")
            return ToolExecutionResult(res.success, "Ringer set to $mode")
        }
    }

    val getBatteryTool = object : Tool {
        override val name = "get_battery"
        override val description = "Gets current battery level and charging status."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("dumpsys battery | grep -E 'level|status|temperature'")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getStorageTool = object : Tool {
        override val name = "get_storage"
        override val description = "Gets device internal storage free/used space."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("df -h /data")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getRamTool = object : Tool {
        override val name = "get_ram"
        override val description = "Gets RAM memory usage."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("free -h || head -n 4 /proc/meminfo")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getNetworkInfoTool = object : Tool {
        override val name = "get_network_info"
        override val description = "Gets Wi-Fi SSID, IP address, and network connectivity state."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("ip addr show wlan0 || ip route")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getDeviceInfoTool = object : Tool {
        override val name = "get_device_info"
        override val description = "Gets device hardware model, Android version, and root status."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val model = rootManager.execute("getprop ro.product.model").stdout.trim()
            val androidVer = rootManager.execute("getprop ro.build.version.release").stdout.trim()
            val diag = rootManager.getDiagnosticInfo()
            return ToolExecutionResult(true, "Model: $model\nAndroid: $androidVer\nRoot: $diag")
        }
    }

    val takeScreenshotTool = object : Tool {
        override val name = "take_screenshot"
        override val description = "Captures a screenshot of the current screen."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "describe" to mapOf("type" to "boolean", "description" to "Whether to also summarize what is on screen.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = com.example.AuraApplication.instance.overlayController.hideForCapture {
                rootManager.execute("screencap -p /sdcard/aura_screenshot.png")
            }
            return if (res.success) {
                ToolExecutionResult(true, "Screenshot saved to /sdcard/aura_screenshot.png")
            } else {
                ToolExecutionResult(false, "Screenshot failed: ${res.stderr}")
            }
        }
    }

    val recordScreenTool = object : Tool {
        override val name = "record_screen"
        override val description = "Records the screen for a given duration in seconds."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("seconds" to mapOf("type" to "integer", "description" to "Duration in seconds (e.g. 5, 10).")),
            "required" to listOf("seconds")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val sec = (args["seconds"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 10
            val res = rootManager.execute("screenrecord --time-limit $sec /sdcard/aura_screenrecord.mp4")
            return if (res.success) {
                ToolExecutionResult(true, "Screen recording saved to /sdcard/aura_screenrecord.mp4 ($sec seconds)")
            } else {
                ToolExecutionResult(false, "Recording failed: ${res.stderr}")
            }
        }
    }

    val rebootTool = object : Tool {
        override val name = "reboot"
        override val description = "Reboots the device into normal, recovery, bootloader, or soft reboot mode. Requires confirmation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'normal', 'recovery', 'bootloader', or 'soft'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "normal"
            val cmd = when (mode) {
                "recovery" -> "reboot recovery"
                "bootloader", "fastboot" -> "reboot bootloader"
                "soft" -> "setprop ctl.restart zygote"
                else -> "reboot"
            }
            rootManager.execute(cmd)
            return ToolExecutionResult(true, "Rebooting into $mode mode...")
        }
    }

    val shutdownTool = object : Tool {
        override val name = "shutdown"
        override val description = "Powers off / shuts down the device. Requires confirmation."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            rootManager.execute("reboot -p || poweroff")
            return ToolExecutionResult(true, "Shutting down device...")
        }
    }

    val controlMediaTool = object : Tool {
        override val name = "control_media"
        override val description = "Controls media playback (action: 'play_pause', 'next', 'previous', 'stop')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("action" to mapOf("type" to "string", "description" to "'play_pause', 'next', 'previous', or 'stop'.")),
            "required" to listOf("action")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val action = args["action"]?.toString()?.lowercase(Locale.ROOT) ?: "play_pause"
            val keycode = when (action) {
                "next" -> 87
                "previous", "prev" -> 88
                "stop" -> 86
                else -> 85 // KEYCODE_MEDIA_PLAY_PAUSE
            }
            val res = com.example.AuraApplication.instance.inputController.pressKey(keycode, "Media $action")
            return ToolExecutionResult(res, "Media playback $action executed")
        }
    }

    val getDeviceTelemetryTool = object : Tool {
        override val name = "get_device_telemetry"
        override val description = "Gets detailed device telemetry (battery health, CPU load, RAM free, thermal state)."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val bat = rootManager.execute("dumpsys battery | grep -E 'level|temperature|status|health'").stdout.trim()
            val ram = rootManager.execute("free -h || head -n 4 /proc/meminfo").stdout.trim()
            val uptime = rootManager.execute("uptime").stdout.trim()
            val report = "=== DEVICE TELEMETRY ===\nUptime: $uptime\n\n--- Battery ---\n$bat\n\n--- Memory ---\n$ram"
            return ToolExecutionResult(true, report)
        }
    }

    val toggleNfcTool = object : Tool {
        override val name = "toggle_nfc"
        override val description = "Turns NFC on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc nfc enable" else "svc nfc disable"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "NFC enabled" else "NFC disabled")
        }
    }

    val vibrateDeviceTool = object : Tool {
        override val name = "vibrate_device"
        override val description = "Triggers a haptic vibration pulse or alert feedback."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("duration_ms" to mapOf("type" to "integer", "description" to "Vibration duration in milliseconds (default 500)."))
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val ms = (args["duration_ms"] as? Number)?.toLong() ?: 500L
            return try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(ms)
                }
                ToolExecutionResult(true, "Vibrated device for $ms ms")
            } catch (e: Exception) {
                ToolExecutionResult(false, "Vibration failed: ${e.message}")
            }
        }
    }

    val unlockPhoneTool = object : Tool {
        override val name = "unlock_phone"
        override val description = "Wakes up and unlocks the phone screen using owner's configured credentials (pattern, PIN, or password) via verified root/accessibility state machine."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val unlockManager = com.example.core.unlock.UnlockManager.getInstance(context)
            val result = unlockManager.executeUnlock(dryRunOnlyBouncer = false)
            val isSuccess = result.status == com.example.core.unlock.UnlockStatus.UNLOCKED ||
                            result.status == com.example.core.unlock.UnlockStatus.ALREADY_UNLOCKED
            return ToolExecutionResult(isSuccess, result.humanMessage)
        }
    }

    val lockPhoneTool = object : Tool {
        override val name = "lock_phone"
        override val description = "Immediately locks the phone screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val a11y = AuraAccessibilityService.instance
            val locked = a11y?.lockDevice() ?: false
            if (locked) {
                return ToolExecutionResult(true, "Phone screen locked successfully")
            }
            val res = com.example.AuraApplication.instance.inputController.pressKey(223, "Lock Screen") // KEYCODE_SLEEP
            return ToolExecutionResult(res, if (res) "Phone screen locked" else "Could not lock screen")
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        setWifiTool, setBluetoothTool, setMobileDataTool, setNetworkTypeTool, setAirplaneModeTool,
        setFlashlightTool, setHotspotTool, setLocationTool, setDoNotDisturbTool,
        setAutoRotateTool, setDarkModeTool, setBrightnessTool, setVolumeTool,
        setRingerModeTool, getBatteryTool, getStorageTool, getRamTool,
        getNetworkInfoTool, getDeviceInfoTool, takeScreenshotTool, recordScreenTool,
        rebootTool, shutdownTool, controlMediaTool, getDeviceTelemetryTool,
        toggleNfcTool, vibrateDeviceTool, unlockPhoneTool, lockPhoneTool
    )
}
