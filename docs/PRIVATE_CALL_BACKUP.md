# One-time opt-in private call-history backup

This version gives the **phone owner** an automatic backup of existing and future Android call records.
It does NOT automatically send the owner's full history to a separately authorized Admin account.

## Collector workflow

1. Install the new Collector APK, open **Account**, and sign in to your Firebase Authentication account.
2. Open **Calls** and tap **Enable automatic private backup** once.
3. Android asks for `READ_CALL_LOG` only if it is not already granted. This permission is restricted on modern Android; an installer/role may not be eligible to grant it.
4. The first enabled run scans the Android call-log database and writes matching records into your **private** Firebase collection.
5. New calls are detected via a `ContentObserver` while the Collector Activity is visible. WorkManager also schedules periodic network-connected checks (minimum interval 15 minutes; Android can delay execution). It is **not guaranteed instantaneous** and can be interrupted by force-stop, uninstall, account sign-out, or permissions being revoked.
6. To pause backups, tap **Disable backup** on Calls. **Stop sharing** on Home also disables future backups.
7. To permanently erase private backup records, use **Delete all my private call backups**, then confirm.

No CSV import or repeated record-by-record upload is required for the private backup.
The existing separate owner-approved call-sharing data, if any, remains under `devices/{deviceId}/callLogs` and is not automatically populated by this backup.

## Firestore data layout

```text
users/{OWNER_FIREBASE_AUTH_UID}/callBackups/call_{AndroidCallId}
  callId: string
  ownerUid: string
  number: string
  direction: string
  callTimestamp: timestamp
  durationSeconds: int
  source: "OWNER_PRIVATE_BACKUP"
  schemaVersion: 1
```

The `firebase/firestore.rules` file enforces read/write/delete only for the authenticated owner UID at this path.
**Important:** If you sign in to Admin and Collector using the *same UID*, Firebase cannot distinguish those two apps as different identities.
For actual separation between people, use distinct authenticated accounts.

## Firebase deployment

1. In Firebase Authentication, ensure the Collector account exists and email/password sign-in is enabled.
2. In Firebase Console, open your project `mouninaveentracker` → **Firestore Database** → **Rules**.
3. Copy the complete `firebase/firestore.rules` from this feature branch, paste it into the editor, and **Publish**. Do not enable permissive global rules.
4. Firebase automatically creates `users/{uid}/callBackups` upon the first successful private backup. Do not manually create documents.
5. Check **Firestore Database → Data → users → your UID → callBackups** for new records.
6. If backup fails, open **Collector → Logs** to inspect consent, permission, or Firebase errors.

## Important limitations

- `READ_CALL_LOG` is a restricted Android permission and might not be grantable to a regular monitoring app. This must be verified on the actual device.
- Changes can be observed while the Collector app is running. Android may defer periodic background WorkManager jobs. Always-on, guaranteed-live delivery is not provided.
- The backup uses Android call-provider IDs to avoid duplicate documents.
- Existing phone call records are backed up, but deleting records from the phone does not automatically delete their Firebase backup copies; use Delete All to remove backup data.
- Firebase project billing, policies, access restrictions, and data retention remain the account owner's responsibility.
- No microphone recordings are stored by this feature. Live audio is a separate explicitly started feature.
