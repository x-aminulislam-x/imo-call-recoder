# Call Recorder (acoustic)

Records IMO (and other VoIP/cellular) calls on **stock, unrooted Android** by
capturing acoustically: your voice goes into the mic directly, the far end goes
into the mic out of the loudspeaker.

This is the only approach that works without root. It is also the approach that
fails silently on some phones, so the app is built around telling you fast when
that happens.

## Why it is built this way

| Decision | Reason |
|---|---|
| Audio source `UNPROCESSED` → `VOICE_RECOGNITION` → `MIC` | Never `VOICE_COMMUNICATION`: that source turns on the acoustic echo canceller, whose job is to remove loudspeaker output from the mic — i.e. exactly the remote party's voice we are trying to capture. |
| Raw `AudioRecord` + `MediaCodec`, not `MediaRecorder` | We need the PCM buffers to measure level. Android does not tell you it has muted your capture; `read()` keeps returning success with zero-filled buffers. Metering is the only way to detect it. |
| Detection via `AudioManager.getMode()` | `MODE_IN_COMMUNICATION` is what every VoIP app sets. No telephony permissions, no accessibility service, works for IMO/WhatsApp/Signal alike. |
| `NotificationListenerService` for the IMO filter | The audio mode says *a* call is happening, not *which app*. IMO's ongoing call notification supplies that. Optional — without it the app records all calls. |
| `targetSdk 33`, not 34 | Android 14 adds foreground-service-type restrictions that block a mic-type service in several paths this app needs. Sideloaded app, no Play policy to satisfy. |
| Forced speakerphone (opt-in, on by default) | Acoustic capture needs the far end audible in the room. Best-effort: uses `setCommunicationDevice` on API 31+, `isSpeakerphoneOn` below. Some OEM builds and IMO itself may override it. |

## Build

Needs Android Studio (Hedgehog or newer) or a JDK 17 + Android SDK 34 + Gradle 8.7+.

```bash
cd imo-call-recorder && ./gradlew assembleDebug
```

There is no Gradle wrapper JAR in this folder. Either open the project in Android
Studio (it generates one on first sync), or run `gradle wrapper` once with a
local Gradle install.

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Setup on each phone

1. Install the APK (enable "install unknown apps" for your file manager).
2. Open the app, tap **Permissions** → allow microphone and notifications.
3. Tap **Battery** → set the app to unrestricted. Skipping this is the most
   common cause of the watcher dying after a few hours.
4. Optional: tap **Notif. access** and enable the app, then tick
   **Record IMO calls only**.
5. Tap **Start watching**. Leave it running — the persistent notification is
   what keeps the microphone grant alive.

## The one test that matters — do this first

Do not trust it until you have run this on **each** phone:

1. Start watching.
2. Make a short IMO call to someone and put it on speakerphone.
3. Watch the app's status line or the notification. It shows a live level in
   dBFS.
   - **Numbers moving around -50 to -20 dBFS** → capture works.
   - **Stuck at -120, and an alert fires after 12 seconds** → this phone mutes
     background capture during calls. There is no software fix on a stock ROM.
4. Hang up, open the app, tap the recording, play it back. Confirm you can hear
   **both** voices, not just yours.

If step 3 alerts, this phone needs root or an external recorder. The app tells
you rather than letting you find out after an important call.

## Known limits

- **Quality is acoustic, not line-level.** Speakerphone in a quiet room is
  intelligible; a noisy street is not. The far end is always quieter than you.
- **Speakerphone is effectively required.** Held to the ear, the earpiece is too
  quiet for the mic to pick up the other party.
- **After a reboot, open the app once.** Android 14+ forbids mic-type foreground
  services from starting at boot; `BootReceiver` tries and gives up quietly.
- **Not Play Store distributable.** Google's policy has banned call recording
  since May 2022. Sideload only.
- **Recording consent is your responsibility.** Many jurisdictions require every
  party to consent, not just you. Check what applies where you and the people you
  call are located.

## Files

| File | Role |
|---|---|
| `AacRecorder.kt` | Mic → PCM → AAC/m4a, with RMS metering |
| `CallDetector.kt` | Polls audio mode for call start/end |
| `CallNotificationListener.kt` | Identifies IMO calls specifically |
| `RecorderService.kt` | Foreground watcher, routing, silence alarm |
| `MainActivity.kt` | Setup checklist, controls, recording list |
| `BootReceiver.kt` | Best-effort restart after reboot |
