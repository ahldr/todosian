# The 📍 token in the Obsidian Tasks plugin fork

> Investigation only. Nothing was pushed to `ahldr/obsidian-tasks`. A throwaway prototype was built in a scratch copy to measure the work, and it is not committed anywhere. Companion to [location-notifications-feasibility.md](location-notifications-feasibility.md).

- **Question:** Can `ahldr/obsidian-tasks`, the fork of the Obsidian Tasks plugin, use the same `📍 lat,lon[/radius]` token as Todosian?
- **Fork state:** `main` at `ba73fbf` (2026-11-26), plugin version 8.7.0. The last commit is by the upstream maintainer, so the fork is in step with upstream.
- **Evidence:** the plugin source and its contributor guides; a prototype that passes the plugin's own test suite, type-check and linters; and tests run against an unmodified copy of upstream. Nothing was tried inside Obsidian.

## 1. Verdict

**Yes. The smallest useful step needs no plugin change. First-class support is a small code change with a large test and documentation tail.**

| Level | What it gives | Plugin change | Evidence |
|---|---|---|---|
| 0 | The pin coexists with the Tasks tokens, and a query can filter by distance with JavaScript over `task.description` | None. The pin must be written before the Tasks tokens. | Tested on unmodified upstream 8.7.0 |
| 1 | A real `place` field: read, written, kept by edits and recurrence; `has place`, `no place`, `place includes`; sort and group; show or hide in results; `task.place` in scripts | 10 source files, +103 lines | Prototype: the full suite, type-check and linters pass after updating tests |
| 2 | An edit-dialog field, a settings toggle, a tooltip, autosuggest and documentation | Level 1 plus about six more source files and about a dozen doc pages | Estimate from the plugin's own checklist and a comparable field. Not prototyped. |
| 3 | Geographic query words such as `place within 5 km of …` | A new filter grammar | Not recommended. Level 1 plus scripting already does it (tested). |

**Recommendation:**

1. Do level 0 now. It needs only the pin-first rule that the Todosian report already recommends.
2. Do level 1 in the fork if you want queries, sort, group, layout switches and `task.place`. It is one focused change, and the commit must include the test updates.
3. Leave level 2 until Todosian's token format is settled (Q10 in the main report).
4. Do not propose it upstream through a pull request from here. Upstream requires a human-opened issue or discussion first ([§8](#8-risks-and-constraints)).

## 2. How the plugin treats a task line

- **Reading is from the end of the line.** Every field regex ends in `$`. The source says why: "The regexes end with `$` because they will be matched and removed from the end until none are left" (`src/TaskSerializer/DefaultTaskSerializer.ts`). Order is free, and one unknown token at the end stops the loop.
- **Writing order is the enum order.** `TaskLayoutComponent` in `src/Layout/TaskLayoutOptions.ts` "determines the order that task fields are rendered and written to markdown": Description, Id, DependsOn, Priority, RecurrenceRule, OnCompletion, CreatedDate, StartDate, ScheduledDate, DueDate, CancelledDate, DoneDate, BlockLink. A new member straight after `Description` makes the plugin write the pin first. That is the placement the Todosian report requires, so it falls out of the design.
- **Two formats.** The emoji format and the Dataview format each need their own symbol and regex.
- **A plain string field survives rebuilds.** Most `new Task({...})` calls spread `...task`. In the prototype only two construction sites needed an explicit value, both in `src/Commands/CreateOrEditTaskParser.ts`. Tested: completing a recurring task keeps the place on the completed task and carries it to the next occurrence.
- **Scripts see `ReadOnlyTask`** (`src/Task/ReadOnlyTask.ts`). In `filter by function`, text that contains `return` runs as a function body, and any other text is wrapped in `return (...)` (`src/Scripting/Expression.ts`). A multi-statement filter therefore needs a top-level `return`.

## 3. Name: `place`, not `location`

`TaskLocation` appears in 12 source files, and the bare word "location" in 25. In this plugin it means where a task sits in a file (path, line number). A geographic field named `location` would collide with it in code, docs and scripts. `place` is not used as an identifier anywhere.

Proposed names: field `place`, layout component `Place`, instructions `has place`, `no place` and `place includes …`, Dataview key `place::`, CSS class `task-place`.

## 4. Grammar shared with Todosian

`📍 <lat>,<lon>[/<radius in metres>]`, with no spaces inside the value. The prototype's regex source is:

```
(-?\d{1,2}(?:\.\d+)?,-?\d{1,3}(?:\.\d+)?(?:/\d{1,5})?)
```

It is deliberately strict. Tested: `📍 Berlin` and a pin in the middle of the description are left alone, and a variation selector after the emoji is accepted. A regex cannot check ranges (`|lat| ≤ 90`, `|lon| ≤ 180`), and the plugin's `extractField` removes a match before its setter can reject it, so range checks need a small change there (Q-P4). Todosian should use the same grammar.

## 5. Prototype (level 1)

Built in a scratch copy of the fork. The plugin's own checklist ("How do I add a new field to the Task class") and its `NEW_TASK_FIELD_EDIT_REQUIRED` markers (36 sites in 14 source files) were followed, except for the edit dialog.

| File | Change | Lines |
|---|---|---|
| `src/TaskSerializer/DefaultTaskSerializer.ts` | symbol, regex, parse, write | +17 |
| `src/TaskSerializer/DataviewTaskSerializer.ts` | `place::` key and regex | +8 −1 |
| `src/Task/Task.ts` | field, constructor, `identicalTo` | +7 |
| `src/Task/ReadOnlyTask.ts` | `task.place` for scripts | +4 |
| `src/Query/Filter/PlaceField.ts` (new) | `has place`, `no place`, `place includes`, `regex matches`, sort, group | +59 |
| `src/Query/FilterParser.ts` | register the filter | +2 |
| `src/Layout/TaskLayoutOptions.ts` | `Place` component after `Description`, plus `hide place` and `show place` | +2 |
| `src/Renderer/TaskFieldRenderer.ts` | renderer entry (the compiler demands it) | +1 |
| `src/Commands/CreateOrEditTaskParser.ts` | two explicit constructions | +2 |
| `src/TaskSerializer/index.ts` | `TaskDetails` type | +1 |
| **Source total** | **10 files** | **+103 −1** |

**Result.** The type-check found three errors, all in tests. The first test run had 107 failures. 92 were assertions that differ only by an extra `"place": ""`, 6 were snapshots, 5 were upstream's coverage meta-tests (which demand sample filter, sort and group lines for every new field), and the rest were approval files and the fully-populated test task. One added line in the shared serializer test matcher removed 87 of the 107. After updating the remaining tests, snapshots and approval files, everything is clean:

- **Suite:** 188 files and 5,489 tests pass, against 186 files and 5,452 tests before. That is 37 more tests, of which 19 are the new ones written for this prototype. The 10 "expected fail" tests are unchanged.
- **Checks:** `tsc`, `eslint` and `svelte-check` report 0 errors. `eslint --fix`, which the project's `lint` script uses, fixed six style errors in test files.
- **Size:** 39 files, +484 −146. Source: 10 files, +103 −1. Test code: 14 files, +359 −131, including 2 new test files (+199). Approval files and snapshots: 15 files, +22 −14. Every changed line in the approval files and snapshots is the old text of a line that now includes the pin.

**Tested behavior:**

- Reading the pin before or after the other tokens, with negative coordinates, and with and without a radius.
- A pin that is not coordinates, or sits in the middle of the description, stays in the description.
- Writing: `Buy oat milk 📍 52.52,13.40/150 🆔 abc 🔼 📅 2026-12-01`. A trailing pin moves to the front on the next write.
- Dataview: `Buy oat milk  [place:: 52.52,13.40/150]  [due:: 2026-12-01]`.
- Recurrence, the filters, `hide place`, and `filter by function` over `task.place`, including a great-circle distance filter.

**Known limits of the prototype:** no range validation; the value is stored as raw text, so sorting or grouping by it is alphabetical and not meaningful for coordinates; and no edit dialog.

## 6. What level 2 still needs

From the plugin's checklist ("necessary for a first release") and the marker sites:

| Area | Where | Sites |
|---|---|---|
| Edit dialog | `src/ui/EditableTask.ts`, `src/ui/EditTask.svelte` | 6 and 2 |
| Dialog settings | `src/Config/EditModalShowSettings.ts`, `src/Config/Settings.ts`, `src/ui/ModalOptionsEditor.svelte` | 2, plus the settings UI |
| Tooltip | `src/Renderer/TaskLineRenderer.ts` | 2 |
| Autosuggest | `src/Suggestor/Suggestor.ts` | 2 |
| Styling | `src/Renderer/Renderer.scss` | review |
| Documentation | A comparable field (`on completion`) is mentioned in 17 files under `docs/`. One is the changelog and one is a built bundle. They include Layout, Styling, Auto-Suggest, Task Properties, Quick Reference, both Task Format pages and the sample HTML snippets. | about 15 pages |

Not prototyped. The edit dialog is the largest piece: a text input with validation, plus its tests and snapshots.

## 7. Compatibility across the three programs

| Scenario | Result | Evidence |
|---|---|---|
| Todosian writes the pin first, then its tokens. Upstream Tasks reads the line. | Every token is recognised. The pin stays in the description. | Tested, unmodified 8.7.0 |
| The pin is written after the Tasks tokens. Upstream Tasks reads the line. | Nothing before the pin is recognised: the due date and recurrence end up in the description. | Tested, unmodified 8.7.0 |
| The same line, read by the fork with `place` | The pin and all other tokens are recognised, in any order. | Tested, prototype |
| A task is edited in Obsidian | The plugin rewrites its tokens in its own order (`🔁` before `📅`). The pin stays first. | Tested, upstream and prototype |
| A plugin-written line has `🆔`, `⛔`, `🏁` or `❌`. Todosian reads it. | Todosian stops at the first of these and never reaches the pin or the tokens before it. | Code reading: `MarkdownParser.kt:934-992` has no regex for them. Not executed. |
| Dataview-format files | `[place:: …]` is plugin-only. Todosian reads the emoji format only. | Prototype test, Todosian code |

The fifth row is a limitation that Todosian already has, but the pin makes it matter (Q-P6).

## 8. Risks and constraints

- **Upstream policy.** The plugin's contribution guide (`contributing/Contributing/Updating code.md`) was tightened in February 2026 "because of the growth of AI-generated PRs". It says "You must have an issue or discussion first. This is non-negotiable," and a maintainer must agree to the approach before code is written. It also requires one feature per pull request, and every commit must build and pass tests, because the maintainers use `git bisect`. The fork is free to carry the field. Proposing it upstream should start with a discussion written by a person.
- **Churn on the touched files.** Across the 8,085 commits fetched (2024-01-13 to 2026-11-26): `Task.ts` 130, `DefaultTaskSerializer.ts` 81, `EditTask.svelte` 192, `TaskLineRenderer.ts` 92, `EditableTask.ts` 56, `TaskLayoutOptions.ts` 8, `FilterParser.ts` 4, `ReadOnlyTask.ts` 2. The edits are small insertions at marked sites, so conflicts mainly arise when upstream adds another field to the same lists.
- **Test and snapshot churn.** A new field changes layout snapshots, emoji lists and the fully-populated test task. A commit that adds the field has to include those updates to stay green.
- **No native location in Obsidian.** In an Obsidian forum thread ("Geolocation permission for mobile app", 2022 to late 2025), users report that Obsidian has no native location permission, and one says that only IP-based location was possible. I did not check this against the current Obsidian release. A "use current location" button should not be assumed. Entry would be by hand, by paste, or from a map plugin. The Map View plugin documents `[name](geo:lat,lng)` links, and only in notes that carry a `locations:` front-matter tag.
- **Privacy.** Full-mode results render `📍 52.52,13.40/150`. Short mode renders only the pin. Decide whether coordinates should be hidden by default (Q-P5).
- **JavaScript in queries.** `filter by function` needs the plugin's JS-in-queries setting to be on. The prototype tests switch it on explicitly.

## 9. Questions

- **Q-P1. Name.** Is `place` right, or do you prefer another name that avoids `TaskLocation`?
- **Q-P2. Level and timing.** Level 1 now, or wait for the Todosian token decision (main report Q10)? If you want it now, the fork has to be attached with push access so the change can go in as a draft pull request there.
- **Q-P3. Pin first.** Confirm that the fork should write the pin straight after the description. Upstream readers and Todosian both need that placement.
- **Q-P4. Range validation.** Extend `extractField` so a field can reject a match, or accept regex-only checking?
- **Q-P5. Privacy default.** Should rendered results hide coordinates unless the user opts in?
- **Q-P6. Todosian and Tasks-only tokens.** Should Todosian recognise and preserve `🆔`, `⛔`, `🏁` and `❌` as opaque tokens, so the pin stays readable on lines the plugin has edited? This is Q22 in the main report.
- **Q-P7. Upstream.** Do you want to start a discussion upstream yourself?

## 10. Evidence and reproducibility

- Fork files and line numbers are named in the text. Contributor guides read: `contributing/Code/How do I add a new field to the Task class.md`, `contributing/Code/How do I add a new task filter.md` and `contributing/Contributing/Updating code.md`.
- Upstream source: https://github.com/obsidian-tasks-group/obsidian-tasks (`DefaultTaskSerializer.ts` on `main`, version 8.7.0).
- Map View location formats, read directly: https://esm7.github.io/obsidian-map-view/location-formats
- Forum thread, read directly through its JSON: https://forum.obsidian.md/t/geolocation-permission-for-mobile-app/38768
- The prototype and its test runs live only in a scratch directory. The patch is not committed here, because it contains context from the plugin's MIT-licensed code and this repository is Apache-2.0. It is reproducible, and can be pushed to the fork as a draft pull request.
