# Build and CI

The workflow at `.github/workflows/android.yml` uses Java 17, Android SDK 35, Gradle 8.11.1, runs unit tests and lint, builds a debug APK, verifies the APK archive, and uploads it as a GitHub Actions artifact. Push each project to its own GitHub repository and run Actions. The workflow requires `app/google-services.json` to be present.

No release signing secrets or release APK signing are configured. Add a protected keystore and GitHub Actions secrets before release signing; never commit a keystore or passwords.
