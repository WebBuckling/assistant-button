# Assistant Button

**Open your assistant's floating overlay (Gemini, Claude, ChatGPT...) from any button that can launch an app.**

A tiny, invisible Android app. When launched, it asks the system to show the
**default digital assistant**, the same way a long press on Home does, and then
closes itself. So you get the assistant's floating overlay on top of the
current screen instead of the full-screen app.

- No permissions, no network, no background service, ~10 KB
- Works with whatever assistant is selected in system settings
- Survives reboots (no Shizuku, no accessibility service)

## Why

On **Honor phones with the Chinese ROM** (e.g. Magic 8 / Magic 8 Pro / Magic 8 Pro Air,
MagicOS 10–11), long-pressing Home is hardcoded to Honor's YOYO assistant. It ignores the
default assistant you pick in settings, and does nothing at all once YOYO is disabled.
The **AI Button** can only launch apps, and launching the Gemini app opens it full screen,
not as the overlay.

Assistant Button bridges that gap: bind it to the AI Button and you get the real Gemini
overlay (with "circle to ask").

It also works on any other Android phone with a key remapper, a launcher gesture, a
home-screen icon, or a quick-settings shortcut.

## Install

1. Download `assistant-button.apk` from [Releases](../../releases) and install it
   (allow installing from your browser or file manager when asked).
2. Set your assistant: **Settings → Apps → Default apps → Digital assistant app → Google**
   (or Claude, ChatGPT...).
   For Gemini, also check **Gemini app → Settings → Digital assistants from Google → Gemini**.
3. Tap the **Assistant Button** icon once to test. You should see the floating overlay.

### Bind it to the Honor AI Button

**Settings → AI Button → Short press** (or Double press) → swipe to **Quick services** →
tap the app name under it → search **"Assistant"** → choose **Assistant Button** → **Use**.

Long press can stay on the camera.

### Optional: disable YOYO (Honor, via adb)

MagicOS doesn't let you uninstall YOYO, but you can disable it:

```bash
adb shell pm disable-user --user 0 com.hihonor.magicvoice
# undo:
adb shell pm enable com.hihonor.magicvoice
```

If `adb devices` shows nothing on Windows with platform-tools 37, set `ADB_LIBUSB=1`
and restart the server (`adb kill-server`).

## How it works

`MainActivity` is a translucent activity that calls
[`Activity.showAssist()`](https://developer.android.com/reference/android/app/Activity#showAssist(android.os.Bundle))
once it has window focus, then finishes. This starts a session with the active
`VoiceInteractionService`, the same path the system uses for long-press Home. If no
assistant is set, it opens the default-assistant picker instead.

## Build from source

No Gradle needed. Requires JDK 17+ and the Android SDK (`platforms;android-36`,
`build-tools;36.0.0`).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./build.sh            # produces app.apk, signed with a local debug key
```

`extras/ai_key_short_service_info.json` is the Honor setting the AI Button picker writes
(`settings get global ai_key_short_service_info`), kept for reference.

## License

[MIT](LICENSE)
