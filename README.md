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
   ./gradlew assembleDebug
   ```
   This builds with the placeholder Groq key (`YOUR_GROQ_API_KEY`) baked into
   the resource — voice dictation stays disabled until you enter a real key
   at runtime in step 2. **Do not** paste a real key into
   `app/src/main/res/values/groq_api_key.xml` (or a `groq_api_key.xml`
   override): any value placed in that resource is compiled directly into
   the APK and ships inside it with any distributed build.
2. Install the APK, open **AI Controller**, and accept (or decline) the
   voice-dictation consent dialog. Enter your Groq API key in the **Groq API
   key** field and tap **Save Key** — this stores it in the app's own private
   on-device storage, never in the build.
3. Tap **Open Accessibility Settings** and enable "AI Controller Input
   Mapper".
4. Connect an Xbox-style gamepad (Bluetooth or USB-C) and toggle **Controller
   input** on.

## Using it

- **Left stick** — move the cursor. **A** — tap. **B** — backspace (hold
  500ms to delete to line start). **X** — forward delete. **Y** — recents.
  **LB** — tap (label "Shift"). **RB** — long-press (label "R·Clk"). **LT** —
  tap (label "Ctrl"). **RT** — hold to talk, release to transcribe and type.
  **Select/View (⧉)** — toggle the floating on-screen keyboard. **Start** —
  move accessibility focus. **Right-stick click (RS)** — Enter/submit in the
  focused field.
- **D-pad / HAT left/right** — while the floating keyboard is open and not
  being dragged, moves the text caret in the focused field by ±1 character
  (instead of nudging the mouse cursor).
- **Edit Button Mappings** (in the app) rebinds any input to any action,
  including the new `CYCLE_CONTEXT` action (see below).
- **Open Custom Keyboard** (in the app) launches [`KeyboardActivity`](app/src/main/java/com/ai/controller/ui/KeyboardActivity.kt) — a
  full-screen diagnostic keyboard. The primary on-screen keyboard is the
  floating [`FloatingKeyboardOverlay`](app/src/main/java/com/ai/controller/FloatingKeyboardOverlay.kt),
  toggled by the **Select/View (⧉)** button on the controller — a
  WindowManager overlay with QWERTY grid, PRO/BUBBLY/CASUAL/BOLD/BIG text-style
  toggle, 5 pinned-snippet slots, font-size A+/A- controls, skin-tone picker,
  voice button, and punctuation row, the Android analogue of
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
| `FloatingKeyboardOverlay.kt` | Floating on-screen keyboard (WindowManager overlay) |
| `InputMapper.kt` | Pure KeyEvent/MotionEvent → `ControllerInput`/`ButtonAction` translation |
| `ProfileManager.kt` / `ProfileSerializer.kt` | Persisted, user-editable button mapping |
| `ContextSwitcher.kt` | desktop/browser/iptv profile presets |
| `PttController.kt` | Push-to-talk press/release edge state machine |
| `TextStyles.kt` | PRO/BUBBLY/CASUAL/BOLD/BIG Unicode transforms |
| `VoiceManager.kt` | On-device TTS voice packs |
| `VoiceBridgeServer.kt` | Loopback-only `/voice` + `/speak` HTTP endpoint |
| `DriftCalibrator.kt` | Analog-stick drift compensation |
| `ConsentManager.kt` | Persisted Groq-consent flag |
| `ui/KeyboardActivity.kt` | Full-screen diagnostic keyboard (legacy) |
| `ui/SettingsActivity.kt` | Per-input button mapping editor |

## Building

```
./gradlew compileDebugKotlin   # type-check
./gradlew assembleDebug        # build an installable APK
```

Requires the Android SDK (compileSdk 35, minSdk 26). No `groq_api_key.xml`
edit is needed or recommended — enter the Groq key at runtime instead (see
Install, above).

## Known limitations

**System IME integration is unreliable.** `AIInputMethodService` (a real
system IME) was implemented but live-tested 2026-09-18 and found not to
reliably surface its input view — `switchToInputMethod()` and
`setShowMode(AUTO)` report success but `dumpsys input_method`'s `mInputShown`
stays `false`. The working keyboard is `FloatingKeyboardOverlay`, a
WindowManager overlay that never depends on IME arbitration.

**D-pad on some controllers reports as HAT axes, not key events.**
The default profile maps `DPAD_UP/DOWN/LEFT/RIGHT` as `KEY_EVENT`, but on
controllers where the D-pad emits `AXIS_HAT_X/Y` motion events instead,
those bindings do nothing until remapped in **Edit Button Mappings** (or the
profile updated to include hat-axis mappings).
