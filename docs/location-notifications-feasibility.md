# Location-based notifications for todos: feasibility

> Research only. No code changes were made. Open items are listed as questions in [§10](#10-open-questions) so a follow-up pass or a maintainer can pick them up.

- **Question:** Can Todosian notify the user when they are near a place linked to a todo?
- **Codebase state:** branch `ccr-19d2b5bc-1rus53`, identical to `main` at `7a598f0` when this was written.
- **Status:** draft for maintainer review.

## 1. Verdict

**Feasible, with conditions.** The work can be done with the platform location API, without Google Play services and without any new network permission. Nothing has been run on a device yet, so three items decide whether this is worth building (details in §10):

1. **Is `LocationManager.addProximityAlert` usable on targetSdk 37?** The official reference page lists it as added in API 1, and the AOSP API surface file has no deprecation marker on it. A mirror claimed deprecation from API 34, which neither source shows. Behavior on real devices is still unverified. [Q1]
2. **Do platform proximity alerts survive reboot, process death and Doze, and what do they cost in battery?** The official geofencing guide says the system cannot recover Play-services geofences after a reboot. Nothing we read covers the platform API. [Q3, Q6]
3. **Does it need `ACCESS_BACKGROUND_LOCATION`?** On Android 11+ (minSdk is 30) the background grant happens on a Settings page, not in the permission dialog. The pages we read do not say whether the platform proximity API needs this grant at all. [Q2]

**Recommended next step:** a short device spike (Phase 0, §9). If it passes, build a v1 that notifies on entering a circle around a todo's stored coordinates. Coordinates are entered by hand or taken from the current location. There is no map and no place-name search.

## 2. Assumed reading of the request

- Each todo may have one optional place: coordinates and a radius.
- The trigger is entering that area. Exit and dwell are out of v1.
- The notification shows the todo's text and must arrive while the app is closed.
- Not in v1: place-name search, a map picker, Google services, any network access. Each one conflicts with `AGENTS.md` (§4).

These assumptions still need maintainer sign-off (Q8, Q9).

## 3. What the codebase has today

| Area | Where | Relevance |
|---|---|---|
| Permissions | `app/src/main/AndroidManifest.xml:5-6` | Only `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`. No location permission and no `INTERNET`. |
| SDK and dependencies | `app/build.gradle.kts:19-20`, `gradle/libs.versions.toml` | minSdk 30, targetSdk 37. No Play services and no map SDK. |
| Todo model | `app/src/main/java/com/isotjs/todosian/data/model/Todo.kt:23-45` | Tasks metadata fields exist. There is no location field. |
| Token regexes and `parse` | `utils/MarkdownParser.kt:22-30`, `:37` | `parse` always extracts Tasks tokens. The Tasks setting is applied by callers and writers, not by `parse`. |
| Write order | `MarkdownParser.kt:893-911` (`toSuffixString`) | Created, start, scheduled, due, done, recurrence, priority. |
| Read order | `MarkdownParser.kt:914-992` (`parseRemainder`) | Peels suffixes from the end. The recurrence rule (`:983`) swallows everything after its marker. |
| Edit with Tasks on | `MarkdownParser.kt:572-601`, esp. `:589`; `ui/category/CategoryViewModel.kt:268-298` | The suffix is rebuilt from `TasksMeta`. Any token missing from `TasksMeta` is deleted on edit. |
| Edit with Tasks off | `MarkdownParser.kt:390-414` (`editTodoText`, keeps `suffixRaw` at `:407-410`) | Suffixes survive text edits. |
| Edit sheets | `ui/category/CategoryScreen.kt:637, 700, 761`; `ui/dailyfocus/DailyFocusScreen.kt:331` | Each site copies `TasksMeta` field by field. |
| Recurrence | `MarkdownParser.kt:684-717` (`buildRecurredTaskLine`), copy list at `:700-708` | Only the listed fields are copied to the next occurrence. |
| Stable ids | `MarkdownParser.kt:787-800` (`stableTodoId`) | Built from indent, text and most metadata. Contains no file and no line. |
| Daily reminder | `notifications/DueReminderScheduler.kt:14, 30, 38-42`; `notifications/DueReminderReceiver.kt:14-31` | One `setAndAllowWhileIdle` alarm per day. Armed only when Tasks support is on (`:30`). Boot re-arms it (`DueReminderReceiver.kt:24`). |
| Reminder notification | `notifications/DueReminderNotifier.kt:30, 32, 47-83, 254-261` | Own channel and notification ID (5001). A full-vault scan on each run. A runtime permission check. |
| App start | `TodosianApplication.kt:44-66` | Syncs the reminder alarm from settings on every process start. |
| Folder access | `data/FileRepository.kt:154-164` (persisted grant), `:199-206` (`readLines`, under `fileMutex`) | A persisted read/write grant lets a receiver read files after a reboot. The daily reminder already does this. |
| External changes | `data/FileRepository.kt:360-439` (`observeMarkdownFilesChanges`) | Only runs while something collects the flow. Edits made outside the app are invisible otherwise. |
| Tests | `app/src/test/` (unit tests only); no `androidTest` directory | Device checks are manual. A debug-only receiver exists at `app/src/debug/java/com/isotjs/todosian/notifications/TestNotificationReceiver.kt` and can be reused for the spike. |
| Strings | `values/` and `values-night/` only. Notification strings at `values/strings.xml:257-275` | There are no translations to keep in sync. |

## 4. Constraints from `AGENTS.md`

- **No network permission, no analytics, no closed-source libraries** (Common Pitfalls, item 5). This rules out Play-services geofencing and any proprietary or tile-fetching map SDK.
- **Files are the source of truth.** Registration state must be rebuildable from the Markdown files. Do not cache file contents.
- **Preserve non-todo lines** and change only the todo line that changed.
- **Obsidian compatibility.** The Tasks emoji format, as documented in `AGENTS.md`, has no location token. A new token must read as harmless text in Obsidian.
- **Battery first** (AI-only guidelines, item 5). Do not poll. `AGENTS.md` also says not to move reminders back to periodic WorkManager (Notifications, rule 6).
- **No version bumps** (AI-only guidelines, item 8). This document is the only file changed.

## 5. Platform options

| # | Option | What the sources say | Fit | Verdict |
|---|---|---|---|---|
| A | Play-services geofencing (`GeofencingClient`) | Official guide: up to 100 geofences per app per device user. "The system cannot recover the geofences" after a reboot, so the app must re-register on boot. Needs `ACCESS_FINE_LOCATION` and, for target 29+, `ACCESS_BACKGROUND_LOCATION`. | Closed-source dependency, excluded by `AGENTS.md`. Absent on devices without Play services. | **Rejected** |
| B | Platform `LocationManager.addProximityAlert` (one PendingIntent per circle) | Official reference page (read 2026-11-26): added in API 1, not marked deprecated. The AOSP API surface agrees. The page says it requires `ACCESS_FINE_LOCATION` from API 17 on (`SecurityException` otherwise), but the AOSP annotation allows coarse or fine. The conflict needs a device test. Expiration in milliseconds, or -1 for none. The PendingIntent fires on both entering and exiting, with `KEY_PROXIMITY_ENTERING` set to match. Removal by PendingIntent. The page warns that brief passes may fire nothing and near misses may fire. Not documented there: whether the background-location permission is needed, persistence across reboot, and limits (Q2–Q4). | No new dependency. Should behave the same with and without Play services (to verify). | **Preferred candidate, pending spike** |
| C | Continuous polling in a location foreground service | Android 14+ needs `foregroundServiceType="location"`, the `FOREGROUND_SERVICE_LOCATION` permission and the type constant. A location foreground service cannot start from the background without `ACCESS_BACKGROUND_LOCATION`. The listed use cases are navigation and location sharing. The docs point geofence needs to the geofence API. | Battery cost, a persistent notification, and a use-case mismatch. | **Rejected as default.** Last-resort fallback only. |
| D | Periodic WorkManager checks | The minimum repeat interval is 15 minutes (AndroidX WorkManager). Timing is inexact. | Cannot notify promptly. `AGENTS.md` discourages it for reminders. | **Use only to re-sync the registry, never as the trigger** |
| E | Check when the app is opened | No background permission. Shows nearby todos inside the app. | Works everywhere, but there is no background notification. | **Viable MVP fallback** |
| F | Place names through `android.location.Geocoder` | Backed by a system geocoding service. On Google devices this is usually network-backed (to verify). | Sends place names off the device. Offline behavior varies. | **Not in v1** |
| G | Map-based picker | Google Maps is closed-source. Open tile maps fetch tiles over the network. | Conflicts with the zero-network rule. | **Not in v1** |

## 6. Proposed design (v1, pending spike)

### 6.1 Token

Proposal, not tested:

```
- [ ] Buy oat milk 📅 2026-12-01 📍 52.52000,13.40500/150
```

- Syntax: `📍 <lat>,<lon>[/<radius in metres>]`, decimal degrees, anchored at the end of the line like the other suffix tokens.
- Radius default: 150 m, to be tuned in the spike. The allowed range is open (Q9).
- **Store the raw token text** and parse it lazily. An invalid token then round-trips unchanged and is never silently dropped.
- The writer emits the token **before** `🔁`, because the recurrence rule swallows everything after its marker (`MarkdownParser.kt:29-30`). The reader checks the location token **before** the recurrence step in `parseRemainder`, so hand-written lines in the other order still parse.
- Keep the token out of `stableTodoId`. Moving a place must not change the todo's id, which would reset its collapse state (`CollapsedTodoRepository` keys on that id).

### 6.2 Flow

1. **Edit:** the user sets a place by coordinates or from the current location. The writer adds or replaces the `📍` token on that line only.
2. **Sync:** runs on app start, after any place edit, on boot, and on the daily alarm. It parses the folder, builds the desired set of places, diffs it against the registry, and adds or removes system registrations.
3. **Firing:** the system delivers the PendingIntent on entering and on exiting the circle. `LocationReminderReceiver` ignores exits in v1 (`KEY_PROXIMITY_ENTERING` is false). Otherwise it calls `goAsync()`, reads the file through `FileRepository.readLines` (which shares `fileMutex`), finds the todo by stable id, and checks that it is still open and still carries the token. If so, it posts one notification for that todo for the day.
4. **Boot:** `BOOT_COMPLETED` re-adds everything, because the system may drop registrations (Q3).

### 6.3 Registry (derived, not authoritative)

- **Key:** `(file URI, Todo.id)`. The id is unique per file only (`stableTodoId` has no file component), so the file must be part of the key.
- **Value:** request code, latitude, longitude and radius. Stored in private prefs, the same way `DueReminderNotifier` keeps its state (`DueReminderNotifier.kt:34-36`).
- The registry can be deleted and rebuilt from the files at any time. It is a cache of registrations, not a second data store.
- The PendingIntent is an explicit intent to a new non-exported receiver. Its data URI is unique per key, so `removeProximityAlert` can match it later. This follows the pattern in `DueReminderScheduler.kt:58-68`.
- **Diff:** `desired - registered` is added, `registered - desired` is removed, and changed values are removed and re-added. Make this a pure function with unit tests, like `nextTriggerMillis` (`DueReminderScheduler.kt:52-56`).
- **Expiration:** the reference page accepts `-1` for no expiration. The default proposal is no expiry, with removal on sync. Confirm against Q3 and Q4.

### 6.4 Notification

- A new channel, separate from `todosian_due_reminders` (`DueReminderNotifier.kt:30`), and new notification IDs rather than 5001.
- Strings go in `values/strings.xml`. There are no translations to update.
- Tapping the notification opens `MainActivity`, as in `DueReminderNotifier.kt:92-99`.
- Skip posting when the feature is off, the folder is gone, the POST_NOTIFICATIONS permission is denied (the check at `DueReminderNotifier.kt:254-261`), or the todo is closed or no longer carries the token.
- Dedupe: one notification per todo per day, persisted the same way as the due reminder (`DueReminderNotifier.kt:191-211`).

### 6.5 Permissions and onboarding

- Ask for location only when the user first sets a place, not at install.
- The reference page says fine location is required (`SecurityException` otherwise, from API 17 on). The AOSP annotation also allows coarse location. Plan for fine location and confirm the actual behavior in the spike. Declare coarse location too, for the Android 12+ approximate/precise model.
- `ACCESS_BACKGROUND_LOCATION` only if Q2 shows the platform API needs it. If it does, explain the grant first and send the user to the Settings page, because on Android 11+ the grant happens there rather than in the dialog.
- The background page says "the user's choices in the location permissions dialog also apply to background location". An approximate-only grant therefore carries over to background access. Require precise location, or warn that reliability drops.
- No foreground service in v1. Manifest additions are limited to the permissions above, one non-exported receiver, and no `INTERNET`.

### 6.6 Capturing a place

- Latitude and longitude fields with validation.
- "Use current location": a one-shot fix through `LocationManager.getCurrentLocation`. This is API 30+ and must be verified (Q14). No continuous tracking.
- No map and no search in v1 (Q13).

### 6.7 Battery and privacy

- The design polls nothing. The real cost depends on what the platform does for proximity alerts (Q6).
- Re-register only when the desired set changes. A full-vault scan on every cold start costs time on large vaults. Throttle it, or compare a cheap fingerprint first (Q12).
- The app cannot force GPS-only for proximity alerts, because provider choice belongs to the platform. The network provider may send Wi-Fi and cell data to the OS location backend (Q16).
- Coordinates are plain text in the vault. They sync to every device that syncs the folder, and into any Git history or backup of it. Options are in Q10.

## 7. Round-trip hazards

Any new token that is not carried through `TasksMeta` will be lost. Three paths rebuild the line from `TasksMeta` rather than from the raw text:

1. **Edit with Tasks on.** `MarkdownParser.editTodo` replaces the whole suffix from `meta` (`MarkdownParser.kt:589`). The edit sheets copy each field by hand (`CategoryScreen.kt:637, 700, 761`; `DailyFocusScreen.kt:331`). A location field that is not copied there is deleted on the first text edit.
2. **Recurrence.** `buildRecurredTaskLine` copies a fixed list of fields (`MarkdownParser.kt:700-708`). A recurring todo would lose its place on the next occurrence.
3. **Recurrence swallows the tail.** `recurrenceSuffixRegex` captures `(.+?)\s*$` (`MarkdownParser.kt:29-30`). A token written after `🔁` becomes part of the rule.

Guard rails: unit tests that round-trip each path, and a test that non-todo lines stay byte-for-byte unchanged (the edge-case list in `AGENTS.md`).

## 8. Test plan

**Unit tests** (`app/src/test/`):
- Parse and write the location token, including on lines that also carry `🔁`, `⏫` and `📅`, and lines where the tokens are in hand-written order.
- Invalid coordinates and radii round-trip unchanged.
- Text edits keep the place, with Tasks support on and off.
- Recurrence keeps the place.
- Changing the place does not change `Todo.id`.
- Registry diff: add, remove, change, no-op.
- Unicode and emoji text, very long lines, empty files, and files with only non-todo content (the `AGENTS.md` edge-case list).

**Manual** (device or emulator, since there are no instrumentation tests):
- Place set by coordinates and by current location. Notification received with the app killed.
- Reboot, then confirm the registry is re-added.
- Permission denied at each step. The feature degrades without crashing.
- Folder changed or removed. Sync cleans up.
- External edit from a text editor or Syncthing. Picked up at the next sync trigger.
- Emulator movement with `adb emu geo fix <lon> <lat>`.
- Battery: `adb shell dumpsys batterystats` and `adb shell dumpsys location` over 24 hours, with 10 places and with 50.
- Fire a test notification through the existing debug-only receiver pattern (`TestNotificationReceiver`).

## 9. Phased plan and touch points

| Phase | Scope | Size |
|---|---|---|
| 0. Spike | Debug-only receiver and registration. Answer Q1–Q7 on API 30, on a recent API, on the latest API, and on one OEM device. Write the results back into this file. | Small |
| 1. Data | Token parse and write. `Todo` and `TasksMeta` fields. Copies in the edit sheets and in recurrence. Unit tests. | Medium |
| 2. Capture UI | Coordinate fields, "use current location", foreground permission flow. | Medium |
| 3. Engine | Registry, sync diff, receiver, boot re-arm, notification channel, strings. | Medium |
| 4. Background and settings | Background permission flow if Q2 requires it. Settings toggle. README updates (Supported Markdown, Privacy). | Small to medium |

Likely touch points: `app/src/main/AndroidManifest.xml`, `utils/MarkdownParser.kt`, `data/model/Todo.kt`, `ui/category/CategoryScreen.kt`, `ui/category/CategoryViewModel.kt`, `ui/dailyfocus/DailyFocusScreen.kt`, `ui/dailyfocus/DailyFocusViewModel.kt`, `TodosianApplication.kt`, new files under `notifications/`, `ui/settings/SettingsTasksScreen.kt`, `values/strings.xml`, tests under `app/src/test/`, and `README.md`. No version bump (`AGENTS.md`).

## 10. Open questions

The follow-up pass should answer these. The blockers come first.

**Blockers (answer in Phase 0)**

- **Q1. Status of `addProximityAlert`.** **Resolved.** The official reference page (https://developer.android.com/reference/android/location/LocationManager) says "Added in API level 1", with no deprecation marker. The AOSP API surface file (`location/api/current.txt` on the `main` branch, read 2026-11-26) has no `@Deprecated` on `addProximityAlert`, `removeProximityAlert` or `getCurrentLocation`. A mirror (https://developer.android.google.cn/reference/android/location/LocationManager) claimed deprecation from API 34, which neither source shows. `main` is AOSP's development branch, so re-check against a released branch before shipping. Runtime behavior is still for the spike.
- **Q2. Permissions.** The reference page says fine location is required (`SecurityException` without `ACCESS_FINE_LOCATION`, from API 17 on). The AOSP annotation allows coarse or fine instead, so test which one is enforced. Still open: does the API also need `ACCESS_BACKGROUND_LOCATION` when the app is not in the foreground on API 29+? The reference page never names that permission, and the background-location page does not cover this API. What happens with an approximate-only grant is also open.
- **Q3. Persistence.** Do registrations survive a reboot, a force-stop, process death and Doze? The design assumes they do not, and re-adds them on boot.
- **Q4. Limits.** How many concurrent proximity alerts can one app hold? The reference page does not say. The Play-services API allows 100 per app per user.
- **Q5. PendingIntent mutability.** The system adds `KEY_PROXIMITY_ENTERING` to the intent. Does `FLAG_IMMUTABLE` work on API 31+, or must the intent be `FLAG_MUTABLE`? Check on a device.
- **Q6. Battery.** What do 10 and 50 alerts cost over 24 hours? Which provider does the platform use, and does it keep GPS on?
- **Q7. Latency.** How long after entering does the callback arrive? The Play-services guide says that when the app is backgrounded (Android 8.0+), the device "responds to geofencing events every couple of minutes". That sets an expectation for the platform API too, but this needs verification. The platform reference page also warns that brief passes may fire nothing and near misses may fire, so the spike should test short visits and grazing the edge of the circle.

**Product decisions (maintainer)**

- **Q8. Trigger semantics.** Enter only, or enter and exit? Once per day per todo, or on every entry?
- **Q9. Scope and radius.** Is one place per todo enough? What is the default radius and the allowed range? The proposal is 150 m by default.
- **Q10. Privacy of coordinates.** Options: (a) raw coordinates in the note (the proposed default), (b) named places in one shared note, or (c) rounded coordinates. Rounding to 4 decimal places is about 11 m of latitude, so the radius has to absorb it.
- **Q11. Gating.** Should location reminders sit under the Tasks setting or have their own "Location reminders" setting? The daily alarm only exists when Tasks support is on (`DueReminderScheduler.kt:30`).
- **Q12. Freshness.** How stale may registrations be after external edits? Options: sync on app start, boot, the daily alarm and place edits (proposed); WorkManager at 15 minutes or more (inexact, discouraged); an observer that runs only while the app is open.
- **Q13. Map.** Confirm that manual coordinates and "use current location" are enough for v1, with no map SDK.
- **Q14. Current location.** `getCurrentLocation` is documented as "Added in API level 30" and requires coarse or fine location, so it is available at minSdk 30. Still open: whether a one-shot fix is acceptable UX.
- **Q15. Over-limit policy.** When more todos have places than the platform allows, do we prioritize the nearest or the soonest, or refuse with a message?
- **Q16. Network location.** On Google devices, does the platform network provider send Wi-Fi and cell data to Google's location service? The answer affects the privacy wording.
- **Q17. Background UX.** If Q2 requires the background grant, is an explanation screen plus a Settings deep link acceptable?
- **Q18. Fused provider.** `LocationManager.FUSED_PROVIDER` is documented as "Added in API level 31". Still open: is it usable without Play services? This matters only if Q6 shows that the GPS-only choice costs too much.

## 11. Documentation drift noticed

- `AGENTS.md:268` says targetSdk 36. `app/build.gradle.kts:20` sets 37. A maintainer should update `AGENTS.md`. This PR does not touch it.
- `AGENTS.md` says Tasks tokens should be ignored when the setting is off. `MarkdownParser.parse` always extracts them (`:37`), and writers honor the setting. A location token should follow the actual behavior.

## 12. Sources

Official pages read on the date of this study:

- Geofencing: https://developer.android.com/develop/sensors-and-location/location/geofencing
- Background location: https://developer.android.com/develop/sensors-and-location/location/permissions/background
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types

Official reference page, read on the same date (`addProximityAlert`, `removeProximityAlert`, `getCurrentLocation`, `FUSED_PROVIDER`): https://developer.android.com/reference/android/location/LocationManager

AOSP API surface file (cross-check of deprecation status, see Q1): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/location/api/current.txt

Mirror, used only as a lead and not as evidence: https://developer.android.google.cn/reference/android/location/LocationManager

Repository: `AGENTS.md`, and the file and line references above.
