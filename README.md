# MouniNaveen Collector

Android Studio project for Firebase project `mouninaveentracker`.

## Firebase configuration
`app/google-services.json` is the configuration supplied in this conversation and contains both registered Android clients. Google Services selects the client matching this module's `applicationId` (`com.mouninaveen.collector`). Do not share this file publicly as a practice; Firebase Android API keys are identifiers, not admin secrets. Apply API-key restrictions where practical.

## Build
- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle 8.11.1 (CI installs this version)
- Open this directory in Android Studio, sync, then run `./gradlew :app:assembleDebug` after generating the Gradle wrapper in Android Studio, or run `gradle :app:assembleDebug` with Gradle 8.11.1 installed.

## Important status
This is an implementation baseline, not a verified production release. CI is configured to compile, test, lint and upload a debug APK. No APK has been built in this environment. Complete the Firebase setup and emulator/physical-device validation described in `docs/` before deployment.
