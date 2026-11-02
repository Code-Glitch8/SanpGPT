# SnapGPT Android

Native AI floating screen assistant for JEE Main / JEE Advanced students. A small draggable circle floats over other apps; tap it to scan the screen (or a selected area), get **concise answers** next to every detected question, then tap an answer for a **detailed JEE-level explanation**.

Kotlin · Jetpack Compose (Material 3) · WindowManager overlays · MediaProjection · Foreground Service · Coroutines · DataStore · Room · Android Keystore · Groq API. Not a WebView app.

> **Honest build status** — read this first
> - Unit tests cover the pure logic (Groq client, Stage 1 JSON validation, prompts, coordinate mapping, answer placement and token accounting). Groq client tests use an injected in-memory HTTP transport, so they do not require a JDK-only HTTP server, live network access or an API key. Run `./gradlew test` to execute them.
> - The Android layers (Compose UI, overlay windows, MediaProjection capture, Room, Keystore, foreground service) and the Gradle/AGP build **have not been compiled or run** by the author: the authoring environment had no Android SDK and could not reach Maven/Google/Gradle servers.
> - **No APK has been built and none is included.** The GitHub Actions workflow has been validated for structure only; a live run is still pending. The first run on GitHub may reveal compile errors to fix; see [Troubleshooting](#troubleshooting).
> - No live Groq request and no on-device test (overlay, capture, permissions, rotation) has been performed. Treat everything on-device as untested until you try it.

## Features
- Floating 52 dp circle (size/opacity configurable), drag anywhere, 100 % opacity + slight scale while touched, fades to ~30 % when idle, edge snapping, remembered position (survives rotation), keeps clear of system bars.
- Compact menu: **Scan entire screen**, **Select question area**, **Hide assistant**, **Settings** (plus **Clear answers** when answers are showing).
- Screen capture with Android's MediaProjection consent. Full-screen scan, or dim-and-drag area selection with live pixel size, Confirm / Cancel; only the selected region is sent.
- **Two-stage AI**
  - *Stage 1*: one image request -> structured JSON (question text, subject/chapter/topic, type, options, concise answer, confidence, normalised bounding box). No explanations.
  - *Stage 2*: only when you tap an answer bubble -> detailed explanation of that single question (optionally with a small crop of it), then follow-ups, copy and regenerate.
- Answer bubbles placed beside each question from normalised boxes, never covering question text, no overlaps, round for "B"/"42", pill for "9.8 m/s²", dismiss one or clear all.
- JEE modes: Automatic / JEE Main / JEE Advanced, syllabus restriction (default on), chapter/topic display (on), alternative methods (off). Used in both stages.
- Token saving and tracking (actual vs estimated), history with thumbnails, Settings, Light/Dark/System theme.

## Requirements
- Android 8.0+ (API 26). Compiles against SDK 35 (Android 15).
- A Groq API key: <https://console.groq.com/keys>
- To build locally: JDK 17 and Android SDK 35 (Android Studio Ladybug or newer is easiest).

## Repository structure
```
SnapGPT-Android/
├── .github/workflows/build-android.yml     # tests + debug APK artifact
├── .github/workflows/release-android.yml   # optional signed release (manual)
├── app/src/main/java/com/snapgpt/
│   ├── MainActivity.kt, SnapGptApp.kt      # entry, manual DI container
│   ├── ui/        Compose screens, theme, view models
│   ├── overlay/   floating circle, menu, selection, answer bubbles, explanation panel, placement maths
│   ├── capture/   MediaProjection capture, consent activity, JPEG encoding, image sizing
│   ├── ai/        Groq client, prompts, Stage 1 parser, AiService, usage accounting
│   ├── jee/       exam modes and JEE instruction builder
│   ├── history/   session models, history repository
│   ├── settings/  DataStore settings, Keystore key storage, usage store
│   ├── database/  Room entities and DAO
│   └── services/  foreground OverlayService, ScanCoordinator, notification
├── app/src/test/  unit tests
├── gradle/wrapper/ gradlew gradlew.bat
└── build.gradle.kts settings.gradle.kts gradle/libs.versions.toml
```

## Install (use the app)
1. Install the APK (see below) and open **SnapGPT**.
2. **Settings > AI**: paste your Groq key, *Save key*, then *Test connection* (should say Connected).
3. Home > **Start assistant**. Grant, when asked:
   - *Draw over other apps* (system settings page opens; switch SnapGPT on, return, press Start again).
   - *Notifications* (Android 13+; optional, shows a Stop button).
   - *Screen capture*: in Android's dialog choose to share the **entire screen** (on Android 14+ SnapGPT requests entire-screen only).
4. The circle appears. Tap it -> **Scan entire screen** or **Select question area**.
5. Answers appear beside the questions. Tap one for the explanation panel (drag the header to move, – to collapse, type a follow-up, Copy answer / Copy steps / Regenerate, ✕ to close).
6. Stop any time with the notification's **Stop** button or **Stop assistant** in the app.

## Groq API setup
- Endpoint: `https://api.groq.com/openai/v1/chat/completions`. Default model: `qwen/qwen3.8-27b`.
- Checked against <https://console.groq.com/docs/vision>: this model accepts image input and JSON mode, up to 3 images per request, about 2,048 input tokens per image, 20 MB request limit. Model availability changes; SnapGPT **never switches models silently** and shows a clear error if the model is unavailable. You can type another vision-capable model id in Settings.
- Requests send images as base64 JPEG data URLs, `response_format: json_object` for Stage 1, `reasoning_format: hidden`, a completion budget that leaves room for reasoning, 15 s connect / 120 s read timeouts. HTTP 429 is never retried automatically; SnapGPT waits for `retry-after`.
- **Key security:** the key is AES-GCM encrypted with an Android Keystore key and never written to logs. A key inside any mobile app can still be extracted by someone controlling the device or process. Use a dedicated key you can revoke. For a public release, put a backend proxy in front of Groq: construct `GroqClient(baseUrl = "https://your-proxy/openai/v1")` in `SnapGptApp.kt` and move the key to the server.

## Required permissions
| Permission | Why |
|---|---|
| `INTERNET` | Call Groq. |
| `SYSTEM_ALERT_WINDOW` | Draw the circle, menu and answer bubbles over other apps. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required foreground service of type mediaProjection. |
| `POST_NOTIFICATIONS` | Show the mandatory notification (Android 13+). |
| MediaProjection consent | Asked by Android itself; nothing can be captured without it. |

## JEE modes
- **Automatic**: judges difficulty per question, reports `exam_level`, never claims to know the exact exam or year unless visible.
- **JEE Main**: efficient standard methods, option elimination, numerical shortcuts.
- **JEE Advanced**: deeper multi-concept reasoning, still inside the Advanced syllabus, watches for multiple-correct / integer types.
- **Syllabus restriction** (on by default): instructs the model to use only official NTA JEE Main / JEE Advanced syllabus methods, prefer the simplest valid JEE-level method, treat hard problems as in-syllabus, and state a limitation if no JEE-level method exists. The app does not embed the official syllabus text; the AI is told to treat the official syllabi as authoritative and a compact chapter list is only a naming hint.

## Token-saving architecture
- Stage 1 returns concise JSON only; explanations are generated only on demand, for one question.
- Identical scans (same image hash and settings) are answered from an in-memory cache with zero requests.
- Stage 2 re-sends only a small crop, and only for figures, unclear text or low confidence (or if you force it in Settings); otherwise text only.
- Images are resized/JPEG-compressed (Low / Balanced / High); maximum questions per scan and per-stage output caps.
- Settings > AI shows requests made / successful / failed / cached, **actual** tokens reported by Groq, and **locally estimated** tokens, kept separate.

## GitHub Actions SDK setup note

The Android SDK setup step uses `android-actions/setup-android@v4` and explicitly installs
only `platform-tools`. Do not add the legacy SDK package named `tools`: Google no longer
publishes that package, and `sdkmanager tools` fails with `Failed to find package 'tools'`.
The Android Gradle Plugin downloads the project's required Android platform and build-tools
packages during the Gradle build after the SDK licenses have been accepted.

## Build
Local:
```bash
./gradlew test
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
The debug APK is signed with the auto-generated Android debug keystore; **no private credentials are needed**.

### GitHub Actions (builds the APK for you)
1. Create a GitHub repository.
2. Upload the entire `SnapGPT-Android` folder contents (including the hidden `.github` folder and `gradle/wrapper/gradle-wrapper.jar`), or `git init`, `git add .`, `git commit`, `git remote add origin ...`, `git push -u origin main`.
3. Open the repository's **Actions** tab.
4. Select **Build Android APK**.
5. Wait for it to finish (also runs on every push to `main`, on pull requests, and via *Run workflow*).
6. Open the completed run.
7. Download the **SnapGPT-Android-Debug-APK** artifact (a ZIP).
8. Extract the ZIP.
9. Copy `app-debug.apk` to your phone and install it (allow "Install unknown apps" for your file manager or browser).

GitHub Actions only produces the APK; it does not publish anything to Google Play. Status of this workflow: structure validated (YAML parses; wrapper validation, JDK 17, Android SDK, Gradle cache, unit tests, `assembleDebug`, artifact upload, logs on failure). **A live GitHub run is still pending.**

### Release signing (optional)
Keep credentials **outside the repository** (`*.jks`, `*.keystore` and `keystore.properties` are git-ignored).
1. Create a keystore once: `keytool -genkeypair -v -keystore release.jks -alias snapgpt -keyalg RSA -keysize 2048 -validity 10000`. Store it and its passwords somewhere safe; losing it means you cannot update installed apps.
2. Locally: `export SNAPGPT_KEYSTORE_PATH=/path/to/release.jks SNAPGPT_KEYSTORE_PASSWORD=... SNAPGPT_KEY_ALIAS=snapgpt SNAPGPT_KEY_PASSWORD=...` then `./gradlew assembleRelease`. Without these variables the release build is unsigned.
3. On GitHub: add secrets `SNAPGPT_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `SNAPGPT_KEYSTORE_PASSWORD`, `SNAPGPT_KEY_ALIAS`, `SNAPGPT_KEY_PASSWORD`, then run **Build signed release APK (optional)** manually.

## Privacy
- Nothing is captured until you tap Scan; the virtual display is attached to a buffer only for that capture, and SnapGPT's own windows are hidden during it.
- Screenshots go only to Groq, only when you scan or ask. Nothing is uploaded in the background and there is no analytics.
- History (questions, answers, explanations, small thumbnails, token counts) stays on-device; it can be turned off, and thumbnails, conversations or everything can be deleted in Settings > Privacy.
- Backups are disabled so the encrypted key and history are not copied off the device.

## Known Android limitations
- **Protected content** (`FLAG_SECURE`: banking apps, DRM video, some exam apps) is delivered by Android as a black frame. SnapGPT detects this and tells you; it does not and cannot bypass it.
- MediaProjection consent is required each time the assistant starts; Android 14+ ends capture when the user stops it from the status bar, after which the next scan asks again.
- Overlays cannot appear over some system screens (permission dialogs, lock screen) and some OEM ROMs add extra restrictions (battery optimisation may kill the service).
- Rotating the screen clears answer bubbles (their positions belong to the old layout) and re-lays out the circle and panel.
- Answer-bubble accuracy depends on the AI's bounding boxes; the parser rescales common 0-100 and 0-1000 and pixel notations, but boxes can still be off.
- The explanation panel's text field only becomes focusable while you type (Android does not allow keyboards in non-focusable overlay windows).
- AI answers can be wrong. Verify important results.

## Troubleshooting
- **Workflow fails at compile**: open the failed run, expand *Build debug APK*, and fix the reported Kotlin error (the Android layers were never compiled by the author). The *SnapGPT-Android-Build-Logs* artifact has the reports.
- **Dependency not found**: versions are pinned in `gradle/libs.versions.toml`; bump the named version.
- **Circle does not appear**: check *Draw over other apps* is on for SnapGPT and that the notification was not swiped away (Stop it and start again).
- **"Screen came back blank"**: the app in front blocks screenshots.
- **Scan times out**: some devices send no frame for a completely static screen; try again after scrolling a little, and report the device model.
- **Invalid key / model not listed**: Settings > AI > Test connection shows which.
- **No answers positioned correctly**: try *Select question area* around a single question.

## Tests
`./gradlew test` runs: Stage 1 parsing/validation and box normalisation, prompt content per mode and setting, the Groq client using an injected fake HTTP transport (auth header, image payload, usage, 401/404/413/429/5xx, cool-down, network failure), bubble placement and coordinate mapping, answer formatting, token accounting, image sizing, settings defaults and LaTeX clean-up.

Not covered by automated tests (needs a device): floating overlay, dragging and transparency, MediaProjection capture, area selection UI, permission flows, Room, DataStore persistence, Keystore, live Groq calls.

## License
MIT, see `LICENSE`.
