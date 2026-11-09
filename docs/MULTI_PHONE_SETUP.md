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
