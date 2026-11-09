# Restore Personal_App and Admin_App: microphone, backup, SMS, screen activity

## Before installing

These changes are on the draft PR branches **feature/fix-startup-sync-and-microphone** in both repositories, not on `main`. Install **both** debug APKs from the **latest successful** GitHub Actions run on this exact branch.

**Cloud Firestore rules must be deployed separately.** The latest `firebase/firestore.rules` in the Collector and Admin PRs is identical. Paste the entire file into Firebase Console > Firestore Database > Rules and click **Publish**. Code commits do not automatically deploy Firebase rules. Never replace this file with an `allow read, write: if true` test rule.

## Important phone selection

The account has two linked devices named **oneplusnaveen** and **vivimini**. Each physical installation must explicitly select its *own* enrollment in Personal_App > Account > This phone. On the OnePlus phone choose **oneplusnaveen**. On the Vivo phone choose **vivimini**. In Admin_App choose that same device in the selected-device switcher. One device's cloud paths are **not** the other device's paths.

Do **not** clear app storage or uninstall to fix a sync error; that resets local account/device binding and backup cursors.

## Verify calls and SMS

1. Personal_App > Access: grant Usage Access; ensure backup enabled individually in Calls / Messages.
2. Tap **Check sync now**, which now schedules a fresh explicitly requested job for calls, SMS, and screen events without waiting for existing retry/backoff work.
3. Tap **Refresh** and review **Calls / Messages / Lock** timestamps and **Needs attention**. `7 of 7 configured` means access is enabled; it does not prove uploads succeeded.
4. Admin_App > select the matching phone. Calls for that device are under `users/{authUid}/deviceCallBackups/{deviceId}/calls`; SMS under `users/{authUid}/deviceSmsBackups/{deviceId}/messages`. The earlier owner-only backup is in `users/{authUid}/callBackups` and appears under the Admin app's **Earlier records** selection.
5. A linked Admin with a *different* Firebase Authentication UID cannot read these private owner backups. Sign in using the same owning UID; don't weaken the rules.

Android limits recurring background WorkManager timing to a minimum of 15 minutes. App-visible checks and process-scoped broadcast observers can be more frequent, but Android can delay or stop background work. `READ_SMS` and `READ_CALL_LOG` might be restricted on certain Android installations.

## Verify microphone

1. On Personal_App on the selected phone, open **Live** and tick the owner approval for the session. Press **Start microphone session**.
2. The screen must show **Microphone offer published**, not just `Starting…`. A persistent Android microphone notification includes **Stop sharing**.
3. Admin_App > select the same device > **Live** > **Refresh session** > **Listen now** once offered.
4. If Personal_App shows **Firebase enrollment/session timed out**, check Wi-Fi/mobile data, selected enrollment, signed-in owner and **published Firestore rules**.
5. If Personal_App shows **No microphone offer after 45 seconds**, restart the session and collect diagnostic log entries; the WebRTC/Firebase signaling step failed. An older stopped service will no longer close a newer session.
6. If Admin shows `No published offer (latest STARTING)`, the Collector session has not published SDP. If it shows `ANSWERED`, a prior listener already consumed that offer; have the owner explicitly Stop and Start a new session.
7. A `CONNECTED` WebRTC state without sound requires checking audio output, local input meter, and connectivity; STUN alone cannot traverse every carrier NAT. Production cross-network reliability may require authenticated TURN relay credentials.
8. Switching apps or locking the phone may preserve a user-started foreground service, but force-stop or reboot stops microphone capture. It will **not** be secretly restarted.

## Verify screen state

Screen activity primarily uses Android's UsageStats `KEYGUARD_SHOWN/HIDDEN`. The new fallback queues device-scoped actual `SCREEN_OFF`, `SCREEN_ON` and `SCREEN_UNLOCKED` events while the Personal_App process is alive. Turning the screen off is **not** asserted to be a secure lock. Admin labels them accordingly. The new event types require publishing this branch's Firestore rules first.

## How to report a remaining failure

Send the **red Needs attention** text under Personal_App > Access, the latest **AUDIO / LOCK / SMS / BACKUP** entries from Personal_App > Logs, and the Admin Live status after pressing Refresh session. Do not share passwords, full SMS bodies or phone numbers in diagnostic screenshots. Device-model and timestamps are sufficient.
