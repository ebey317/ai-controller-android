# AI Controller (Android)

Control an Android device with an Xbox-style gamepad instead of touch —
cursor movement, taps/gestures, global navigation, an on-screen keyboard,
and push-to-talk voice dictation. This is the Android port of the Linux
`ai-controller` project; see [AGENTS.md](AGENTS.md) for how the two map to
each other module-for-module.

## How it works

The app is a single [`AccessibilityService`](app/src/main/java/com/ai/controller/ControllerAccessibilityService.kt)
(`ControllerAccessibilityService`) plus a settings UI. No root, no ADB, no
companion device — everything routes through public
`AccessibilityService` + `WindowManager` + `AudioRecord` APIs.

- **Buttons** (A/B/X/Y, D-pad, bumpers, sticks-as-buttons, Start/Select) are
  read via `onKeyEvent` and mapped to taps, long-presses, swipes, global
  actions (back/home/recents), or the voice trigger.
- **Sticks and triggers** are analog axes, which Android only delivers to a
  *focused* window (not hit-tested like touch). A tiny 1x1 focusable overlay
  (`MotionCaptureView`) exists solely to receive them; a focus watchdog
  re-claims focus if something else steals it (see bug A3 below).
- **The cursor** is a small always-on-top overlay (`CursorOverlay`) driven by
  the left stick, touch-transparent so it never blocks input to the app
  underneath (bug A1).
- **Voice dictation** is push-to-talk: hold the mapped trigger, speak,
  release. Audio is recorded locally and sent to the Groq Whisper API for
  transcription — the *only* external network call this app makes. See
  [PRIVACY.md](PRIVACY.md) for the full data-handling story and the consent
  gate in front of it.

## Install

1. Open this directory in Android Studio, or build from the CLI:
   ```
   cp docs/groq_api_key.xml.example app/src/main/res/values/groq_api_key.xml
   # edit that file and paste your real Groq API key
   ./gradlew assembleDebug
   ```
2. Install the APK, open **AI Controller**, and accept (or decline) the
   voice-dictation consent dialog.
3. Tap **Open Accessibility Settings** and enable "AI Controller Input
   Mapper".
4. Connect an Xbox-style gamepad (Bluetooth or USB-C) and toggle **Controller
   input** on.

## Using it

- **Left stick** — move the cursor. **A** — tap. **B/X** — back. **Y** —
  recents. **LB** — scroll up. **RB** — long-press. **RT** — hold to talk,
  release to transcribe and type. **View/Back** — toggle the system
  keyboard. **Start** — move accessibility focus.
- **Edit Button Mappings** (in the app) rebinds any input to any action,
  including the new `CYCLE_CONTEXT` action (see below).
- **Open Custom Keyboard** launches [`KeyboardActivity`](app/src/main/java/com/ai/controller/ui/KeyboardActivity.kt) — a
  floating on-screen QWERTY grid with a PRO/BUBBLY/CASUAL/BOLD/BIG text-style
  toggle and 5 pinned-snippet slots, the Android analogue of
  `slide_keyboard.py`.
- **Contexts**: bind `CYCLE_CONTEXT` to a button to cycle desktop → browser →
  iptv button-mapping presets (`ContextSwitcher`), each persisted
  independently so customizing one never touches another.

## Project layout

| File | Purpose |
|---|---|
| `ControllerAccessibilityService.kt` | Input → gesture/action dispatch, voice PTT, lifecycle |
| `CursorOverlay.kt` | Touch-transparent on-screen cursor |
| `LegendOverlay.kt` | Small HUD bubble showing the active button legend |
| `InputMapper.kt` | Pure KeyEvent/MotionEvent → `ControllerInput`/`ButtonAction` translation |
| `ProfileManager.kt` / `ProfileSerializer.kt` | Persisted, user-editable button mapping |
| `ContextSwitcher.kt` | desktop/browser/iptv profile presets |
| `PttController.kt` | Push-to-talk press/release edge state machine |
| `TextStyles.kt` | PRO/BUBBLY/CASUAL/BOLD/BIG Unicode transforms |
| `VoiceManager.kt` | On-device TTS voice packs |
| `VoiceBridgeServer.kt` | Loopback-only `/voice` + `/speak` HTTP endpoint |
| `DriftCalibrator.kt` | Analog-stick drift compensation |
| `ConsentManager.kt` | Persisted Groq-consent flag |
| `ui/KeyboardActivity.kt` | Floating on-screen keyboard |
| `ui/SettingsActivity.kt` | Per-input button mapping editor |

## Building

```
./gradlew compileDebugKotlin   # type-check
./gradlew assembleDebug        # build an installable APK
```

Requires the Android SDK (compileSdk 35, minSdk 26) and a
`app/src/main/res/values/groq_api_key.xml` (gitignored — see above).
