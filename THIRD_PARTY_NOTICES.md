# Third-party notices

## libxposed API

- Project: https://github.com/libxposed/api
- Version: 102.0.0
- License: Apache License 2.0
- Usage: compile-only; the API implementation is not packaged in the APK.

## libxposed Service

- Project: https://github.com/libxposed/service
- Version: 102.0.0
- License: Apache License 2.0
- Usage: packaged in the module App to access framework Remote Preferences.

## Miuix

- Project: https://github.com/compose-miuix-ui/miuix
- Version: 0.9.3
- License: Apache License 2.0
- Usage: Compose UI and preference components packaged in the module App.

## DexKit

- Project: https://github.com/LuckyPray/DexKit
- Version: 2.2.0
- License: Apache License 2.0; the published artifact metadata also declares LGPL-3.0.
- Usage: packaged in the module App, including its published Android native libraries, to narrow
  obfuscated hook candidates before the module performs its own reflection checks.

## Android Gradle Plugin and Gradle Wrapper

- Android Gradle Plugin 9.2.1 is distributed under the Android SDK license.
- Gradle 9.5.1 is distributed under the Apache License 2.0.
