# Assistant Button

**Open Circle to Search, with the real screen, from any button that can launch an app.
No PC, no root, no Shizuku app.**

Made for **Honor phones with the Chinese ROM** (tested on a Magic 8 Pro Air, MagicOS 11 /
Android 17). It should work on other Android 11+ phones that have the Google app.

## The problem

On Chinese-ROM Honor phones:

- Long-press Home is hardcoded to Honor's YOYO assistant. It ignores the default
  assistant you pick in settings, and does nothing once YOYO is disabled.
- The **AI Button** can only launch apps.
- When an app opens the assistant (`Activity.showAssist()`), Android gives the assistant
  only that app's own content. Gemini's own screen capture comes out **black** on this ROM.

So Gemini opens, but it can't see your screen.

## The fix

Android does give the screenshot to the assistant when the session is shown with the
`SHOW_WITH_SCREENSHOT` flags, but only callers with shell (adb) privileges can pass them.
Assistant Button gets those privileges the same way Shizuku does: it pairs with the phone's
own **Wireless debugging** and talks to `adbd` over localhost. On each press it runs:

```
service call voiceinteraction 3 null <Bundle{omni.entry_point=1}> 7 <tag>
```

That is `IVoiceInteractionManagerService.showSessionFromSession`, and it opens Google's
**Circle to Search** with a real screenshot. MiCTS does the same thing through Shizuku.

If Wireless debugging is unavailable (no Wi-Fi, not paired), the button falls back to the
assistant's floating overlay, which can't see the screen.

## Setup (one time, needs Wi-Fi)

1. Install `assistant-button.apk` from [Releases](../../releases).
2. Make the Google app the default assistant:
   **Settings → Apps → Default apps → Digital assistant app → Google**.
3. Open **Assistant Button setup** (the gear icon) and follow it:
   - Developer options → turn on **Wireless debugging** (tap *Always allow* on this network).
   - Tap **Start pairing**, then in Wireless debugging tap **Pair device with pairing code**.
   - Without closing that dialog, pull down the notification shade. In the Assistant Button
     notification tap **Enter code** and type the 6-digit code.
   - The notification changes to *Paired!*.
4. Bind **Assistant Button** to your button.
   Honor: **Settings → AI Button → Short press → Quick services → (app) → search "Assistant"
   → Assistant Button → Use**.

During pairing the app grants itself `WRITE_SECURE_SETTINGS` over ADB. After a reboot it
uses that to switch Wireless debugging back on, as long as you are on a Wi-Fi network you
allowed before.

### Optional: disable YOYO (via adb from a PC)

```bash
adb shell pm disable-user --user 0 com.hihonor.magicvoice   # undo: pm enable
```

On Windows with platform-tools 37, if `adb devices` is empty, set `ADB_LIBUSB=1` and
restart the server.

## Security

Wireless debugging gives shell-level access. This app uses it only to open Circle to Search
and to grant itself `WRITE_SECURE_SETTINGS`. Its ADB key is stored in the app's private
storage. To revoke access: Wireless debugging → paired devices → forget *AssistantButton*,
or turn Wireless debugging off.

## Build

JDK 17 and the Android SDK (platform 36):

```bash
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Assistant Button"
./gradlew assembleDebug
```

`extras/ai_key_short_service_info.json` is the Honor setting the AI Button picker writes,
kept for reference.

## Credits

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) by Muntashir Al-Islam
  (Apache-2.0 / GPL-3.0): the on-device ADB client and pairing.
- [Shizuku](https://github.com/RikkaApps/Shizuku) and [MiCTS](https://github.com/parallelcc/MiCTS)
  for showing the approach.

## License

[MIT](LICENSE)
