# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VCAT (Video Codec Acid Test) is an Android video decoder benchmarking application that evaluates real-world decode performance through long-running playback workloads with telemetry collection. Built on ExoPlayer, it supports hardware decoders (H.264, HEVC, VP9, AV1) plus a bundled dav1d software AV1 decoder.

## Build Commands

```bash
# Build and run tests
./gradlew build

# Build release APK (outputs: VCAT-{versionName}-v{versionCode}.apk)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run single test class
./gradlew testDebugUnitTest --tests "com.roncatech.vcat.CpuInfoTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Lint checks
./gradlew lint

# Install debug APK on connected device
./gradlew installDebug

# View dependency tree
./gradlew app:dependencies
```

## Architecture

### Module Structure
Single-module Android app (`app/`) with package organization:
- **ui/** - Activities (MainActivity, FullScreenPlayerActivity) and Fragments for bottom navigation tabs
- **video/** - ExoPlayer integration with custom StrictRenderersFactoryV2 for decoder selection
- **models/** - ViewModels (SharedViewModel) and data classes (RunConfig, TestResult, DecoderConfig)
- **telemetry/** - TelemetryLogger for CSV-based metrics (battery, CPU, memory, frame drops)
- **http/** - NanoHTTPD server on port 53000 for remote control
- **service/** - CommandReceiver and PlayerCommandBus for inter-component communication
- **tools/** - Device/CPU/battery info, XSPF playlist parsing, storage utilities

### Key Architectural Patterns
- **MVVM**: SharedViewModel with LiveData for reactive UI updates
- **Fragment-based navigation**: Bottom navigation with multiple tab fragments
- **Command pattern**: HTTP server → CommandReceiver → PlayerCommandBus → UI components

### External Dependencies
- **libvcat** - Core media stack with native decoders (dav1d AV1, optional vvdec VVC)
- **ExoPlayer 2.19.1** - Media playback framework
- **NanoHTTPD** - Embedded HTTP server
- **Gson** - JSON serialization for SharedPreferences

### Data Flow
```
User selects test vectors → RunConfig configured → FullScreenPlayerActivity launched
    → ExoPlayer plays with selected decoders → TelemetryLogger collects metrics
    → Results exported as CSV
```

## Configuration

- **Min SDK**: 30 (Android 11)
- **Target/Compile SDK**: 35 (Android 15)
- **Java**: 11
- **Namespace**: `com.roncatech.vcat`

Test results stored in `/vcat/test_results/` on device storage.
