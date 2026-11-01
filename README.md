# Qhorium

Qhorium is an Android Wear OS messaging client implemented in Kotlin using Wear Material 3 Jetpack Compose, designed for circular Wear OS displays.

## Overview

- **Platform**: Wear OS (API 23+)
- **Target Display**: Circular smartwatches
- **UI Framework**: Wear Compose / Wear Material 3
- **Language**: Kotlin 2.x / Java 17

## Project Structure

- `app/src/main/java/`: Main application source code
  - `rj/qmce/lite/ui`: Wear Material 3 Jetpack Compose UI components and screens
  - `rj/qmce/lite/viewmodel`: ViewModels and UI state management
  - `rj/qmce/lite/data`: Repositories, models, media caches, and session handling
  - `rj/qmce/lite/kernel`: Kernel bridges and IPC handlers
  - `rj/qmce/lite/fix`: Runtime compatibility and platform patches
  - `rj/qmce/lite/agent`: Optional onboard LLM agent integration
  - `rj/qmce/lite/notify`: Notification management and Wear complications
- `app/src/main/res`: Android Wear OS resources and adaptive icons
- `app/libs`: Precompiled SDK dependencies
- `Design/`: Vector design assets and brand resources

## Building from Source

### Prerequisites

- JDK 17
- Android SDK (Platforms 36/37, Build-Tools 37.0.0)

### Build Commands

```bash
# Debug build (generates debug APK under app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# Release build (minified)
./gradlew :app:assembleRelease

# Clean Kotlin compilation diagnostics
./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false
```

## Engineering & Development Notes

- **Runtime Compatibility**: Do not modify stub packages (`moye.*`) or runtime patch packages (`rj.qmce.lite.fix`) unless required for platform compatibility.
- **ProGuard**: Required ProGuard rules in `app/proguard-rules.pro` must be preserved as precompiled SDK classes reference application classes via reflection.
- **Display Target**: The UI is optimized for circular displays (`isRound = true`). Screen components should adhere to safe circular padding and bottom composer clearance.

## License & Contribution

- Contributions must maintain code quality, existing formatting (4-space indentation), and objective documentation.
- Commit messages must be concise, descriptive, and in English.
