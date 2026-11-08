# MouniNaveenTracker — Collector

Kotlin + Jetpack Compose + Firebase Authentication/Firestore. Package: `com.mouninaveen.collector`.

See `docs/FIREBASE_SETUP.md` before first use and `docs/KNOWN_LIMITATIONS.md` for platform limitations. Deploy `firebase/firestore.rules` before testing.

Open this folder in Android Studio (JDK 17, Android SDK 35), sync Gradle, then build the debug APK. The included `google-services.json` contains both registered Android clients; Google Services selects the matching package.

Privacy: no messages, notification content, calls, contacts, keystrokes, passwords, microphone, camera, screen recordings, photos, videos, or files are collected. Consent is required and can be withdrawn in the Collector.
