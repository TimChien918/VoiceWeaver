# VoiceWeaver Agent Guide

> 🏷 **Product renamed to 「拾語者 Speak My Life」** (2026-10). User-facing text uses the new name (繁中「拾語者」, EN/JA/KO "Speak My Life"); code identifiers still use VoiceWeaver on purpose — package/applicationId, class & file names (`VoiceWeaverCore`, `VoiceWeaverScreen`…), Google Drive library folder `VoiceWeaver/…`, SharedPreferences/DB/Firestore keys, repo & Live URLs, `~/VoiceWeaver-電腦端/`, desktop bundle/exe file names. Do not rename those (compatibility with existing users' data).

> 📦 **Computer-side runtime moved out of this project** (2026-06): GPT-SoVITS, the conda
> env, and miniforge now live in `~/VoiceWeaver-電腦端/` (separate from the Android code).
> The `tools/GPT-SoVITS/`, `tools/gpt-sovits-env/`, `tools/miniforge3/` paths below now map
> to `~/VoiceWeaver-電腦端/`. The Android app only talks to those servers over HTTP, so the
> move does not affect the app build. See `tools/電腦端已搬移.md`.

Purpose: keep AI coding agents fast and focused. Read this file first, then use `rg` from the repo root. The repo has large local model/runtime folders; do not browse them unless the task explicitly asks for model internals.

## Fast Search Rules

- Use `rg` / `rg --files`; `.ignore` is tuned so searches skip generated output, model weights, Conda runtimes, and large assets.
- Do not recursively inspect these by default:
  - `tools/miniforge3/`
  - `tools/gpt-sovits-env/`
  - `tools/GPT-SoVITS/GPT_SoVITS/`
  - `tools/GPT-SoVITS/*weights*/`
  - `tools/GPT-SoVITS/user_models/`
  - `app/src/main/assets/models/`
  - `app/src/main/assets/poi/`
- Open ignored files only when the user specifically asks about model runtime, weights, assets, or GPT-SoVITS internals.

## Main App Map

- App entry/core:
  - `app/src/main/java/com/example/MainActivity.kt`
  - `app/src/main/java/com/example/ai/VoiceWeaverCore.kt`
- TTS / GPT-SoVITS:
  - `app/src/main/java/com/example/ai/TtsModule.kt`
  - `app/src/main/java/com/example/ai/LocalSovitsHelper.kt`
  - `tools/GPT-SoVITS/model_setup_server.py`
  - `tools/GPT-SoVITS/api_v2.py`
- AAC UI:
  - `app/src/main/java/com/example/ui/AacScreen.kt`
  - `app/src/main/java/com/example/data/AacData.kt`
- Main UI/settings:
  - `app/src/main/java/com/example/ui/VoiceWeaverScreen.kt`
  - `app/src/main/java/com/example/ui/SettingsScreen.kt`
  - `app/src/main/java/com/example/ui/theme/AppPalette.kt`
- Location/POI:
  - `app/src/main/java/com/example/ai/LocationTracker.kt`
  - `app/src/main/java/com/example/ai/GooglePlacesClient.kt`
  - `app/src/main/java/com/example/ai/OverpassPoiClient.kt`
  - `app/src/main/java/com/example/ai/OfflinePoiIndex.kt`
- Data:
  - `app/src/main/java/com/example/data/AppDatabase.kt`
  - `app/src/main/java/com/example/data/SettingsRepository.kt`

## GPT-SoVITS Local Workflow

- Start API: `./tools/GPT-SoVITS/start_api.sh`
- Start model setup page: `./tools/GPT-SoVITS/start_model_setup_page.sh`
- Setup page URL: `http://127.0.0.1:9890/`
- API URL: `http://127.0.0.1:9880/`
- The setup page owns model memory, one-click switching, stop synthesis, test WAV playback, and Finder reveal.
- Active API state must be verified through `/health`; config alone is not enough.

## Build / Install

- Build debug APK: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
- USB API bridge for phone: `/Users/timchien/Library/Android/sdk/platform-tools/adb reverse tcp:9880 tcp:9880`
- Install debug APK: `/Users/timchien/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Editing Rules

- Keep changes focused. Do not refactor unrelated app screens while fixing GPT-SoVITS, AAC, location, or settings.
- Preserve user models, weights, refs, and `tools/GPT-SoVITS/user_models/model_history.json`.
- Safe-to-delete generated files: `app/build/`, root `build/`, `.DS_Store`, `__pycache__/`, `*.pyc`, temporary caches.
- Do not delete Conda/runtime folders or model weights unless the user explicitly asks.

## Verification Checklist

- For Python setup/API changes: run `python3 -c "compile(open('PATH', encoding='utf-8').read(), 'PATH', 'exec')"`.
- For UI page changes: restart `9890` and verify `http://127.0.0.1:9890/` in the in-app browser.
- For Android changes: run `./gradlew :app:assembleDebug`; install only if the user needs it on-device.
- After model switching changes, verify the page shows `API 使用中`, not only `設定中`.
