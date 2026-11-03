# SnapGPT Android

Native AI floating screen assistant for JEE Main / JEE Advanced students. A small draggable circle floats over other apps; tap it to scan the screen (or a selected area), get **concise, independently verified answers** next to every detected question, then tap an answer for a brief explanation (or the full step-by-step solution on request).

**One app, one APK, many providers.** Add as many API accounts as you like (several Groq keys, Gemini, OpenRouter, Anthropic, any OpenAI-compatible server). SnapGPT tries them in your priority order and fails over automatically when one runs out of quota, is rate-limited, has a bad key or is down.

Kotlin · Jetpack Compose (Material 3) · WindowManager overlays · MediaProjection · Foreground Service · Coroutines · DataStore · Room · Android Keystore. Not a WebView app.

> **Honest build status — read this first**
> - The pure logic (provider adapters, failover, verification, pipeline, prompts, Stage 1 parsing, placement, token accounting) is covered by **83 unit tests** that were compiled and run with a standalone Kotlin compiler. They use scripted HTTP replies and a local mock server. No real provider was contacted.
> - The Android layers (Compose UI, overlay windows, MediaProjection capture, Room, Keystore, foreground service) and the Gradle/AGP build **have never been compiled or run**: the authoring environment had no Android SDK and could not reach Maven/Google/Gradle servers.
> - **No APK has been built and none is included.** The GitHub Actions workflow was checked for structure only; a live run is pending. The first run may show compile errors to fix (see [Troubleshooting](#troubleshooting)).
> - No live provider request and no on-device test (overlay, capture, permissions, rotation, Keystore) has been performed. Treat everything on-device as untested until you try it.

## Intended use
SnapGPT is a study and practice aid. Do not use it during proctored exams or anywhere assistance is prohibited. AI answers can be wrong; verification lowers the risk but never removes it.

## Features
- Floating circle (size/opacity configurable), drag anywhere, edge snapping, remembered position. Menu: **Scan Entire Screen**, **Select Question Area**, **Clear Answers**, **Hide Assistant**, **Settings**.
- **Confirm before anything is sent.** After capture, a frozen preview shows which account/provider will receive the image and waits for **Confirm**. **Cancel** sends nothing. Area scans send only the selected rectangle.
- **Multiple providers behind one interface**: Groq, Google Gemini, OpenRouter, Anthropic, Custom (OpenAI-compatible, https only). Each has its own request/response/error format and usage reporting.
- **Unlimited accounts**: name, provider, endpoint (override/proxy), model, key, enabled, priority, status, last error, last success, cooldown, per-account usage. Add, edit, delete, test, enable/disable, move up/down, duplicate (key copied only if you say so).
- **Automatic failover** (can be switched off, then only your top account is used) with live status messages such as "Sending screenshot to Groq Primary…", "Groq Primary quota reached.", "Switching to Gemini Primary…", "Answer generated using Gemini Primary."
- **Answer verification**: the question text is solved again independently (preferably on a different account), compared, and disagreements get a closer re-check. Labels: *Verified*, *Corrected after verification*, *Unable to verify*, *Low confidence*.
- **Brief by default**: final answer, short explanation, formula, verification status. Follow-ups are concise. Phrases like "Give a detailed answer", "Explain in detail", "Show all steps", "Explain from basics", "Derive the formula" (or the **Detailed solution** chip) produce the full step-by-step answer. Context is kept without resending the screenshot.
- Two-stage AI preserved: Stage 1 = JSON answers + bounding boxes only; Stage 2 = explanation only when you tap a bubble.
- JEE modes (Automatic / Main / Advanced), syllabus restriction (default on), chapter/topic display, alternative methods.
- Per-account usage dashboard; history with thumbnails; Light/Dark/System theme.

## Requirements
- Android 8.0+ (API 26), compiled against SDK 35.
- At least one API key from a provider you choose (see below).
- To build locally: JDK 17 and Android SDK 35.

## Using the app
1. Install the APK and open **SnapGPT**.
2. **Settings > API accounts > Add account**. Pick a provider, give it a name, type the model name exactly, paste the key, Save, then **Test**.
   - **Groq**: key from console.groq.com/keys. Add several keys as separate accounts (e.g. "Groq Primary", "Groq Backup").
   - **Gemini**: key from Google AI Studio. Example model `gemini-2.5-flash`.
   - **OpenRouter**: key from openrouter.ai. Choose a model that accepts images, e.g. `google/gemini-2.5-flash`.
   - **Anthropic**: key from console.anthropic.com. Example model `claude-sonnet-5-5`.
   - **Custom**: base URL of an OpenAI-compatible server (must be `https://`), its model id, and tick *This model accepts images* if true.
3. Order the accounts with **Move up/down**; the top one is tried first.
4. Home > **Start assistant**, allow *Draw over other apps* and Android's screen-capture prompt, then use the circle.

### Priority and failover
- Accounts are tried by priority. After a failure, remaining accounts of the **same provider** are tried before other providers.
- An account is skipped when disabled, without key/model, cooling down, or (for screenshots) its model is not known to accept images. Models are **never swapped silently**: each account uses exactly its configured model.
- Recoverable failures move on: quota/credits exhausted, HTTP 429 (Retry-After respected), invalid or revoked key, model unavailable, provider-side "no images", temporary 5xx/network/timeout after limited retries with exponential backoff.
- Not retried elsewhere: ordinary malformed requests, oversize images, truncated answers.
- Cooldowns: rate limit = Retry-After (default 60 s); quota = reported delay (at least 5 min, default 30 min); invalid key 6 h (cleared when you edit or successfully test the account); temporary problems 60 s.
- At most 8 accounts are tried per request, and an identical in-flight request is never sent twice.
- Remaining quota is shown **only** if a provider reports it in response headers; it is never guessed.

### Verification
Stage 1 answers are re-solved from text alone by another account when possible. *Verified* means an independent solve agreed and flagged no doubtful formula, sign or unit; it is **not a proof**. Questions that depend on figures or unclear text show *Unable to verify*. Verification costs extra requests and can be turned off in Settings.

### Token tracking
Per account: requests, successes, failures, quota errors, per-kind counts (analysis, explanation, follow-up, verification), **actual** tokens (provider-reported) and **estimated** tokens (local guess: ~4 characters per token + 2048 per image). They are always shown separately.

## Security and privacy
- Every API key is encrypted **individually**: each account has its own AES-256-GCM key in the Android Keystore; only ciphertext is stored. Deleting an account or key destroys its Keystore entry. Keys are never hard-coded, committed, logged or shown again after saving; error text is scrubbed of key-like strings.
- HTTPS only (plain http is accepted solely for loopback test servers). Keys travel in headers, never in URLs.
- Screenshots stay on the device until you press Confirm, go only to the provider named on the card, and nothing is sent to providers you have not configured. No analytics, no background uploads.
- **A key stored inside any mobile app cannot be made fully secret**: someone controlling the device or process can extract it. Use dedicated keys you can revoke. For a public release put a backend proxy in front of the providers: each account has an editable endpoint, so you can point accounts at your proxy and move keys server-side.
- Screenshots of the selected area may be cached as small thumbnails in history (can be disabled/deleted in Settings).

## Repository structure
```
app/src/main/java/com/snapgpt/
├── ui/            Compose screens (Home, Accounts, Usage, Settings, History), view models
├── overlay/       circle, menu, confirm/selection overlay, status pill, answer bubbles, explanation panel
├── capture/       MediaProjection capture, consent activity, JPEG encoding
├── ai/            pipeline, prompts, Stage 1 parser, error mapping, AiService
│   ├── providers/     adapters (Groq, Gemini, OpenRouter, Anthropic, OpenAI-compatible), accounts, capabilities
│   ├── failover/      failover manager, account store, connection tester
│   └── verification/  answer verifier and comparer
├── jee/           exam modes and JEE instructions
├── history/       session models, history repository
├── settings/      DataStore settings, Room-backed account repository
├── database/      Room entities and DAOs
├── security/      per-account Keystore encryption
├── networking/    HTTP transport
└── services/      foreground OverlayService, ScanCoordinator, notification
app/src/test/      unit tests (83)
.github/workflows/ build-android.yml, release-android.yml
```

## Build
```bash
./gradlew test assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
The debug APK uses the auto-generated debug keystore; **no private credentials are needed**.

### GitHub Actions (builds the APK for you)
1. Create a GitHub repository and push the project (include the hidden `.github` folder and `gradle/wrapper/gradle-wrapper.jar`).
2. Open **Actions > Build Android APK** (runs on push to `main`, pull requests and *Run workflow*).
3. When it finishes, download the **SnapGPT-Android-APK** artifact, extract the ZIP and install `app-debug.apk` on your phone (allow "Install unknown apps").

One APK is produced. On failure the **SnapGPT-Android-Build-Logs** artifact holds the reports. A live run is still pending.

### Release signing (optional, one signed APK)
Keep credentials **outside the repository** (`*.jks`, `*.keystore`, `keystore.properties` are git-ignored).
1. `keytool -genkeypair -v -keystore release.jks -alias snapgpt -keyalg RSA -keysize 2048 -validity 10000`; keep it safe.
2. Locally: export `SNAPGPT_KEYSTORE_PATH`, `SNAPGPT_KEYSTORE_PASSWORD`, `SNAPGPT_KEY_ALIAS`, `SNAPGPT_KEY_PASSWORD`, then `./gradlew assembleRelease`.
3. On GitHub add secrets `SNAPGPT_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `SNAPGPT_KEYSTORE_PASSWORD`, `SNAPGPT_KEY_ALIAS`, `SNAPGPT_KEY_PASSWORD` and run **Build signed release APK (optional)** manually.

## Permissions
`INTERNET`; `SYSTEM_ALERT_WINDOW` (circle, bubbles); `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`; `POST_NOTIFICATIONS`; MediaProjection consent is asked by Android itself.

## Limitations
- Protected (FLAG_SECURE) apps return a blank frame; SnapGPT detects this and does not bypass it.
- Overlays cannot appear over some system screens, and some OEMs kill background services.
- Rotation clears answer bubbles. Bubble accuracy depends on the AI's bounding boxes.
- Unknown models (custom/OpenRouter/other Groq models) are not sent screenshots until image support is validated or you confirm it.
- Verification of figure-based questions is not possible from text.
- The explanation text field is focusable only while typing (Android overlay rule).
- AI answers can be wrong. Verify important results.

## Troubleshooting
- **Workflow fails at compile**: open the failed run, expand *Run unit tests and build the debug APK* and fix the reported Kotlin error (Android layers were never compiled by the author).
- **"No API account can receive a screenshot"**: enable an account with a key whose model accepts images and that is not cooling down (Accounts shows why).
- **Invalid key / model not found**: use **Test** on the account.
- **Circle missing**: check *Draw over other apps* and that the notification was not dismissed.

## Tests
`./gradlew test` covers adapters (request shapes, auth, images, usage, error mapping per provider), failover (priority, quota, 429 + Retry-After, invalid key, model unavailable, same-provider-first, multiple keys, backoff, network errors, no-switch on bad requests, disabled failover, image eligibility, dedupe, cancellation, 1000 accounts), verification (verified/corrected/low confidence/unable), the two-stage pipeline and caching, brief vs detailed prompts, JEE prompts, Stage 1 parsing, placement, token accounting, image sizing and LaTeX clean-up.

Not covered (needs a device): overlays, capture, permission flows, Room, DataStore, Keystore, live provider calls.

## License
MIT, see `LICENSE`.
