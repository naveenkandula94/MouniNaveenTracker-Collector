# Known limitations

- Android UsageStats/UsageEvents are historical, permission-gated and may be delayed, aggregated, or incomplete. This is not guaranteed real-time monitoring.
- WorkManager periodic work has a minimum interval of 15 minutes and Android/OEM battery policies can defer it.
- Lock/unlock event availability varies by Android version and device. Missing events are not inferred.
- The Collector records foreground/background app event metadata and keyguard events only; it does not inspect app content.
- CSV export uses Android's share sheet as CSV text; large exports should be paginated or written to a document URI in a later iteration.
- Enrollment bootstrap is manual in Firebase Console because the Spark-compatible client-only app must not mint privileged credentials.
- Build and physical-device tests must be run in Android Studio with JDK 17 and Android SDK 35. This source package has not been physically tested in this environment.
