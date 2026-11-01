# SnapGPT Android

A native Kotlin/Jetpack Compose Android study assistant. A user-started floating control can request a screen capture, send the selected screenshot to Groq's Qwen vision model for concise JEE-oriented answers, then request a detailed explanation only when an answer bubble is tapped.

> **Study-use note:** SnapGPT is intended for practice, revision and learning. Follow your school, coaching centre and examination rules. Do not use it to get unauthorized assistance during tests or examinations.

## Current implementation

- Native Android app built with Kotlin and Jetpack Compose (not a WebView).
- Draggable floating `S` assistant with idle transparency and a tap menu.
- Explicit Android MediaProjection permission for each scan session.
- Full-display scan and screenshot-preview region selection.
- Groq vision request using `qwen/qwen3.8-27b`.
- Stage 1: concise structured question/answer detection.
- Stage 2: detailed JEE-focused explanation only after an answer bubble is tapped.
- Follow-up questions in the explanation panel.
- Bounding-box-based answer bubble placement.
- JEE Main, JEE Advanced and Automatic prompt modes; syllabus restriction is enabled by default.
- Android Keystore encryption for the API key at rest.
- Local Room history and local token counters.
- GitHub Actions workflow that runs unit tests and builds an APK artifact.

## Important platform limitations

1. Android requires explicit user consent for MediaProjection. On Android 14 and later, obtain a fresh consent result for each new projection session and use each projection instance for only one `createVirtualDisplay` call. SnapGPT requests consent for each scan.
2. Some apps mark their content secure (`FLAG_SECURE`). Android can block capture of those windows; SnapGPT does not bypass that restriction.
3. In selected-area mode, SnapGPT first captures the user-authorized display and then opens a preview where the user draws a crop rectangle. The crop is mapped back to the original screenshot coordinates for bubble placement.
4. Vision-model bounding boxes are approximate. Bubble placement is best-effort and may need manual repositioning on unusual layouts.
5. Full-screen image capture depends on the device/OEM display implementation and can be stopped by Android or the device vendor.
6. The API key is encrypted locally with Android Keystore. Because requests are made directly from the phone, a key used by a client application is not a true server-side secret. For public distribution, move Groq calls behind a secure backend proxy and apply per-user controls.
7. Model availability, quotas and rate limits are controlled by Groq and can change. The app displays API errors rather than claiming a fixed quota.
8. Screenshot files are kept in the app cache for the current scan/explanation session. The Room history stores question/answer metadata and a temporary screenshot path; Android may clear cached files later.

## Groq setup

1. Create an API key in the Groq Console: <https://console.groq.com/keys>.
2. Install SnapGPT and open **Settings**.
3. Paste the key and press **Save key**.
4. Press **Test Groq connection**.
5. The default model is `qwen/qwen3.8-27b`.

SnapGPT uses the OpenAI-compatible Groq endpoint:

- Base URL: `https://api.groq.com/openai/v1`
- Chat completions: `https://api.groq.com/openai/v1/chat/completions`
- Vision documentation: <https://console.groq.com/docs/vision>
- Models: <https://console.groq.com/docs/models>

The implementation sends screenshots only after the user starts a scan. Avoid scanning passwords, private messages, payment screens, personal records or other sensitive material.

## Run in Android Studio

1. Install Android Studio and the Android SDK Platform 35 / Build Tools 35.0.0.
2. Extract or clone this repository.
3. Open the `SnapGPT-Android` folder in Android Studio.
4. Use JDK 17 for Gradle.
5. Let Android Studio sync Gradle dependencies.
6. Run the `app` configuration on a physical Android device. Overlay and MediaProjection behaviour should be tested on a real device.

The repository includes a small self-bootstrapping `gradlew` script that downloads the pinned Gradle 8.9 distribution if a system Gradle is not installed. It is not the stock Gradle Wrapper JAR distribution. Android Studio may instead use its configured Gradle distribution during project import.

Build locally from a terminal with network access:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

The debug APK output is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions: build the APK

1. Create a GitHub repository, for example `SnapGPT-Android`.
2. Upload the full contents of this project to the repository root. The `.github/workflows` folder must be included.
3. Push or commit to `main` (the workflow also supports `master` and pull requests).
4. Open the repository's **Actions** tab.
5. Select **Build Android APK**. It runs automatically on push; you can also choose **Run workflow**.
6. Open the completed workflow run.
7. Download the artifact named **SnapGPT-Android-Debug-APK**.
8. Extract the artifact ZIP and install `app-debug.apk` on your Android device. Android may ask you to permit installation from the browser or file manager that opened the APK.

The workflow installs JDK 17, Android platform/build tools 35, Gradle 8.9, runs unit tests and assembles the debug APK. It uploads build reports if the workflow fails.

## Signed release workflow (optional)

The manual workflow `.github/workflows/release-android.yml` builds a signed release APK. Before running it, add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing key alias.
- `ANDROID_KEY_PASSWORD`: key password.

Keep the keystore and passwords private. Never commit them to Git. The release workflow fails early when required secrets are missing. The debug workflow does not require signing secrets.

## Permissions

- **Display over other apps (`SYSTEM_ALERT_WINDOW`)**: shows the draggable assistant and answer bubbles.
- **Foreground service**: keeps the user-enabled overlay alive while the user switches between apps.
- **MediaProjection foreground service**: carries out a screen capture after Android's consent UI is approved.
- **Notifications**: shows the ongoing service notification on Android versions that require notification permission.
- **Internet**: sends the user-selected screenshot and prompts to Groq and checks API connectivity.

The user can stop the floating service from SnapGPT or its ongoing notification. SnapGPT does not use an Accessibility Service and does not capture continuously.

## Two-stage token-conscious flow

- **Stage 1** sends one screenshot request asking for question text, subject/chapter where known, concise answer and normalized bounding boxes. It does not ask for worked explanations.
- **Stage 2** runs only when an answer bubble is tapped. It sends a crop of that question plus the detected context to Groq.
- Follow-up questions use the question and explanation text rather than automatically resending the original screenshot.

Actual token fields are read from Groq responses when provided. Local usage totals are not the same as the account's remaining quota.

## Troubleshooting

- **Floating button does not appear:** allow SnapGPT under Android Settings → Apps → Special app access → Display over other apps, then tap Enable again.
- **Notification permission prompt:** allow notifications so Android can show the ongoing foreground-service notice.
- **Capture permission cancelled:** tap the floating button and start the scan again; Android consent is required for each scan session.
- **Black/blank capture:** the app being captured may use protected content or the device may restrict projection.
- **401 from Groq:** confirm the API key in SnapGPT Settings.
- **429 from Groq:** check the Groq Console for current rate-limit/quota status and retry later.
- **Model not found or unavailable:** check Groq's current model catalogue and the account's model access. Do not assume model IDs or free-tier quotas are permanent.
- **No question bubbles:** ensure the question is readable in the captured image and retry with Select question area.
- **Build failure:** open the workflow run and inspect the Gradle error; if it is a dependency or SDK download error, rerun after the GitHub runner network is available.

## Repository layout

```text
SnapGPT-Android/
├── .github/workflows/
│   ├── build-android.yml
│   └── release-android.yml
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/snapgpt/
│   │   ├── MainActivity.kt
│   │   ├── ai/
│   │   ├── capture/
│   │   ├── data/
│   │   └── overlay/
│   └── res/values/
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── README.md
```

## License

Choose and add a license before distributing this project publicly. This repository intentionally does not claim third-party rights or include API credentials.
