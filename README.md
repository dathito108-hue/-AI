# MultiAI Trading Council — Android P1

Native Android app source for an offline-first, multi-model trading research council. No Termux required.

## Build

Pushing to `main` triggers GitHub Actions. The workflow reconstructs the Android Studio source bundle under `ci/source-bundle/`, applies the tracked build/model-installer patches, installs JDK 17 + Android SDK/NDK/CMake, runs unit tests, then builds a debug APK.

Open **Actions → Android debug APK → latest successful run → Artifacts** to download `MultiAITradingCouncil-debug-APK`.

## Install a conversation model in the app

Open **Mô hình** in the APK and tap **Tải và cài Qwen3.5-2B (~1,27 GB)**. The in-app downloader fetches Qwen3.5-2B Q4_K_M GGUF from Hugging Face, resumes a partial transfer when supported, verifies SHA-256, and registers it as the local coordinator model. Wi-Fi and at least 1.5 GB of free storage are recommended. Model source: https://huggingface.co/lmstudio-community/Qwen3.5-2B-GGUF

## Install real specialist AIs

Open **Mô hình** → **AI chuyên môn có trọng số thật**. The app can download, SHA-256-check against the Hugging Face file metadata, install into app-private storage, and load these GGUF weights locally:

- **FinSenti-Qwen3.5-0.8B Q4_K_M** (~529 MB): fine-tuned for financial text sentiment. English-focused; it is not a price-forecasting model. Source: https://huggingface.co/Ayansk11/FinSenti-Qwen3.5-0.8B-GGUF
- **Qwen2-Math-1.5B-Instruct Q4_K_M** (~918 MB): math/reasoning specialist. Source: https://huggingface.co/tensorblock/Qwen2-Math-1.5B-Instruct-GGUF
- **Qwen2.5-Coder-1.5B-Instruct Q4_K_M** (~1.12 GB): coding/EA development specialist. Source: https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF

These are genuine pretrained or fine-tuned weights, not hard-coded prediction rules. The APK contains the installer and runtime, not the multi-gigabyte weight files; activate each model from inside the app to download it. The current runtime loads one GGUF model at a time to stay within phone memory limits. A specialist download failing its metadata/hash/size checks is not installed.

## Current scope and limits

- Native Android UI uses Kotlin and Jetpack Compose.
- Local GGUF chat is bridged to llama.cpp via JNI; specialist AI slots use ONNX Runtime Android.
- **Qwen3.5-2B is a general conversation/coordinator model, not a trained financial forecasting model.** TCN, GRU, RegimeNet, AnomalyNet and EnsembleGate still require their own trained ONNX weights that match `docs/MODEL_CONTRACTS.md`.
- No broker connection or real-money order execution is implemented. The live-trading gate remains disabled.
- Build success confirms the APK compiles and tests pass; it does not prove model forecast accuracy or profitability.
- The initial native build fetches llama.cpp from GitHub, so network access is required during CI.

Full Android Studio project is reconstructed by the workflow from the versioned source bundle plus the tracked patches in `ci/patches/`.
