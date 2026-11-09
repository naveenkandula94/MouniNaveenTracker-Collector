# MouniNaveenTracker: live microphone and call-history sharing

## Features and limitations

- **Live audio**: Collector owner explicitly checks the consent box, grants `RECORD_AUDIO`, and presses **Start live microphone**. Admin logs in, selects the enrolled device under **Live audio**, selects **Find live session**, then **Listen now**. WebRTC transmits audio, not Firebase.
- Collector must remain in the foreground for this version. Switching between Collector tabs now retains an active session; closing or backgrounding the app is not guaranteed to keep microphone access. No background microphone foreground service is installed. The Android system microphone privacy indicator remains visible.
- **Call history**: The Collector can attempt to read its own phone's latest 12 call records via the Android `CallLog.Calls` provider. After a local preview and selection, the owner must approve the chosen records before sharing them with Admin through Firestore. No automatic background copying of private call history.
- Modern Android treats `READ_CALL_LOG` as a **hard restricted permission**. The installer must allowlist it; the user cannot bypass that restriction by accepting an in-app consent checkbox. If Android denies the permission, the Collector cannot read local call history through the Android call-log provider.
- Audio connection uses WebRTC STUN. A TURN server must be configured for reliable connectivity on some mobile networks. No audio recordings are stored in Firebase. SDP and ICE signaling are temporarily stored in Firestore until removed by an administrator.
- This is a preview implementation. Validate real-device signaling, reconnection and session cleanup before production deployment.

## Firebase Firestore structure

```
admins/{adminUid}: role = "admin"
devices/{deviceId}: adminUid, collectorUid, displayName, createdAt
devices/{deviceId}/callLogs/{callId}:
  callId, deviceId, number, direction, callTimestamp,
  durationSeconds, sharedByUid, sharedAt, source, schemaVersion
devices/{deviceId}/liveAudio/{sessionId}:
  collectorUid, deviceId, status, createdAt, offer, answer, endedAt
devices/{deviceId}/liveAudio/{sessionId}/publisherIce/{id}
devices/{deviceId}/liveAudio/{sessionId}/listenerIce/{id}
```

## Firebase Console setup

1. Open https://console.firebase.google.com/project/mouninaveentracker/firestore/databases/-default-/data
2. In **Firebase Authentication > Users**, locate your Admin account UID.
3. Create collection `admins`, document ID **the exact Admin Auth UID**, field `role` (string) = `admin`. If you previously deleted the `admins` collection, re-create it here.
4. In **Cloud Firestore > Rules**, paste the COMPLETE contents of this repository's `firebase/firestore.rules` file from the target branch and press **Publish**. Do not set `allow read, write: if true`.
5. Install Collector and Admin builds registered in the same Firebase project. Admin enrolls the Collector Firebase Auth UID in the **Devices** tab, recreating the `devices` collection if deleted.
6. On Collector, sign in and grant sharing consent. Grant microphone permission only to start a user-controlled audio session. Under **Phone call log**, request permission, review the displayed records, tick the approval statement, and upload.
7. In Admin, open **Calls**, select the device and Refresh. For live microphone, open **Live audio** and join the already started session.
8. If the microphone won't connect across the internet, set up an authorized TURN relay; STUN alone cannot guarantee NAT traversal.

## Permission troubleshooting

`READ_CALL_LOG`: if denied or not allowlisted by the installer, do not attempt alternate hidden methods to access call history. Verify the app's legitimate Phone role or permitted exception and Android/Play policies. A regular Play-distributed Collector app is not guaranteed this access.

`RECORD_AUDIO`: request while Collector is visible. If denied, open Android Settings > Apps > Collector > Permissions > Microphone. No remote Admin microphone activation is implemented.

## Data deletion

In Firebase Console > Firestore Data, remove `devices` with its subcollections to delete all device records and signaling. This also deletes enrollments; re-enroll afterward. Preserve `admins` if you still need Admin authorization.

The corresponding CLI operation is:

```bash
firebase login
firebase firestore:delete devices --recursive --project mouninaveentracker --database "(default)"
```

This is destructive. Export data first if needed. Keep Collector sharing off during cleanup.

## Simplified navigation (current feature branch)

Collector tabs: **Home**, **Calls**, **Live audio**, **Account**, **Logs**. The old CSV import control was removed. The local diagnostic log is available in Logs.

Admin tabs: **Home**, **Calls**, **Live audio**, **Settings**. Diagnostics and the optional device pairing UI are under Settings. The same authenticated user may be used for a controlled single-account test, but separate accounts are more secure for actual sharing.

If Admin shows an empty live session list while Collector reports CLOSED, start a fresh Collector session from Live audio. The Apps must have updated Firestore rules deployed. Check Collector Logs for Firebase signaling errors. STUN alone is not sufficient on all mobile networks.
