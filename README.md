# MultiAI Trading Council — Android P1

Native Android app source for an offline-first, multi-model trading research council. No Termux required.

## Build

Pushing to `main` triggers GitHub Actions. The workflow reconstructs the source bundle stored under `ci/source-bundle/`, installs JDK 17 + Android SDK/NDK/CMake, runs unit tests, then builds a debug APK.

Open **Actions → Android debug APK → latest successful run → Artifacts** to download `MultiAITradingCouncil-debug-APK`.

## Current scope and limits

- Native Android UI uses Kotlin and Jetpack Compose.
- Local GGUF chat is bridged to llama.cpp via JNI; specialist AI slots use ONNX Runtime Android.
- The project has no pretrained financial specialist weights embedded. TCN, GRU, RegimeNet, AnomalyNet and EnsembleGate must be trained/exported and validated for their declared input/output contracts.
- No broker connection or real-money order execution is implemented. The live-trading gate remains disabled.
- Build success only confirms the APK compiles and tests pass; it does not prove model forecast accuracy or profitability.
- The initial native build fetches llama.cpp from GitHub, so network access is required during CI.

Full Android Studio project is reconstructed by the workflow from the versioned source bundle. 
