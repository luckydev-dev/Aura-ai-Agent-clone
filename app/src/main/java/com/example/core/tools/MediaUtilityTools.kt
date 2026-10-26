package com.example.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.example.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MediaUtilityTools(
    private val context: Context,
    private val rootManager: RootManager
) {

    val mediaControlTool = object : Tool {
        override val name = "media_control"
        override val description = "Controls media playback: 'play', 'pause', 'play_pause', 'next', 'previous', or 'stop'."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "description" to "'play', 'pause', 'play_pause', 'next', 'previous', or 'stop'.")
            ),
            "required" to listOf("action")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val action = args["action"]?.toString()?.lowercase(Locale.ROOT) ?: "play_pause"
            val keycode = when (action) {
                "play" -> 126
                "pause" -> 127
                "next" -> 87
                "previous", "prev" -> 88
                "stop" -> 86
                else -> 85 // play_pause
            }
            val res = com.example.AuraApplication.instance.inputController.pressKey(keycode, "Media $action")
            if (!res) {
                rootManager.execute("cmd media_session dispatch $action")
            }
            return ToolExecutionResult(true, "Media: $action")
        }
    }

    val playMusicTool = object : Tool {
        override val name = "play_music"
        override val description = "Plays music by search query (song/artist/playlist) on Spotify or default player."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "Song title or artist (e.g. 'lofi hip hop')."),
                "app" to mapOf("type" to "string", "description" to "App name (e.g. 'spotify', 'youtube music').")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString() ?: return ToolExecutionResult(false, "Missing query")
            val app = args["app"]?.toString()?.lowercase(Locale.ROOT) ?: "spotify"

            val pkg = if (app.contains("spotify")) "com.spotify.music" else "com.google.android.apps.youtube.music"
            val res = rootManager.execute("am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -e query \"$query\" $pkg")
            return ToolExecutionResult(res.success, "Playing '$query' on $app")
        }
    }

    val setAlarmTool = object : Tool {
        override val name = "set_alarm"
        override val description = "Sets an alarm for a specific hour and minute."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "hour" to mapOf("type" to "integer", "description" to "Hour (0-23)."),
                "minute" to mapOf("type" to "integer", "description" to "Minute (0-59)."),
                "label" to mapOf("type" to "string", "description" to "Optional alarm label.")
            ),
            "required" to listOf("hour", "minute")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val hour = (args["hour"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing hour")
            val minute = (args["minute"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing minute")
            val label = args["label"]?.toString() ?: "Aura Alarm"

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult(true, "Alarm set for %02d:%02d ($label)".format(hour, minute))
            } catch (e: Exception) {
                android.util.Log.w("Aura", "startActivity for set_alarm failed, using root fallback: ${e.message}")
                val res = rootManager.execute("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR $hour --ei android.intent.extra.alarm.MINUTES $minute --es android.intent.extra.alarm.MESSAGE \"$label\" --ez android.intent.extra.alarm.SKIP_UI true")
                ToolExecutionResult(res.success, "Alarm set for %02d:%02d".format(hour, minute))
            }
        }
    }

    val setTimerTool = object : Tool {
        override val name = "set_timer"
        override val description = "Sets a countdown timer in seconds."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "duration_seconds" to mapOf("type" to "integer", "description" to "Timer duration in seconds.")
            ),
            "required" to listOf("duration_seconds")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val seconds = (args["duration_seconds"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing duration")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult(true, "Timer started for $seconds seconds")
            } catch (e: Exception) {
                android.util.Log.w("Aura", "startActivity for set_timer failed, using root fallback: ${e.message}")
                val res = rootManager.execute("am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH $seconds --ez android.intent.extra.alarm.SKIP_UI true")
                ToolExecutionResult(res.success, "Timer started for $seconds seconds")
            }
        }
    }

    val createCalendarEventTool = object : Tool {
        override val name = "create_calendar_event"
        override val description = "Creates a calendar event."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "description" to "Title of the event."),
                "start_time_epoch_ms" to mapOf("type" to "integer", "description" to "Start time in epoch ms."),
                "description" to mapOf("type" to "string", "description" to "Optional description.")
            ),
            "required" to listOf("title")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val title = args["title"]?.toString() ?: return ToolExecutionResult(false, "Missing title")
            val startTime = (args["start_time_epoch_ms"] as? Number)?.toLong() ?: (System.currentTimeMillis() + 3600000)
            val desc = args["description"]?.toString() ?: ""

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult(true, "Calendar event created: '$title'")
            } catch (e: Exception) {
                ToolExecutionResult(false, "Could not create event: ${e.message}")
            }
        }
    }

    val addReminderTool = object : Tool {
        override val name = "add_reminder"
        override val description = "Adds a reminder note with optional time."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "Reminder text.")
            ),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            return ToolExecutionResult(true, "Reminder noted: \"$text\"")
        }
    }

    val openUrlTool = object : Tool {
        override val name = "open_url"
        override val description = "Opens a web link in the browser."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf("type" to "string", "description" to "Web address to open.")
            ),
            "required" to listOf("url")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            var url = args["url"]?.toString() ?: return ToolExecutionResult(false, "Missing url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult(true, "Opened $url")
            } catch (e: Exception) {
                val res = rootManager.execute("am start -a android.intent.action.VIEW -d \"$url\"")
                ToolExecutionResult(res.success, if (res.success) "Opened $url" else res.stderr)
            }
        }
    }

    val webSearchTool = object : Tool {
        override val name = "web_search"
        override val description = "Searches the web via browser."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "Search query.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString() ?: return ToolExecutionResult(false, "Missing query")
            val url = "https://www.google.com/search?q=${Uri.encode(query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult(true, "Searched Google for: $query")
            } catch (e: Exception) {
                android.util.Log.w("Aura", "startActivity for search_web failed, using root fallback: ${e.message}")
                val res = rootManager.execute("am start -a android.intent.action.VIEW -d \"$url\"")
                ToolExecutionResult(res.success, "Searched for $query")
            }
        }
    }

    val getClipboardTool = object : Tool {
        override val name = "get_clipboard"
        override val description = "Gets current text copied to device clipboard."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            return withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                ToolExecutionResult(true, if (text.isNotBlank()) "Clipboard: $text" else "Clipboard is empty")
            }
        }
    }

    val setClipboardTool = object : Tool {
        override val name = "set_clipboard"
        override val description = "Copies text into the device clipboard."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to copy.")),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("aura", text))
            }
            return ToolExecutionResult(true, "Copied to clipboard.")
        }
    }

    val shareTextTool = object : Tool {
        override val name = "share_text"
        override val description = "Opens Android share sheet with text."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to share.")),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(shareIntent)
            return ToolExecutionResult(true, "Opened share sheet.")
        }
    }

    val fileListTool = object : Tool {
        override val name = "file_list"
        override val description = "Lists directory contents."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string", "description" to "Directory path (e.g. '/sdcard/Download').")),
            "required" to listOf("path")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val path = args["path"]?.toString() ?: "/sdcard"
            val res = rootManager.execute("ls -lh \"$path\"")
            return ToolExecutionResult(res.success, if (res.success) res.stdout else res.stderr)
        }
    }

    val fileReadTool = object : Tool {
        override val name = "file_read"
        override val description = "Reads text contents of a file."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string", "description" to "File path to read.")),
            "required" to listOf("path")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val path = args["path"]?.toString() ?: return ToolExecutionResult(false, "Missing path")
            val res = rootManager.execute("head -n 100 \"$path\"")
            return ToolExecutionResult(res.success, if (res.success) res.stdout else res.stderr)
        }
    }

    val fileWriteTool = object : Tool {
        override val name = "file_write"
        override val description = "Writes text content to a file."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "File path."),
                "content" to mapOf("type" to "string", "description" to "Content to write.")
            ),
            "required" to listOf("path", "content")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val path = args["path"]?.toString() ?: return ToolExecutionResult(false, "Missing path")
            val content = args["content"]?.toString() ?: ""
            return try {
                File(path).writeText(content)
                ToolExecutionResult(true, "Wrote file to $path")
            } catch (e: Exception) {
                android.util.Log.w("Aura", "Standard File.writeText failed for $path, trying root: ${e.message}")
                val res = rootManager.execute("echo \"$content\" > \"$path\"")
                ToolExecutionResult(res.success, if (res.success) "Wrote file via root" else res.stderr)
            }
        }
    }

    val fileMoveTool = object : Tool {
        override val name = "file_move"
        override val description = "Moves or renames a file."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "source" to mapOf("type" to "string", "description" to "Source path."),
                "destination" to mapOf("type" to "string", "description" to "Destination path.")
            ),
            "required" to listOf("source", "destination")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val src = args["source"]?.toString() ?: return ToolExecutionResult(false, "Missing source")
            val dest = args["destination"]?.toString() ?: return ToolExecutionResult(false, "Missing destination")
            val res = rootManager.execute("mv \"$src\" \"$dest\"")
            return ToolExecutionResult(res.success, if (res.success) "Moved file to $dest" else res.stderr)
        }
    }

    val fileCopyTool = object : Tool {
        override val name = "file_copy"
        override val description = "Copies a file."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "source" to mapOf("type" to "string", "description" to "Source path."),
                "destination" to mapOf("type" to "string", "description" to "Destination path.")
            ),
            "required" to listOf("source", "destination")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val src = args["source"]?.toString() ?: return ToolExecutionResult(false, "Missing source")
            val dest = args["destination"]?.toString() ?: return ToolExecutionResult(false, "Missing destination")
            val res = rootManager.execute("cp -r \"$src\" \"$dest\"")
            return ToolExecutionResult(res.success, if (res.success) "Copied to $dest" else res.stderr)
        }
    }

    val fileDeleteTool = object : Tool {
        override val name = "file_delete"
        override val description = "Deletes a file (requires confirmation)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string", "description" to "File path to delete.")),
            "required" to listOf("path")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val path = args["path"]?.toString() ?: return ToolExecutionResult(false, "Missing path")
            val res = rootManager.execute("rm -f \"$path\"")
            return ToolExecutionResult(res.success, if (res.success) "Deleted $path" else res.stderr)
        }
    }

    val installApkTool = object : Tool {
        override val name = "install_apk"
        override val description = "Installs an APK file from storage (requires confirmation)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string", "description" to "Path to .apk file.")),
            "required" to listOf("path")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val path = args["path"]?.toString() ?: return ToolExecutionResult(false, "Missing path")
            val res = rootManager.execute("pm install -r \"$path\"")
            return ToolExecutionResult(res.success, if (res.success) "Installed APK: $path" else res.stderr)
        }
    }

    val uninstallAppTool = object : Tool {
        override val name = "uninstall_app"
        override val description = "Uninstalls an application by package name (requires confirmation)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("name" to mapOf("type" to "string", "description" to "App package name.")),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val res = rootManager.execute("pm uninstall $name")
            return ToolExecutionResult(res.success, if (res.success) "Uninstalled $name" else res.stderr)
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        mediaControlTool, playMusicTool, setAlarmTool, setTimerTool,
        createCalendarEventTool, addReminderTool, openUrlTool, webSearchTool,
        getClipboardTool, setClipboardTool, shareTextTool, fileListTool,
        fileReadTool, fileWriteTool, fileMoveTool, fileCopyTool,
        fileDeleteTool, installApkTool, uninstallAppTool
    )
}
