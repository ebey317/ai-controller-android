# Privacy & consent

AI Controller is built to touch the network as little as possible. This
document describes exactly what leaves the device, when, and how you control
it.

## What data is collected

**Microphone audio, and only while you are holding the voice-trigger
button.** The app does not listen in the background, does not keep a rolling
buffer, and does not record anything outside an explicit press-and-hold.

## Where it goes

Recorded audio is sent, as a WAV file over HTTPS, to the **Groq Whisper API**
(`api.groq.com`) for speech-to-text transcription. That is the *only*
external network endpoint this app talks to — see the `CONSTRAINTS` in
[AGENTS.md](AGENTS.md). No analytics, no crash reporting, no advertising SDK,
no other transcription/LLM provider.

The returned transcript is typed into whatever field is currently focused on
your device, locally, via the Android Accessibility API. It is not stored by
this app beyond the single request. Groq's own retention policy governs what
happens to the request on their side (see Groq's privacy policy at
groq.com).

## The consent gate

The very first time you open the app, or any time consent has not been
granted, you see a dialog:

> "When you hold the voice-trigger button, AI Controller records audio from
> your microphone and sends it to the Groq Whisper API (api.groq.com) over
> the internet to be transcribed into text. No other network service is
> contacted, and nothing is recorded unless you are actively holding the
> trigger. Allow this?"

- **Allow** persists a `consent=true` flag (`ConsentManager`, backed by a
  dedicated SharedPreferences file) and unblocks the voice trigger.
- **Not now** leaves consent unset (`false`). The voice trigger stays fully
  inert: `ControllerAccessibilityService` checks `ConsentManager.isGranted()`
  before it opens the microphone *or* the local `/voice` bridge endpoint
  transcribes anything — if consent is not granted, no `AudioRecord` is ever
  created and no bytes are ever sent to Groq.

You can revisit this choice at any time by clearing the app's storage or
(in a future settings entry) a dedicated toggle; until then, reinstalling or
clearing app data re-shows the dialog.

## What never leaves the device

- Button mappings, profile contexts, voice-style preferences, pinned
  snippets, and the active TTS voice pack — all local SharedPreferences.
- Text-to-speech playback uses Android's on-device `TextToSpeech` engine
  (`VoiceManager`), not a network TTS call.
- The local `/voice` and `/speak` HTTP endpoints (`VoiceBridgeServer`) are
  bound to `127.0.0.1` only and never accept a connection that didn't
  originate on-device.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capture the push-to-talk clip sent to Groq |
| `INTERNET` | The single HTTPS call to `api.groq.com`, plus the loopback voice-bridge socket |
| `BIND_ACCESSIBILITY_SERVICE` | Read gamepad input and dispatch gestures/text |
| `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED` | Keep the accessibility service reliable across the session |
