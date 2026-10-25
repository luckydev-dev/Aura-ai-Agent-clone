# Aura — Root-Powered Realtime Voice Agent for Android

Aura is a native Android AI voice agent powered by the **Gemini Live API** (bidirectional streaming over WebSocket). It provides continuous, low-latency voice conversation with barge-in (interruption) capability, and actively automates phone actions as root: opening apps, sending WhatsApp messages, making calls, controlling device hardware, reading the screen, clicking and typing in any app, taking screenshots, and executing shell commands.

---

## Features

1. **Realtime Voice Engine**:
   - Bidirectional audio streaming over WebSocket via `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY`.
   - Native audio playback (24 kHz PCM16) and capture (16 kHz PCM16 with AEC, NS, and AGC).
   - Barge-in support: Speak to interrupt Aura mid-sentence with instantaneous buffer flush.
   - Live captions showing both model transcriptions and user speech.
   - Configurable voice models (`Aoede`, `Puck`, `Charon`, `Kore`, `Fenrir`, `Leda`, `Orus`, `Zephyr`).
   - Push-to-talk, continuous conversation, and background wake phrase detector ("Hey Aura").

2. **Root & Device Automation**:
   - Persistent root shell powered by `libsu` (`com.github.topjohnwu.libsu:core`).
   - One-tap **Setup Wizard** that auto-grants all runtime permissions, whitelists battery optimization, enables the Accessibility Service, and allows draw-over-apps / secure settings.
   - Safe command execution layer with hard blocklist against catastrophic commands (`rm -rf /`, `/system` / `/data` wipes, block device writes) and confirmation prompts for risky actions (`reboot`, `shutdown`, `pm uninstall`).

3. **Complete Tool Ecosystem (Function Calling)**:
   - **Apps & Navigation**: `open_app`, `close_app`, `clear_app_data`, `clear_app_cache`, `list_installed_apps`, `get_foreground_app`, `go_home`, `go_back`, `open_recents`, `open_notifications`, `open_quick_settings`, `lock_screen`, `wake_screen`.
   - **Communication**: `send_whatsapp` (with auto-send button detection, WhatsApp Business support), `send_sms`, `make_call`, `answer_call`, `end_call`, `read_sms`, `read_call_log`, `read_notifications`, `reply_to_notification`, `dismiss_notification`, `search_contacts`.
   - **Device Control**: `set_wifi`, `set_bluetooth`, `set_mobile_data`, `set_airplane_mode`, `set_flashlight`, `set_hotspot`, `set_location`, `set_do_not_disturb`, `set_auto_rotate`, `set_dark_mode`, `set_brightness`, `set_volume`, `set_ringer_mode`, `get_battery`, `get_storage`, `get_ram`, `get_network_info`, `get_device_info`, `take_screenshot`, `record_screen`, `reboot`, `shutdown`.
   - **Screen Automation**: `read_screen` (compact UI hierarchy dump), `tap_element`, `tap_xy`, `long_press`, `swipe`, `scroll`, `type_text` (handles unicode and Hindi text), `press_key`, `wait`, `find_on_screen`, `run_task` (autonomous multi-step loop).
   - **Media & Utilities**: `media_control`, `play_music` (Spotify / YouTube Music), `set_alarm`, `set_timer`, `create_calendar_event`, `add_reminder`, `open_url`, `web_search`, `get_clipboard`, `set_clipboard`, `share_text`, file operations (`file_list`, `file_read`, `file_write`, `file_move`, `file_copy`, `file_delete`), `install_apk`, `uninstall_app`.
   - **Power**: `run_shell` (with safety verification).

4. **Custom Design System**:
   - Distinctive typography pairing: **Sora** for headings, **Inter** for body text, and **JetBrains Mono** for command outputs.
   - Dark-first aesthetic with deep black background (`#0B0B0F`), sleek surfaces (`#14141B`), and signature gradient accents (`#FFB36B` -> `#FF6B8B` -> `#8B6BFF`).
   - **Aura Living Orb**: Organic Canvas gradient blob reflecting states: Idle, Listening, Thinking, Speaking, Acting, Error, Reconnecting.
   - Floating draggable mini orb overlay (`SYSTEM_ALERT_WINDOW`) for background control.
   - Quick Settings tile and Home Screen widget.

5. **Local Memory (Room Database)**:
   - Action logs, conversation history, and user aliases stored strictly on-device.
   - Summaries injected into the Live session system prompt on connection.

---

## Building the Project

### Option A: Local Build (Android Studio or Gradle CLI)

1. **Prerequisites**:
   - JDK 17 or higher
   - Android SDK (compileSdk 36, minSdk 26)
   - Rooted device (Magisk, KernelSU, or APatch) with USB debugging enabled.

2. **Configure API Key**:
   - Copy `.env.example` to `.env`:
     ```bash
     cp .env.example .env
     ```
   - Add your Gemini API key in `.env`:
     ```properties
     GEMINI_API_KEY=AIzaSy...
     ```
   - Alternatively, enter your key directly in the Aura app settings screen.

3. **Build the Debug APK**:
   ```bash
   gradle assembleDebug
   ```
   The generated APK will be at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. **Install to Rooted Device**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B: Cloud Build (GitHub Actions)

A pre-configured GitHub Actions workflow is provided at `.github/workflows/build.yml`.
1. Push this repository to GitHub (`main` or `master` branch).
2. Go to **Actions** in your GitHub repository.
3. The `Build Aura APK` workflow will build and publish the debug APK as a downloadable artifact.

---

## How to Get a Gemini API Key

1. Visit [Google AI Studio](https://aistudio.google.com/).
2. Sign in with your Google account.
3. Click **Get API key** and create a key in a new or existing Google Cloud project.
4. Copy the API key and paste it into Aura Settings under **Gemini API Key**.

---

## First-Run Walkthrough

1. **Launch Aura**: Open Aura on your rooted device.
2. **Onboarding**: Read through the three feature overview slides or tap **Skip**.
3. **Setup Wizard**:
   - Tap **Grant Everything (Root)**.
   - Magisk / KernelSU / APatch will display a superuser request prompt. Tap **Grant / Always Allow**.
   - Aura will automatically grant runtime permissions (`RECORD_AUDIO`, `READ_CONTACTS`, etc.), enable the Accessibility Service, grant Notification Access, enable `WRITE_SECURE_SETTINGS`, and exempt the app from battery optimizations.
   - All checkmarks will turn green (verified). Tap **Continue**.
4. **Enter API Key**: Tap the Settings gear icon in the top right, enter your Gemini API key, and tap **Test Connection**.
5. **Start Talking**: Tap the central Aura Orb or the call button. Speak naturally:
   - *"Turn off Wi-Fi and turn on Bluetooth"*
   - *"Open WhatsApp and message Rahul 'I'm coming in 10 minutes'"*
   - *"What's on my screen right now?"*
   - *"Take a screenshot"*
   - *"Set brightness to 40 percent and volume to half"*

---

## Renaming the App

To rename the app:
1. Open `app/src/main/res/values/strings.xml` and update `<string name="app_name">Aura</string>`.
2. Open `settings.gradle.kts` and update `rootProject.name = "Aura"`.
3. Open `metadata.json` and update `"name": "Aura"`.

---

## Troubleshooting

- **Root Access Denied**:
  - Ensure Magisk or KernelSU is installed and active.
  - Open Magisk / KernelSU app and verify `Aura` is listed in Superuser permissions with root access granted.
  - In Aura, tap **Re-run Setup Wizard & Root Checks** in Settings.

- **Accessibility Service Not Enabling**:
  - Run the following root command manually via terminal or adb:
    ```bash
    su -c "settings put secure enabled_accessibility_services com.aistudio.aura.vceagt/com.example.service.AuraAccessibilityService && settings put secure accessibility_enabled 1"
    ```

- **Audio Echo**:
  - Aura uses `AcousticEchoCanceler` and `AudioRecord` with `VOICE_COMMUNICATION` mode. If hardware AEC is unavailable on your custom ROM, use headphones or lower speaker volume.

- **Live API Connection Errors**:
  - Verify your Gemini API key has access to the Gemini 2.0 / 2.5 Live models.
  - Ensure the device has an active internet connection without restrictive firewall blocking WebSockets (`wss://`).
