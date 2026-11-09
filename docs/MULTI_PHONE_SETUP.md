# Personal_App: adding a second phone safely

This build renames the installed Android Collector launcher to **Personal_App** and uses a teal phone/lock icon. The Firebase project and application ID are unchanged.

## First use on OnePlus and on a second Android phone
1. In Admin_App > Settings > Add or manage phones, enter a distinctive phone name, such as **OnePlus** or **Samsung S24**; optionally supply a different Collector Authentication UID. Press **Add phone**. Each enrolled phone gets a unique `devices/{deviceId}` document.
2. Install Personal_App on that particular phone. Sign in to its Firebase Authentication account and open **Account**.
3. Under **This phone**, tap **Refresh linked phones**, then **Use this phone** beside the correct enrolled name. No enrollment is selected automatically, even if only one is available. You must explicitly tap **Use this phone**.
4. Open **Calls**. If private backup is enabled already, the worker automatically uses the selected enrollment. Otherwise, tap **Enable automatic private backup** once and grant Android call-log permission when eligible.
5. Keep **Home > Sharing enabled** to sync lock/unlock events, and start microphone explicitly in **Live audio** when wanted.

**Do not choose OnePlus on your Samsung phone.** These installations share the same Firebase account but must have separate device IDs. Different phones can have identical local Android call-log IDs: per-device partitioning prevents records overwriting each other.

## Firestore paths

New phone-scoped automatic backups:

```
users/{ownerUid}/deviceCallBackups/{deviceId}/calls/{callId}
```

Original backups, collected before multi-phone support, remain at:

```
users/{ownerUid}/callBackups/{callId}
```

Legacy call records do **not** contain a reliable physical device ID. In Admin_App, they appear under **Legacy records** and are deliberately not attributed to a particular phone. After installing this version, the first new device-specific run re-reads that phone's available call history once and then performs incremental updates. This may temporarily duplicate call data across the legacy and new collections; delete the legacy records only after checking the new device-specific backup.

Per-device metadata and activity:

```
devices/{deviceId}/usageEvents/{eventId}
devices/{deviceId}/liveAudio/{sessionId}
devices/{deviceId}/syncLogs/{logId}
```

`syncLogs` includes only backup count/status/time, never phone numbers, call contents, or microphone audio.

## Firebase rules and Android constraints

Publish the **complete** `firebase/firestore.rules` from the updated Admin branch **or** this Collector branch (both share new permission blocks). Those rules restrict private calls to the owner UID, keep event data scoped by device enrollment, and allow Admin to read device status metadata. 

The existing `admins/{uid}` document still requires `role = admin`.

Android restricts `READ_CALL_LOG`. Existing call permissions may survive installing the new build, but a clean install may require re-granting eligible permissions. WorkManager's minimum periodic interval is 15 minutes and Android may defer execution. The Collector also checks changes while its Activity is visible. Guaranteed 10-second background synchronization is not provided.

To stop backup, tap **Calls > Disable backup** or **Home > Stop sharing**. To delete this device's private cloud backup, use **Calls > Delete this phone's private call backups**; disabling first prevents immediate recreation. Old legacy records can be deleted from the Admin_App **Legacy records** owner view.

## Automatic lock/unlock checks

With sharing consent, Usage Access and an explicitly selected phone, Personal_App checks usage-keyguard events automatically every **15 seconds while its Activity is foreground/visible**. The process-alive screen-on/off/user-present system broadcasts can also queue a check. This is **not** a guaranteed 15-second background service after the screen turns off; periodic WorkManager background checks are Android-managed and have a minimum 15-minute repeat interval.

A successful worker uploads immutable lock/unlock events under `devices/{deviceId}/usageEvents`, then updates `lastSyncAt` and `lastSyncStatus` at most once per minute unless new lock events occur. It records non-sensitive lock sync metadata in `devices/{deviceId}/syncLogs`. If Android exposes no keyguard event for a particular device, no synthetic lock event is invented.

The **manual phone selection is mandatory**. For upgraded older installs, the new app requires fresh confirmation because previous versions silently selected the only enrolled phone.

## Live microphone sharing after minimizing and locking

1. In Personal_App sign in, manually select the correct physical device in Account, enable Home sharing and open Live.
2. Approve that specific microphone session and grant Android RECORD_AUDIO. On Android 13+, allow notification permission as well so the ongoing Stop notification is visible.
3. Tap Start. An explicit foreground service of type `microphone` owns the WebRTC peer. You can switch apps and lock the display without destroying the active service; the notification includes a Stop action.
4. In Admin_App select the same named phone, open Live and tap Listen. Incoming audio level and speaker/mute output controls appear.
5. To end, use Personal_App > Live > Stop, Home > Stop sharing, Sign out, or the persistent notification's Stop action.

If WebRTC reports DISCONNECTED or FAILED during this owner-started session, Personal_App waits briefly and attempts to create up to three replacement offers; Admin attempts to rejoin within two minutes of its initial join. Networking/ICE/TURN limitations and OEM battery management may still prevent recovery. This is not a guarantee that every phone remains connected.

**No invisible or reboot-persistent microphone capture.** The service returns `START_NOT_STICKY`. Android may stop it under memory pressure; a force-stop, crash or reboot requires a fresh owner-started session. A previously granted RECORD_AUDIO permission does not authorize starting a microphone foreground service from BOOT_COMPLETED on Android 14+. No background microphone activation is implemented.

## SMS, calls and screen events while app is in background

Personal_App maintains an independent WorkManager periodic backup for calls, owner-opt-in SMS and usage-keyguard events. Each worker stores a last processed timestamp and reads newer records on subsequent runs; it does **not** rescan all available records every 15 seconds. Periodic WorkManager runs at a **minimum scheduled interval of 15 minutes**, and Android's doze, battery policies and connectivity can defer delivery. When Personal_App is visible, SMS/call content observers and 15-second lock checks can queue earlier work. There is no promise of a 15-second background cadence. User may revoke sharing or disable each backup from the app; the Admin phone does not read Android's local call/SMS providers.
