# Location-based notifications for todos: feasibility

> Research only. No code changes were made. **Revision 2.** The first draft was audited against primary sources (AOSP source, the Obsidian Tasks plugin source, official Android pages), and several conclusions changed. [§13](#13-audit-of-the-first-draft) lists what changed. Questions are tracked in [§12](#12-questions).

- **Question:** Can Todosian notify the user when they are near a place linked to a todo?
- **Codebase state:** branch `ccr-19d2b5bc-1rus53`, identical to `main` at `7a598f0` apart from this document.
- **Evidence level:** source reading and official documentation, read on 2026-11-26. Nothing was run on a device. Items marked *inferred* are conclusions drawn from source, not observed behavior.
- **See also:** [the Obsidian plugin side](obsidian-tasks-place-field.md), on how the Tasks plugin fork can read and write the same token.

## 1. Verdict

**Feasible without Play services, a new dependency or a network permission, but not as an arrival alert.** The platform can tell Todosian that the device entered a saved area. It does so late (up to about half an hour), it can miss short visits, and it only works with the "Allow all the time" location permission. Three facts, all read from AOSP source, decide this (evidence in [§6](#6-how-the-platform-proximity-alert-behaves)):

1. **A 30-minute sampling floor.** The system samples location for proximity alerts at most every 30 minutes by default (`location_background_throttle_proximity_alert_interval_ms`), and an alert only fires when a fix shows the change. An ordinary app cannot change this. The official background-limits page agrees in general terms: background apps get location "only a few times each hour".
2. **Background location is effectively required.** The system re-checks a registration's location permission when the app moves between foreground and background (Android 12+), or on every evaluation (Android 11). *Inferred:* without "Allow all the time", alerts stop while the app is not in the foreground, and registering from a boot receiver does not work.
3. **The existing PendingIntent pattern would crash.** The app's reminders use `FLAG_IMMUTABLE`. `addProximityAlert` throws `IllegalArgumentException` for an immutable PendingIntent on apps targeting Android 12 or later, because the system must add an extra to the intent. Google's own geofencing sample says the same: "Starting on Android S+ the pending intent has to be mutable."

What the user gets, by tier:

| Tier | What the user gets | Permission | Delay | Fits `AGENTS.md`? |
|---|---|---|---|---|
| 0. Nearby view | Todos with a place, sorted by distance, when the app is open | Foreground location | None | Yes |
| 1. Background alerts (platform) | A notification after the system sees the device enter the area | Fine location plus "Allow all the time" | About 30 minutes or more. Short visits can be missed. | Yes |
| 2. Arrival alerts | A notification within minutes | Same as tier 1 | Minutes | No. Needs Play services (closed-source) or a location foreground service (battery cost, permanent notification). |

**Recommendation:**

1. Run a short device spike (Phase 0, [§11](#11-phased-plan-and-touch-points)) to confirm §6 on a Google device and on a non-Google ROM.
2. Build tier 0 first. It needs no background permission, and it proves the token format and the place-entry UI.
3. Add tier 1 only if the maintainers accept the delay and the permission, and the spike agrees. Present it in the UI as best-effort.
4. Do not build tier 2 under the current rules.

Tier 1 suits todos tied to somewhere the user stays or passes often (home, office, a neighbourhood) with a radius of a few hundred metres. It does not suit "buy milk when I walk into the shop".

## 2. Assumed reading of the request

- Each todo may have one optional place: coordinates and a radius.
- The trigger is entering that area. Exit and dwell are out of v1.
- In tier 1 the notification shows the todo's text and must arrive while the app is closed.
- Not in v1: place-name search, a map picker, Google services, any network access. Each one conflicts with `AGENTS.md` (§4).

These assumptions still need maintainer sign-off (Q8, Q9, Q21).

## 3. What the codebase has today

| Area | Where | Relevance |
|---|---|---|
| Permissions | `app/src/main/AndroidManifest.xml:5-6` | Only `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`. No location permission and no `INTERNET`. |
| SDK and dependencies | `app/build.gradle.kts:19-20`, `gradle/libs.versions.toml` | minSdk 30, targetSdk 37. No Play services and no map SDK. |
| Todo model | `app/src/main/java/com/isotjs/todosian/data/model/Todo.kt:23-45` | Tasks metadata fields exist. There is no location field. |
| Token regexes and `parse` | `utils/MarkdownParser.kt:22-30`, `:37` | `parse` always extracts Tasks tokens. The Tasks setting is applied by callers and writers, not by `parse`. |
| Write order | `MarkdownParser.kt:893-911` (`toSuffixString`) | Created, start, scheduled, due, done, recurrence, priority. |
| Read order | `MarkdownParser.kt:914-992` (`parseRemainder`) | Peels suffixes from the end, like the Obsidian Tasks plugin. The recurrence rule (`:983`) swallows everything after its marker. |
| Edit with Tasks on | `MarkdownParser.kt:572-601`, esp. `:589`; `ui/category/CategoryViewModel.kt:268-298` | The suffix is rebuilt from `TasksMeta`. Any token missing from `TasksMeta` is deleted on edit. |
| Edit with Tasks off | `MarkdownParser.kt:390-414` (`editTodoText`, keeps `suffixRaw` at `:407-410`) | Suffixes survive text edits. |
| Edit sheets | `ui/category/CategoryScreen.kt:637, 700, 761`; `ui/dailyfocus/DailyFocusScreen.kt:331` | Each site copies `TasksMeta` field by field. |
| Recurrence | `MarkdownParser.kt:684-717` (`buildRecurredTaskLine`), copy list at `:700-708` | Only the listed fields are copied to the next occurrence. |
| Stable ids | `MarkdownParser.kt:787-800` (`stableTodoId`) | Built from indent, text and most metadata. Contains no file and no line. |
| Collapse state | `data/CollapsedTodoRepository.kt` | Keyed by category URI and todo id, so a changing id resets it. |
| Daily reminder | `notifications/DueReminderScheduler.kt:14, 30, 38-42`; `notifications/DueReminderReceiver.kt:14-31` | One `setAndAllowWhileIdle` alarm per day. Armed only when Tasks support is on (`:30`). Boot re-arms it (`DueReminderReceiver.kt:24`). |
| Reminder notification | `notifications/DueReminderNotifier.kt:30, 32, 47-83, 254-261` | Own channel and notification ID (5001). A full-vault scan on each run. A runtime permission check. |
| PendingIntents in use | `DueReminderScheduler.kt:58-68`, `DueReminderNotifier.kt:92-99` | Both use `FLAG_IMMUTABLE`. A location PendingIntent cannot (§6, row 1). |
| App start | `TodosianApplication.kt:44-66` | Syncs the reminder alarm from settings on every process start. |
| Folder access | `data/FileRepository.kt:154-164` (persisted grant), `:199-206` (`readLines`, under `fileMutex`) | A persisted read/write grant lets a receiver read files after a reboot. The daily reminder already does this. |
| External changes | `data/FileRepository.kt:360-439` (`observeMarkdownFilesChanges`) | Only runs while something collects the flow. Edits made outside the app are invisible otherwise. |
| Tests | `app/src/test/` (unit tests only); no `androidTest` directory | Device checks are manual. A debug-only receiver exists at `app/src/debug/java/com/isotjs/todosian/notifications/TestNotificationReceiver.kt` and can be reused for the spike. |
| Strings | `values/` and `values-night/` only. Notification strings at `values/strings.xml:257-275` | There are no translations to keep in sync. |

## 4. Constraints from `AGENTS.md`

- **No network permission, no analytics, no closed-source libraries** (Common Pitfalls, item 5). This rules out Play-services geofencing and any proprietary or tile-fetching map SDK.
- **Files are the source of truth.** Registration state must be rebuildable from the Markdown files. Do not cache file contents.
- **Preserve non-todo lines** and change only the todo line that changed.
- **Obsidian compatibility.** The Tasks emoji format, as documented in `AGENTS.md`, has no location token. A new token must read as harmless text in Obsidian, and must not stop the Tasks plugin from reading the tokens it does know (§7.1).
- **Battery first** (AI-only guidelines, item 5). Do not poll. `AGENTS.md` also says not to move reminders back to periodic WorkManager (Notifications, rule 6).
- **No version bumps** (AI-only guidelines, item 8). This document is the only file changed.

## 5. Platform options

| # | Option | What the sources say | Fit | Verdict |
|---|---|---|---|---|
| A | Play-services geofencing (`GeofencingClient`) | Official guide: up to 100 geofences per app per device user. A backgrounded app gets events "every couple of minutes". "The system cannot recover the geofences" after a reboot, so the app must re-register on boot. Needs `ACCESS_FINE_LOCATION` and, for target 29+, `ACCESS_BACKGROUND_LOCATION`. | Closed-source dependency, excluded by `AGENTS.md`. Absent on devices without Play services. | **Rejected** |
| B | Platform `LocationManager.addProximityAlert` (one PendingIntent per circle) | Not deprecated (reference page, AOSP API file and source all agree). Enter and exit events, removal by PendingIntent, no per-app cap found. But: a 30-minute sampling floor, background location needed (inferred), a mutable PendingIntent, and registrations lost on reboot. Details in §6. | No new dependency. Same logic on Android 12 to 16 and `main`. Needs a working fused location provider on the device (Q18). | **Preferred route for tier 1, pending spike** |
| C | Continuous polling in a location foreground service | Android 14+ needs `foregroundServiceType="location"`, `FOREGROUND_SERVICE_LOCATION` and the type constant. A location foreground service cannot start from the background without `ACCESS_BACKGROUND_LOCATION`. Listed use cases are navigation and location sharing. The docs point geofence needs to the geofence API. A foreground app or an active foreground service is not throttled (background-limits page). | The only platform route to low latency. Battery cost, a permanent notification, and a use-case mismatch. | **Rejected as default.** The only route for tier 2. |
| D | Periodic WorkManager checks | The minimum repeat interval is 15 minutes (AndroidX WorkManager). Timing is inexact. A background app is also subject to the 30-minute location throttle (§6, row 10). | Cannot notify promptly. `AGENTS.md` discourages it for reminders. | **Use only to re-sync the registry, never as the trigger** |
| E | Check when the app is opened (tier 0) | Needs foreground location only. Shows nearby todos inside the app. | Works everywhere. No background notification. | **Build first** |
| F | Place names through `android.location.Geocoder` | Needs a geocoder backend. AOSP's default config names none (`config_geocoderProviderPackageName` is `@null`), so a vendor or ROM must supply one. On Google devices this is usually network-backed (to verify). | Sends place names off the device. Availability varies. | **Not in v1** |
| G | Map-based picker | Google Maps is closed-source. Open tile maps fetch tiles over the network. | Conflicts with the zero-network rule. | **Not in v1** |

## 6. How the platform proximity alert behaves

What AOSP does when an app calls `addProximityAlert`. Paths are under `frameworks/base/` unless stated. Android 14, 15, 16 and `main` have a byte-identical `GeofenceManager.java`. Android 12 and 13 have the same logic in the lines compared. Android 11 (our minSdk) has an older structure with the same 30-minute floor and the same permission result.

| # | Behavior | Evidence | Consequence for Todosian |
|---|---|---|---|
| 1 | **The PendingIntent must be mutable and targeted.** | `location/java/android/location/LocationManager.java`: `addProximityAlert` throws `IllegalArgumentException` ("pending intent must be mutable") when `BLOCK_IMMUTABLE_PENDING_INTENTS` applies. Its javadoc: "For apps targeting Android S and above, immutable PendingIntents passed into location APIs will generate an IllegalArgumentException." It also requires `isTargetedToPackage()` (`BLOCK_UNTARGETED_PENDING_INTENTS`). `services/core/.../am/PendingIntentRecord.java` (`sendInner`) skips the fill-in intent for immutable PendingIntents, which is how the `KEY_PROXIMITY_ENTERING` extra travels. Not present in Android 11. | Use an explicit intent and `FLAG_MUTABLE` on API 31+. Reusing the existing `FLAG_IMMUTABLE` pattern would crash. |
| 2 | **Fine location is enforced.** | `services/core/.../location/geofence/GeofenceManager.java`: `addGeofence` calls `enforceCallingOrSelfLocationPermission(PERMISSION_FINE)`, and a registration is active only while `hasLocationPermissions(PERMISSION_FINE)` holds. The `@RequiresPermission(anyOf=coarse, fine)` annotation is lint metadata. | A coarse-only grant throws `SecurityException`. A later downgrade to approximate silently deactivates the registration. |
| 3 | **Background restrictions apply to the registration.** | Android 11: `updateFences()` calls `noteOpNoThrow(OP_FINE_LOCATION)` for every fence on every cycle and skips the fence when it is not allowed. Android 12 to `main`: `.../location/injector/SystemAppOpsHelper.java` watches the location op with `WATCH_FOREGROUND_CHANGES`. Each change calls `GeofenceManager.onLocationPermissionsChanged`, which recomputes `mPermitted` from `hasLocationPermissions`, and `isActive` requires it. | *Inferred:* without background location the op is foreground-only, so the registration goes inactive when the app leaves the foreground. Needs "Allow all the time" for alerts while the app is closed, and for registering from a boot receiver. Confirm on a device (Q2, Q20). |
| 4 | **Sampling interval.** | `GeofenceManager.mergeRegistrations` requests `FUSED_PROVIDER` updates every `min(2 h, max(30 min, distance to nearest fence ÷ 100 m/s))`, with `setMinUpdateIntervalMillis(0)`, `setHiddenFromAppOps(true)` and a work source. 30 min is `DEFAULT_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS` in `.../location/injector/SystemSettingsHelper.java` (key `location_background_throttle_proximity_alert_interval_ms` in `Settings.Global`). | Alerts lag by up to about 30 minutes plus fix time. The distance term only exceeds 30 minutes beyond about 180 km. Fixes that other apps request are also used. |
| 5 | **Inside test.** | `GeofenceRegistration.onLocationChanged`: inside when distance ≤ `max(radius, fix accuracy)`. Events fire on state changes only, and "exit" only after "inside". | Radii below typical fix accuracy buy nothing. Low-accuracy fixes (network or cell) can produce false "entering" events. Brief passes between fixes fire nothing, as the reference page warns. |
| 6 | **First evaluation is immediate.** | `onActive()` evaluates `getLastLocation()` if it is at most 5 minutes old (`MAX_LOCATION_AGE_MS`). The state starts unknown, so being inside gives an immediate "entering". | Registering, or re-registering after a reboot, while at the place fires at once when a recent fix exists. Sync must not re-add unchanged alerts, and the notification dedupe must cover this. |
| 7 | **Delivery.** | `sendIntent` calls `PendingIntent.send` with a boolean `KEY_PROXIMITY_ENTERING` extra, holds a 30 s partial wake lock, and passes `PendingIntentUtils.createDontSendToRestrictedAppsBundle`. `BroadcastOptions` javadoc: "pending intent will not be sent for an application with background restrictions". | The app receives only enter and exit events, never location fixes. *Inferred:* nothing is delivered while the user has put the app under background restrictions (battery "Restricted"). |
| 8 | **Lifetime.** | Registrations live in memory in the system server. There is no persistence code in `GeofenceManager`. `PendingIntentListenerRegistration` removes a registration when its PendingIntent is cancelled. `ActivityManagerService.forceStopPackageInternalLocked` (`main`) cancels a package's PendingIntents on user stop and on uninstall, and on a plain force-stop only when `Flags.stayStopped()` is on. When location services are off the registration is inactive, then active again when they are on. | Re-register after every reboot. After a force-stop, treat alerts as ended until the app is opened again. Behavior across an app update is unknown (Q20). |
| 9 | **No per-app cap.** | `GeofenceManager` has no count limit. Registrations are keyed by (PendingIntent, geofence). `PendingIntentController` only logs a warning when one UID passes a PendingIntent threshold. | One PendingIntent per todo is fine. Pick our own sanity cap (Q15). |
| 10 | **Background throttling applies to any app request.** | `services/core/.../location/provider/LocationProviderManager.java`: for a background registration that is not exempt, the interval becomes `max(requested, location_background_throttle_interval_ms)`, default 30 minutes. Official page: "apps can receive location updates only a few times each hour". | The app cannot do better by polling in the background. Only a foreground app or foreground service is exempt. |

## 7. Proposed design (pending spike)

### 7.1 Token

Proposal, not tested:

```
- [ ] Buy oat milk 📍 52.52000,13.40500/150 📅 2026-12-01 🔁 every week
```

- Syntax: `📍 <lat>,<lon>[/<radius in metres>]`, decimal degrees.
- **Put it right after the description and before every Tasks token.** The Obsidian Tasks plugin removes its tokens from the end of the line until none match. Its regexes end in `$`, and its source says why: "The regexes end with `$` because they will be matched and removed from the end until none are left" (plugin 8.7.0, `DefaultTaskSerializer.ts`). Any other text after a Tasks token stops that loop, so the plugin would stop reading the dates, priority and recurrence in front of it. With the pin in front of the Tasks tokens, the plugin reads all of them and shows the pin as part of the description.
- Todosian's own reader peels from the end in the same way (`parseRemainder`, `MarkdownParser.kt:914-992`), so it also reads a pin that someone wrote after the Tasks tokens by hand. The writer moves it to the canonical place on the next edit. The write order becomes: description, `📍`, created, start, scheduled, due, done, recurrence, priority.
- **Store the raw token** and parse it lazily. An invalid token then round-trips unchanged and is never silently dropped.
- Keep the token out of `stableTodoId`. Moving a place must not change the todo's id, because collapse state is keyed by it.
- Radius default: 150 m, to be tuned in the spike. The system treats a fix as inside when the distance is at most `max(radius, the fix's own accuracy)` (§6, row 5), so radii below typical fix accuracy buy nothing. The allowed range is open (Q9).
- Alternatives for the maintainers (Q10): a `[label](geo:lat,lon)` link, which the Obsidian Map View plugin documents as its inline location format (per its location-formats page, found by search and not read in full) and which opens a maps app when tapped; a note line under the todo; or named places defined once in a separate note.

### 7.2 Flow

1. **Edit:** the user sets a place by coordinates or from the current location. The writer adds or replaces the `📍` token on that line only.
2. **Sync:** parse the folder, build the desired set of places, diff it against the registry, and call `addProximityAlert` or `removeProximityAlert`. Wrap each call in `runCatching`: `SecurityException` means a missing permission, and `IllegalArgumentException` means bad arguments or an immutable PendingIntent. Skip the registration when permissions are missing.
3. **Firing:** the system sends the PendingIntent on entering and on exiting the circle. `LocationReminderReceiver` ignores exits (`KEY_PROXIMITY_ENTERING` is false), calls `goAsync()`, reads the file through `FileRepository.readLines` (which shares `fileMutex`), finds the todo by stable id, and checks that it is still open and still carries the token. It then applies the daily dedupe and posts the notification.
4. **Re-registration triggers:** app start, after a place edit, after a permission change, `BOOT_COMPLETED`, the daily alarm (which only exists with Tasks support on, Q11), and possibly `MY_PACKAGE_REPLACED` (Q20).
5. **Registering while already inside** the area fires an "entering" intent at once if the system has a fix younger than 5 minutes (§6, row 6). Sync must not re-add unchanged alerts.

### 7.3 Registry (derived, not authoritative)

- **Key:** `(file URI, Todo.id)`. The id is unique per file only (`stableTodoId` has no file component), so the file must be part of the key.
- **Value:** request code, latitude, longitude and radius. Stored in private prefs, the same way `DueReminderNotifier` keeps its state (`DueReminderNotifier.kt:34-36`).
- The registry can be deleted and rebuilt from the files at any time. It is a cache of registrations, not a second data store.
- **PendingIntent:** an explicit intent to a new non-exported receiver, with a data URI that is unique per key so `removeProximityAlert` can match it later. Use `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` on API 31+. On API 30 use `FLAG_UPDATE_CURRENT` alone: PendingIntents are mutable by default there, and `FLAG_MUTABLE` is an API 31 constant. One PendingIntent per todo, because removal works per PendingIntent.
- **Diff:** `desired - registered` is added, `registered - desired` is removed, and changed values are removed and re-added. Make this a pure function with unit tests, like `nextTriggerMillis` (`DueReminderScheduler.kt:52-56`).
- **Expiration:** `-1` means none. The default proposal is no expiry, with removal on sync.

### 7.4 Notification

- A new channel, separate from `todosian_due_reminders` (`DueReminderNotifier.kt:30`), and new notification IDs rather than 5001.
- Strings go in `values/strings.xml`. There are no translations to update.
- Tapping the notification opens `MainActivity`, as in `DueReminderNotifier.kt:92-99`.
- Skip posting when the feature is off, the folder is gone, the POST_NOTIFICATIONS permission is denied (the check at `DueReminderNotifier.kt:254-261`), or the todo is closed or no longer carries the token.
- Dedupe: one notification per todo per day, persisted the same way as the due reminder (`DueReminderNotifier.kt:191-211`).

### 7.5 Permissions and onboarding

- Ask for location only when the user first sets a place, not at install.
- **Foreground (tiers 0 and 1):** `ACCESS_FINE_LOCATION`, with coarse location declared for the Android 12+ approximate/precise dialog. Fine location is enforced (§6, row 2). A coarse-only grant throws, and a later downgrade silently deactivates alerts.
- **Background (tier 1 only):** `ACCESS_BACKGROUND_LOCATION`. On Android 11+ the system dialog has no "Allow all the time" option, and the user must enable it on a Settings page. Explain why before sending the user there.
- Check the permission state each time the app opens and show a status, because the system gives no callback when alerts go inactive.
- **Privacy statement:** the app never receives location fixes in the background. The system only sends an enter or exit event (§6, row 7). Keep it true with a review rule: no `requestLocationUpdates` in the app, and `getCurrentLocation` only after a user tap.
- Manifest additions: the location permissions above, one non-exported receiver, no foreground service and no `INTERNET`.

### 7.6 Capturing a place

- Latitude and longitude fields with validation.
- "Use current location": a one-shot fix through `LocationManager.getCurrentLocation`, which exists from API 30. `FUSED_PROVIDER` is public only from API 31 (it is `@hide` in Android 11), so use GPS or network on API 30. No continuous tracking.
- No map and no search in v1 (Q13).

### 7.7 Battery and privacy

- The app polls nothing. The platform asks the fused provider for a fix at most every 30 minutes, and it also uses fixes that other apps request. The work source attributes the cost to the app.
- On a device that uses AOSP's basic fused provider (no Play services), a request with the default balanced quality asks GPS at that interval, plus the network provider when one is installed (`packages/FusedLocation/.../FusedLocationProvider.java`: GPS is requested when it is present and either there is no network provider or the quality is above low-power). On Google devices the fused provider belongs to Play services and may behave differently (Q6, Q18).
- Re-register only when the desired set changes. A full-vault scan on every cold start costs time on large vaults. Throttle it, or compare a cheap fingerprint first (Q12).
- Coordinates are plain text in the vault. They sync to every device that syncs the folder, and into any Git history or backup of it. Options are in Q10.

## 8. Round-trip hazards

Any new token that is not carried through `TasksMeta` will be lost, and a misplaced token can break Obsidian. Five paths matter:

1. **Edit with Tasks on.** `MarkdownParser.editTodo` replaces the whole suffix from `meta` (`MarkdownParser.kt:589`). The edit sheets copy each field by hand (`CategoryScreen.kt:637, 700, 761`; `DailyFocusScreen.kt:331`). A location field that is not copied there is deleted on the first text edit.
2. **Recurrence.** `buildRecurredTaskLine` copies a fixed list of fields (`MarkdownParser.kt:700-708`). A recurring todo would lose its place on the next occurrence.
3. **Recurrence swallows the tail.** `recurrenceSuffixRegex` captures `(.+?)\s*$` (`MarkdownParser.kt:29-30`). A token written after `🔁` becomes part of the rule. The canonical order in §7.1 avoids this.
4. **The Tasks plugin reads from the end of the line.** A token at the end of the line hides every Tasks token in front of it from Obsidian (§7.1). The canonical order avoids this too.
5. **Tasks-plugin-only tokens.** `parseRemainder` peels only done, due, start, scheduled, created, priority and recurrence (`MarkdownParser.kt:934-992`). The Obsidian Tasks plugin also writes `🆔`, `⛔`, `🏁` and `❌`, and it writes them right after the pin. Todosian stops at the first of these, so on those lines it never reaches the pin. It already misses the other tokens on those lines today. See [the plugin note](obsidian-tasks-place-field.md#7-compatibility-across-the-three-programs).

Guard rails: unit tests that round-trip each path, a test that non-todo lines stay byte-for-byte unchanged (the edge-case list in `AGENTS.md`), and a manual check in Obsidian that the Tasks plugin still shows the due date next to a pin.

## 9. What users will notice

| Behavior | Cause | Mitigation |
|---|---|---|
| Notifications arrive up to about 30 minutes late, and short visits are missed | §6, rows 4 and 5 | Say so in the UI and README. Larger radii catch more visits. |
| Nothing arrives after the app is force-stopped | §6, row 8 | Re-register on the next app start. Document it. |
| Nothing arrives under battery "Restricted" | §6, row 7 (inferred) | Detect background restrictions with `ActivityManager.isBackgroundRestricted()` and warn in settings. |
| Nothing arrives after a reboot unless "Allow all the time" is granted | §6, rows 3 and 8 | Explain the permission. Show a status. |
| An immediate notification when a todo gets a place the user is already standing in | §6, row 6 | Treat it as expected and keep the dedupe. |
| False "entering" events when the system only has a poor fix | §6, row 5 | Radius floor. Once-per-day dedupe. |
| Alerts for edited or deleted todos | Registrations lag behind external edits | Re-read the file when an alert fires. Re-sync on the triggers in §7.2. |
| Coordinates visible in a synced or published vault | Plain-text token | Say so in the README. Options in Q10. |

## 10. Test plan

**Unit tests** (`app/src/test/`):
- Parse and write the location token on lines that also carry `🔁`, `⏫` and `📅`, in the canonical order and in hand-written orders.
- The writer always emits the canonical order: description, pin, then the Tasks tokens.
- Invalid coordinates and radii round-trip unchanged.
- Text edits keep the place, with Tasks support on and off.
- Recurrence keeps the place.
- Changing the place does not change `Todo.id`.
- Registry diff: add, remove, change, no-op.
- Unicode and emoji text, very long lines, empty files, and files with only non-todo content (the `AGENTS.md` edge-case list).

**Manual** (device or emulator, since there are no instrumentation tests):
- Open a file with a pin and Tasks tokens in Obsidian with the Tasks plugin. Confirm the due date, priority and recurrence are still recognized.
- Place set by coordinates and by current location. Notification received with the app killed (tier 1).
- Permission matrix: fine only, fine plus background, approximate only, none. Each with the app in the foreground, in the background, and after a reboot.
- Force-stop, app update, and battery "Restricted". Record what survives (Q20).
- Latency: walk into a 150 m circle and a 500 m circle. Record the time to notification and any misses.
- For lab runs, lower the throttle with `adb shell settings put global location_background_throttle_proximity_alert_interval_ms 60000`. The key exists in AOSP `Settings.Global`. Confirm the shell write is accepted on the test device.
- Emulator movement with `adb emu geo fix <lon> <lat>`.
- Battery: `adb shell dumpsys batterystats` and `adb shell dumpsys location` over 24 hours, with 10 places and with 50.
- Fire a test notification through the existing debug-only receiver pattern (`TestNotificationReceiver`).

## 11. Phased plan and touch points

| Phase | Scope | Size |
|---|---|---|
| 0. Spike | Debug-only receiver and registration with a mutable PendingIntent. Confirm §6 on a Google device, on an AOSP-based ROM, and on one OEM device with aggressive battery management. Write results into §6. | Small |
| 1. Data | Token parse and write. `Todo` and `TasksMeta` fields. Copies in the edit sheets and in recurrence. Unit tests. | Medium |
| 2. Capture UI and tier 0 | Coordinate fields, "use current location", foreground permission flow, a "Nearby" view. | Medium |
| 3. Engine (tier 1) | Registry, sync diff, receiver, boot re-arm, notification channel, strings. | Medium |
| 4. Background permission and settings | Explanation and Settings link for "Allow all the time", status, settings toggle, README updates (Supported Markdown, Privacy). | Small to medium |

Likely touch points: `app/src/main/AndroidManifest.xml`, `utils/MarkdownParser.kt`, `data/model/Todo.kt`, `ui/category/CategoryScreen.kt`, `ui/category/CategoryViewModel.kt`, `ui/dailyfocus/DailyFocusScreen.kt`, `ui/dailyfocus/DailyFocusViewModel.kt`, `TodosianApplication.kt`, new files under `notifications/`, `ui/settings/SettingsTasksScreen.kt`, `values/strings.xml`, tests under `app/src/test/`, and `README.md`. No version bump (`AGENTS.md`).

## 12. Questions

Numbering is kept from the first draft. Q19 to Q22 are new.

**Technical**

- **Q1. Status of `addProximityAlert`. Resolved.** The reference page says "Added in API level 1" with no deprecation marker. The AOSP API file (`location/api/current.txt`, `main`) and the source of `LocationManager.java` show no `@Deprecated` on `addProximityAlert` or `removeProximityAlert`. A mirror (https://developer.android.google.cn/reference/android/location/LocationManager) claimed deprecation from API 34, which none of these show. `main` is a development branch, so re-check against a release before shipping.
- **Q2. Permissions. Answered from source, device test pending.** Fine location is enforced. Background restrictions apply to the registration, so "Allow all the time" is effectively required for background use (§6, rows 2 and 3). An approximate-only grant deactivates the registration.
- **Q3. Persistence. Mostly answered.** In memory only, lost on reboot, removed when the PendingIntent is cancelled, inactive while location services are off (§6, row 8). Open: app update, and a plain force-stop on current devices (Q20).
- **Q4. Limits. Resolved.** No per-app cap in `GeofenceManager`. Play services allows 100, which does not apply here.
- **Q5. PendingIntent mutability. Resolved.** It must be mutable and explicit (§6, row 1).
- **Q6. Battery. Partly answered.** The platform asks the fused provider for a fix at most every 30 minutes (§6, row 4). Real drain depends on the device's fused provider. Measure it in the spike.
- **Q7. Latency. Answered from source, distribution open.** A 30-minute floor (§6, row 4). The Play-services guide's "every couple of minutes" does not apply to the platform API. Measure real delays in the spike.
- **Q14. Current location. Resolved.** `getCurrentLocation(String, CancellationSignal, Executor, Consumer)` exists from API 30 and needs coarse or fine location. `FUSED_PROVIDER` is `@hide` in Android 11 and public from API 31. Whether a one-shot fix is acceptable UX is a product question.
- **Q16. Network location. Open.** Whether the device's fused provider sends Wi-Fi or cell data to a vendor service depends on the device. The app never sees fixes either way.
- **Q18. Fused provider without Play services. Open (device test).** Proximity alerts run on `FUSED_PROVIDER` inside the system, so they need a working fused provider. AOSP ships a basic one that combines GPS and network location (§7.7). Test it on an AOSP-based ROM.
- **Q19. Vendor deviations. Open (device test).** Do Google and OEM builds match AOSP `GeofenceManager`? Compare latency and permission behavior on at least one Google device and one non-Google ROM.
- **Q20. Lifecycle on real devices. Open (device test).** What survives an app update, a plain force-stop, and battery "Restricted"? Is `MY_PACKAGE_REPLACED` needed as a re-registration trigger?

**Product decisions (maintainer)**

- **Q8. Trigger semantics.** Enter only, or enter and exit? Once per day per todo, or on every entry? Registering while inside fires immediately (§6, row 6): keep that or suppress it?
- **Q9. Scope and radius.** Is one place per todo enough? What is the default radius and the allowed range? The proposal is 150 m by default, and the system ignores radii below the fix accuracy.
- **Q10. Token format and privacy of coordinates.** Options: (a) the `📍 lat,lon/radius` token with raw coordinates (the proposal), (b) a `[label](geo:lat,lon)` link for Obsidian Map View interop, (c) named places in one shared note, (d) rounded coordinates. Rounding to 4 decimal places is about 11 m of latitude, so the radius has to absorb it.
- **Q11. Gating.** Should location reminders sit under the Tasks setting or have their own "Location reminders" setting? The daily alarm only exists when Tasks support is on (`DueReminderScheduler.kt:30`).
- **Q12. Freshness.** How stale may registrations be after external edits? Options: sync on app start, boot, the daily alarm and place edits (proposed); WorkManager at 15 minutes or more (inexact, discouraged); an observer that runs only while the app is open.
- **Q13. Map.** Confirm that manual coordinates and "use current location" are enough for v1, with no map SDK.
- **Q15. Cap.** The platform has no cap. Do we set our own (for example the nearest N places), and what does the UI say when it applies?
- **Q17. Background UX.** The background grant is effectively required for tier 1. Is an explanation screen plus a Settings link acceptable, and does the README need a privacy note?
- **Q21. Tier decision.** Is a delay of about 30 minutes acceptable for tier 1? If not, tier 0 is the ceiling under the current rules (§1).
- **Q22. Tasks-only tokens.** Should the parser recognise and preserve `🆔`, `⛔`, `🏁` and `❌` as opaque tokens, so the pin and the tokens before it stay readable on lines that the Obsidian Tasks plugin has edited (§8, item 5)?

## 13. Audit of the first draft

Method: AOSP sources for Android 11 to 16 and `main`, the Obsidian Tasks plugin source (8.7.0), the official Android pages, and the repository. Ten of the cited code lines were re-checked independently, and the official-page quotes were re-read.

**Corrected**
- **Token placement.** The first draft's example put the pin after the Tasks tokens. The Tasks plugin reads from the end of the line, so that would hide the dates, priority and recurrence from Obsidian (§7.1).
- **PendingIntent.** The draft pointed at the existing `FLAG_IMMUTABLE` pattern and left mutability as a question. Immutable PendingIntents are rejected (§6, row 1).
- **Background location.** Left open before. The source shows it is effectively required (§6, row 3).
- **Latency.** Left open before. There is a 30-minute floor (§6, row 4).
- **Persistence, limits, provider.** Left open before. Answered in §6.
- **Verdict.** "Feasible, with conditions" became "feasible, but not as an arrival alert", with a tier table and a different recommendation.

**Confirmed**
- Code references and the round-trip hazards in the edit sheets and in recurrence.
- Play-services geofencing: the 100-geofence cap, no recovery after reboot, the background permission, and the "couple of minutes" response time.
- Foreground-service rules for location, and the Android 11+ Settings-page grant.
- `addProximityAlert` is not deprecated.

**Not changed**
- Deferring Play services, map pickers and geocoding, for the reasons in §4 and §5.

**Not checked**
- Nothing was run on a device. Everything in §6 is a reading of source, and rows 3, 7 and 8 contain inferences.
- The Android 17 behavior-change pages were searched for location, geofence, proximity and PendingIntent terms and had no relevant entries. That is a text search, not a review.

## 14. Documentation drift noticed

- `AGENTS.md:268` says targetSdk 36. `app/build.gradle.kts:20` sets 37. A maintainer should update `AGENTS.md`. This PR does not touch it.
- `AGENTS.md` says Tasks tokens should be ignored when the setting is off. `MarkdownParser.parse` always extracts them (`:37`), and writers honor the setting. A location token should follow the actual behavior.

## 15. Sources

Official pages, read on 2026-11-26:

- Geofencing: https://developer.android.com/develop/sensors-and-location/location/geofencing
- Background location permission: https://developer.android.com/develop/sensors-and-location/location/permissions/background
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Background location limits: https://developer.android.com/about/versions/oreo/background-location-limits
- `LocationManager` reference: https://developer.android.com/reference/android/location/LocationManager

AOSP source (`https://android.googlesource.com/platform/frameworks/base/+/<ref>/<path>`, with `<ref>` set to `refs/heads/main` or `refs/tags/android-<n>.0.0_r1`):

- `location/java/android/location/LocationManager.java`
- `location/api/current.txt`
- `services/core/java/com/android/server/location/geofence/GeofenceManager.java` (tags `android-12` to `android-16`, `main`). Android 11: `services/core/java/com/android/server/location/GeofenceManager.java` and `GeofenceState.java`.
- `services/core/java/com/android/server/location/injector/SystemAppOpsHelper.java`, `LocationPermissionsHelper.java`, `SystemSettingsHelper.java`, `SettingsHelper.java`
- `services/core/java/com/android/server/location/provider/LocationProviderManager.java`
- `services/core/java/com/android/server/location/listeners/PendingIntentListenerRegistration.java`
- `services/core/java/com/android/server/PendingIntentUtils.java`
- `services/core/java/com/android/server/am/PendingIntentRecord.java`, `PendingIntentController.java`, `ActivityManagerService.java`
- `core/java/android/app/BroadcastOptions.java`, `core/java/android/provider/Settings.java`, `core/res/res/values/config.xml`
- `packages/FusedLocation/src/com/android/location/fused/FusedLocationProvider.java`

Obsidian Tasks plugin (`main`, version 8.7.0): https://github.com/obsidian-tasks-group/obsidian-tasks/blob/main/src/TaskSerializer/DefaultTaskSerializer.ts

Obsidian Map View location formats (found by search, not read in full): https://esm7.github.io/obsidian-map-view/location-formats

Repository: `AGENTS.md`, and the file and line references above.
