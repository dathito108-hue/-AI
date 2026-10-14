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

## Market time-series forecasting and multi-AI council

The **Hội đồng AI tìm cơ hội thị trường** section adds a real ONNX inference path using the pinned [Chronos-2 INT8 ONNX checkpoint](https://huggingface.co/OpenSTEF/chronos-2-onnx/blob/d3aa45b0acbedadf69d241954ed1b21167937bae/chronos-2_int8.onnx). The exact artifact is 130,818,337 bytes with SHA-256 `5a4b21df79238050b3815c4bda7e580ff254d1c755f7c5bad9eb11ac91920115`; it is downloaded on demand, resumes partial downloads, and is verified before use.

### Forecast I/O contract

- Source currently supported by the in-app council: **user-imported CSV**, not a live broker or exchange feed.
- CSV needs headers `timestamp,open,high,low,close,volume` and at least 6,048 chronologically ordered 15-minute bars for the council run. The parser allows session/weekend closures up to 72 hours, rejects irregular gaps, and never synthesizes missing candles. The model uses the latest 5,760 bars as input; the earlier/later holdout structure is kept for a recent rolling-origin screen. Forecast horizons refer to bar counts; around market closures they are not guaranteed wall-clock durations.
- Tensor inputs to Chronos-2: `context` float32 `[4,5760]` with log(close), log(high), log(low), log(1+volume); `group_ids` `[4]`; `attention_mask` `[4,5760]`; and future-covariate values/mask `[4,672]`.
- Output `quantile_preds` is interpreted as 21 quantiles over 672 future 15-minute steps for each input series. The close-series p10/p50/p90 is converted from log-price back to price and reported at 1h, 4h, 1d and 7d horizons.
- The checkpoint is a **general pretrained time-series model**, not a model fine-tuned or validated on a financial asset. Forecast quantiles are not calibrated probabilities of profit; backtesting and market-specific fine-tuning are still required.

### Council debate flow

The app runs the numeric forecaster, optionally runs FinSenti on user-provided headlines, runs Qwen2-Math as a quantitative risk auditor, then runs the local Qwen coordinator in strategy-proposal, skeptic/critique, and final-synthesis turns. GGUF models are loaded sequentially to limit RAM use. The app first runs three non-overlapping 96-bar rolling-origin holdouts (288 out-of-sample bars in total), comparing Chronos-2 median-price MAE with a last-close persistence baseline, direction hit rate and p10–p90 coverage. A preliminary hard NO-TRADE veto is applied if this short screen fails. This small recent sample is not a proof of profitability. The transcript is a research report with candidate long/short/no-trade scenarios, cost estimates, opposing evidence and invalidation conditions.

**No live trading is implemented or triggered.** A future live-data connector needs a selected market data source, timestamps/timezone handling, reconnect/staleness checks, spread/slippage/fee inputs, out-of-sample walk-forward tests and an explicit separate execution gate.

## Current scope and limits

- Native Android UI uses Kotlin and Jetpack Compose.
- Local GGUF chat is bridged to llama.cpp via JNI; specialist AI slots use ONNX Runtime Android.
- **Qwen3.5-2B is a general conversation/coordinator model, not a trained financial forecasting model.** TCN, GRU, RegimeNet, AnomalyNet and EnsembleGate still require their own trained ONNX weights that match `docs/MODEL_CONTRACTS.md`.
- No broker connection or real-money order execution is implemented. The live-trading gate remains disabled.
- Build success confirms the APK compiles and tests pass; it does not prove model forecast accuracy or profitability.
- The initial native build fetches llama.cpp from GitHub, so network access is required during CI.

Full Android Studio project is reconstructed by the workflow from the versioned source bundle plus the tracked patches in `ci/patches/`.
