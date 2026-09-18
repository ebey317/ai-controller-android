# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this repo is

`ai-controller-android` is the Android port of the Linux `ai-controller`
project (source of truth lives outside this repo, at
`ai-controller/` — **never edit that copy from here**; this repo is the
Android-native reimplementation of its shape, not a wrapper around it).

The goal is *module-for-module parity*, translated to Android idioms, not a
literal port:

| Linux module | Android equivalent | Notes |
|---|---|---|
| `voice_bridge.py` (FastAPI `/voice`, `/speak`) | `VoiceBridgeServer.kt` | Raw `java.net.ServerSocket`, not a web framework — see constraints below |
| `voice_manager.py` + Piper/Edge-TTS pair | `VoiceManager.kt` | Two presets over Android's on-device `TextToSpeech`, no network TTS |
| `text_styles.py` / `ptt_pynput.py`'s `_transform_text` | `TextStyles.kt` | Same 5 modes (PRO/BUBBLY/CASUAL/BOLD/BIG), same Unicode blocks |
| `ptt_pynput.py`'s F13 on_press/on_release | `PttController.kt` | Press/release edge state machine, same debounce intent |
| `controller-legend.py` | `LegendOverlay.kt` | Simplified to a single text bubble, not a paginated HUD |
| `slide_keyboard.py` | `ui/KeyboardActivity.kt` | Activity, not an IME; 5 pin slots instead of 7 |
| `settle_wiggle.sh` | `DriftCalibrator.kt` | Expressed as input-math bias correction; there's no X11 mouse to nudge |
| AntiMicroX desktop/browser/iptv profiles | `ContextSwitcher.kt` | Full `ControllerProfile` per context, persisted independently |

When a Linux module's approach genuinely cannot translate (e.g. X11-specific
focus tricks, `xdotool`, `pynput` evdev grabs), don't force it — document the
Android-native substitute and why, the way `ControllerProfile.default()`
already documents its desktop-mapping translation choices.

## Hard constraints

- **First-party Android APIs only.** No new third-party libraries beyond
  what's already in `app/build.gradle.kts`
  (`androidx.core`, `androidx.appcompat`, `androidx.recyclerview`,
  `kotlinx-coroutines-android`). If a task suggests a library (e.g. "use
  Ktor"), prefer the first-party equivalent (`java.net.ServerSocket`,
  `android.speech.tts.TextToSpeech`) instead — see `VoiceBridgeServer.kt`
  and `VoiceManager.kt` for the pattern.
- **No network endpoint besides the Groq Whisper API**
  (`https://api.groq.com/openai/v1/audio/transcriptions`). Nothing else may
  make an outbound network call — no analytics, no other STT/TTS/LLM
  provider, no telemetry.
- **All app Kotlin code lives under**
  `app/src/main/java/com/ai/controller/`.
- Use Android's accessibility framework, `WindowManager`, `AudioRecord`, and
  `MediaPlayer`/`TextToSpeech` — not root, not a companion daemon, not
  `INJECT_EVENTS` (a standard `AccessibilityService` doesn't have it; text
  injection goes through `AccessibilityNodeInfo.ACTION_SET_TEXT`).

## Known-fixed bugs (don't reintroduce these)

- **A1** — `CursorOverlay` must stay touch-transparent
  (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`); see `setTouchTransparent()`.
- **A2** — voice recording runs on a coroutine with a sized `AudioRecord`
  buffer and a real `Job.cancel()` path (`stopVoiceRecording(cancel = true)`
  via `PttController.onCancel`), plus a `MAX_RECORDING_MS` safety cutoff.
  Don't go back to a raw `Thread` + unbounded busy-read loop.
- **A3** — `MotionCaptureView` (the 1x1 joystick-capture overlay) must stay
  *focusable* — Android routes `SOURCE_CLASS_JOYSTICK` motion events to the
  focused window only, not by hit-testing. `FLAG_NOT_FOCUSABLE` here would
  silently kill all analog stick/trigger input. The actual A3 fix is the
  focus-loss watchdog (`startFocusWatchdog()`) plus
  `MotionCaptureView.onWindowFocusChanged` re-requesting focus.
- **A4** — nothing reaches Groq without `ConsentManager.isGranted()` being
  true first. Both the PTT path and the local `/voice` bridge check it.
- **A5** — every coroutine the service launches (trigger-repeat jobs, the
  legend tick, the focus watchdog, voice recording) is a child of
  `serviceScope`, cancelled in `teardown()`/`onDestroy()`. Don't reach for a
  bare `Handler.postDelayed` loop for new recurring work — it won't be
  cancelled by `serviceScope.cancel()`.

## Verifying changes

```
cd app/.. # repo root
echo "sdk.dir=/path/to/Android/Sdk" > local.properties  # if not already set
./gradlew compileDebugKotlin
```

Must finish with no errors before committing. There is no test suite in this
port yet; if you add non-trivial logic (especially in `TextStyles`,
`DriftCalibrator`, `InputMapper`, or `PttController` — the pure/testable
pieces), prefer adding a small JUnit test over hand-verifying.

## Secrets

`app/src/main/res/values/groq_api_key.xml` is gitignored. Never commit a real
key. `docs/groq_api_key.xml.example` is the template new contributors copy.
