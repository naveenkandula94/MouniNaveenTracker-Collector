# Firebase setup — MouniNaveenTracker

Both Android apps use project `mouninaveentracker` and their own package registration. Keep the supplied matching `app/google-services.json`. Never put service-account keys or passwords in either APK.

## One-time administrator bootstrap
1. In Firebase Authentication, create the Collector email/password account and the Admin email/password account.
2. Sign in to the Admin app. In Firestore, create `admins/{ADMIN_UID}` manually (document ID must be the Admin Authentication UID). Set `role` to `admin`. This document is the authorization gate; the app cannot self-promote an account.
3. Open the Admin app and enroll the Collector by entering the Collector Authentication UID. The app creates `devices/{randomId}` with `adminUid`, `collectorUid`, `displayName`, and `createdAt`.
4. Install Collector, review the disclosure, tap I consent, grant Usage Access in Android settings, and sign in with the provisioned Collector account. It verifies enrollment and schedules sync.
5. Deploy `firebase/firestore.rules` from either project to the Firestore database. Rules deny all unspecified paths.

## Important security note
This Spark-compatible version uses manual administrator bootstrap and admin-controlled enrollment documents. Do not create an `admins/{uid}` document for an untrusted account. Do not change rules to public read/write. For automated invite-code provisioning, use a trusted backend (e.g. Cloud Functions/Cloud Run); do not put privileged credentials in the app.
