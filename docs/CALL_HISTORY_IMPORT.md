# User-approved call history import

The Collector **does not read Android's call-log database** and does not request `READ_CALL_LOG`.
Import is manually initiated and each upload requires explicit user approval.

## CSV format

Prepare a plain UTF-8 `.csv` file containing **no more than 12 records**.
The header must be exactly:

```csv
number,direction,timestamp,durationSeconds
+919876543210,INCOMING,1760000000000,45
+919876543211,MISSED,1760000060000,0
```

- `number`: phone number (up to 48 characters).
- `direction`: one of `INCOMING`, `OUTGOING`, `MISSED`, `REJECTED`, `UNKNOWN`.
- `timestamp`: call time as Unix **milliseconds** (not seconds).
- `durationSeconds`: nonnegative integer up to 86400.

This format is an **import format**, not a guaranteed export format for any particular phone app. The user must source or prepare the CSV themselves.

## How it works

1. Install the updated Collector app and sign in with the enrolled Firebase account.
2. Grant sharing consent in the Collector (you can withdraw it).
3. Open **Call History — Share Selected Records**, choose a CSV, and review each record.
4. Uncheck records you do not wish to upload.
5. Tick the per-upload approval statement and tap **Approve & Upload to Firebase**.
6. In Admin, open **Calls**, select the corresponding device, and refresh.

The call data is stored at `devices/{deviceId}/callLogs/{callId}` in Firestore, containing:
`callId`, `deviceId`, `number`, `direction`, `callTimestamp`, `durationSeconds`,
`sharedByUid`, `sharedAt`, `source=USER_IMPORTED_CSV`, `schemaVersion=1`.

## Firebase deployment

**Required:** review and publish the matching `firebase/firestore.rules` from either app repository
using Firebase Console → Firestore Database → Rules → Publish.
GitHub merges and Android APK builds do not automatically publish these rules.

Admin read access is limited to the device's enrolled administrator; Collector writes are limited
to the authenticated enrolled collector, and denied when enrollment is disabled.
The rules do not grant generic global collection read access.

## Limitations

- No automatic or background call-log scanning, no live call monitoring, and no call recording.
- The app cannot guarantee instant usage-stat sync; Android controls background scheduling.
- Withdrawal stops future uploads but does not erase records previously uploaded. Manage deletion
  according to your Firebase retention and data-deletion process.
- Existing lock/unlock event sync is separate from manually selected call history.
