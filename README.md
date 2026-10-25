# AI Controller — Android

Android companion to the desktop [AI Controller](https://github.com/ebey317/ai-controller) project. Control your Android device with an Xbox controller via a registered accessibility service.

## Status

Functional. Builds in CI and produces signed APK releases through GitHub Actions.

## Features

- **Accessibility service** — `ControllerAccessibilityService` receives controller events and performs actions on your behalf.
- **Cursor overlay** — on-screen cursor controlled by the left stick.
- **Input mapping** — map buttons to taps, swipes, and key events.
- **Controller profiles** — switch between gamepad-to-Android layouts.
- **Settings activity** — configure bindings and service preferences.
- **Boot receiver** — optionally start the service at device boot.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/ai/controller/
│   ├── ControllerAccessibilityService.kt   # main accessibility bridge
│   ├── MainActivity.kt                       # launcher UI
│   ├── SettingsActivity.kt                   # binding settings
│   ├── BootReceiver.kt                       # autostart at boot
│   ├── CursorOverlay.kt                      # floating cursor
│   ├── InputMapper.kt                        # button → action mapping
│   ├── ProfileManager.kt                     # profile load/save
│   └── ui/
│       ├── ButtonMappingAdapter.kt
│       └── ...
└── res/
    ├── layout/             # Activities and list items
    ├── xml/
    │   └── accessibility_service_config.xml
    └── values/
```

## Requirements

- Android 8.0+ (API 26+)
- Xbox controller connected via Bluetooth or USB-OTG
- Accessibility service permission enabled by the user after install

## Build

```bash
./gradlew assembleDebug
```

Install the resulting APK from `app/build/outputs/apk/debug/`.

## CI / Release

- `.github/workflows/android-build.yml` — builds and lints on every push.
- `.github/workflows/android-release.yml` — creates a GitHub Release with a signed APK when triggered.

## Permissions

The app uses Android accessibility APIs legitimately to translate controller input into UI actions. No remote services or analytics are included. A local Groq API key stub is created at build time only; the real key is gitignored and supplied by the user.

## License

MIT — see [LICENSE](LICENSE).

## Author

[Elijah Wilkins](https://github.com/ebey317) — companion to the Linux [AI Controller](https://github.com/ebey317/ai-controller).
