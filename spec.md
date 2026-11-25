# quiQs

A multi-platform reminder app. Users create **Qs** (as in *cues*) that alert them at a time, on a schedule, or when they enter a location. A Q can
contain text, audio, or both, and is created either through the UI or via natural-language voice prompts interpreted by a local Gemma LLM.
A backend syncs Qs across the user's devices.

Sharing Qs between users (family, team) is out of scope for v1 and will be specified later.

## Goals

- Fast, offline-first Q capture on any user device.
- Natural-language interaction via a **locally-run** LLM — user data doesn't leave the device for LLM calls.
- Flexible alarms: single, recurring, and geofence.
- Cross-device sync via a user account.

## Non-goals (v1)

- Sharing Qs between users.

---

## Client application

### Targets

Desktop (macOS, Linux, Windows), web (browser), Android, iOS.

### Tech

- Kotlin Multiplatform (KMP)
- Compose Multiplatform (CMP)
- Only KMP-ready libraries across all targets.

### Data model — a Q

- `id` — stable UUID
- `text` — typed or transcribed content (optional if `contentAudio` present)
- `voiceNotePath` — path to a single recorded audio file attached to the Q (optional). It is either the voice prompt that created or edited the Q
  (kept so the user can replay what they actually said if the LLM misread it) or a recording the user attached by hand from the details sheet.
  Prompt audio for **query**, **filter** and **delete** commands is not kept. *(This one field replaced the earlier separate `contentAudio` /
  `creationPromptAudio` fields.)*
- `tags` — zero or more labels (e.g. `work`, `medical`, `family`)
- `priority` — `high` / `medium` / `low`, default `medium`
- `completed` — the user marked the Q done. Completing a Q cancels its pending alarms and dismisses any notification showing for it; reopening it re-arms them.
- `alarms` — zero or more, modeled as a sealed hierarchy (JSON discriminator `"type"`, also part of the LLM prompt contract):
    - `OneOffTime(at: Instant, firedAt: Instant?)` — a single moment; `firedAt` is set by the runtime when it fires
    - `RecurringTime(rule, dtStart: Instant, until: Instant?, count: Int?)` — iCalendar RFC 5545 RRULE body (no `DTSTART`/`UNTIL`/`COUNT` in
      the string), anchored at `dtStart`; `until` and `count` are mutually exclusive (e.g. *every Tuesday 21:30 until 2027-12-10*). The supported
      subset is FREQ (MINUTELY…YEARLY), INTERVAL, BYDAY (with ordinals like `2SU` / `-1FR`), BYMONTH, BYMONTHDAY, BYHOUR, BYMINUTE — see `Recurrence.kt`.
    - `Geofence(latitude, longitude, radiusMeters, activeUntil: Instant?)` — one-off or time-bounded *(modeled; not armed yet)*
- `createdAt`, `updatedAt`
- `syncState` — local-only / dirty / synced (client-side bookkeeping) *(planned for Phase 8; not in the model yet)*

Persistence: SQLDelight, one `QEntity` row per Q, with `tags` and `alarms` stored as JSON columns so their shapes can change without migrations.

### Features

- **Create** — manually in the details bottom sheet, or by voice: holding the list screen's FAB records a prompt that goes to the LLM.
    - The voice FAB is a combined *new Q or filter & sort* entry point; the LLM decides which one the user meant.
    - For a create, the sheet fills in field by field while the JSON streams out (`StreamingCommandExtractor`). The user then reviews and saves, and the
      prompt audio is attached as the Q's voice note.
- **Edit / delete** — tapping a Q opens the details bottom sheet with edit and delete actions (delete asks for confirmation). Edits can also be voice
  prompts; the prompt audio is attached to the updated Q.
- **Priority / complete** — set priority from a bottom sheet; mark a Q done with its checkbox.
- **Filter & sort** — a filter/sort sheet (alarm window such as *today* / *next week*, tags, priority, completion, sort key). The same `QFilterSort`
  spec is what the LLM emits for spoken filter requests.
- **Views** — list, day and week views (timeline of alarm occurrences, recurring alarms expanded per occurrence via `QTimeline`); month view still to come.
- **Alarms** — one-off and recurring time alarms fire as notifications with Snooze (15m / 1h / 1d) and Mark-done actions and a deep link back to
  the Q. One scheduled wakeup per Q; re-armed on boot and on package replace.
- **Query** — ask the LLM questions about existing Qs (*"any medical Qs this week?"*), answered with the `search_qs` / `list_all_qs` tools.
- **Sync** — push local changes and pull remote changes from the server *(Phase 8)*.

### LLM

- **Model**: Gemma 4 **E2B** (instruction-tuned), natively multimodal (text / image / video / audio). E4B or 12B may be offered on desktop-class hardware as an opt-in upgrade.
- **Runtime**: [LiteRT-LM](https://ai.google.dev/edge/litert-lm/overview) — Google's cross-platform on-device LLM engine (supersedes MediaPipe LLM Inference, whose mobile APIs are deprecated). LiteRT-LM runs on Android, iOS, desktop (macOS / Linux / Windows) and Web, so we use one runtime across every KMP target.
- **Bridge**: Gemma 4 has native function-calling; the LLM emits a structured JSON tool call, which the app validates and dispatches to the Q store (create / update / delete / query). The command schema + parser live in `shared/commonMain`; the platform-specific LiteRT-LM binding lives under `client/` via `expect`/`actual`.
- **Model delivery**: the model file is **not bundled** with the app. For development (Phase 2), the file is pushed to a known on-device path (`adb push`, or equivalent). The shipping UX — first-run download vs. user-provided path — is still open (see *Open questions*).
- **Multimodal prompts**: because Gemma 4 is audio-native, voice prompts feed the model directly — there is no discrete STT step. The "STT got it wrong" concern becomes "model misinterpreted." Keeping the create/edit prompt audio as `voiceNotePath` is the recovery mechanism.
- **Commands**: `create` / `update` / `delete` / `answer` / `filterSort`.

---

## Server application

### Features

- User authentication and authorization.
- Q sync: create, update, delete, conflict resolution.
- Audio blob storage linked to Qs.

### Tech

- Ktor (Netty).
- Auth mechanism, datastore, and sync strategy — TBD (see open questions).

### Sync sketch

- Each Q carries `updatedAt`; each device tracks a per-user sync cursor.
- Conflict resolution strategy to be decided (last-writer-wins vs. something richer).
- Audio uploaded separately from Q metadata (multipart or pre-signed URL).

---

## Open questions

- **Shared code** — data classes and validation in `shared/` are obvious; does any sync / business logic belong there too, or does the server use it only for types?
- **Auth** — JWT vs. opaque tokens. Session duration, refresh flow, revocation.
- **Sync strategy** — last-writer-wins, CRDT, or operational log? How do we handle *created-offline-on-A + edited-on-B before A syncs*?
- **Database** — Postgres + object storage (S3-compatible) for audio? Or embedded (SQLite) for v1 simplicity?
- **Shipping model delivery** — app-managed first-run download, or user-provided path? If download: from where, under what license, size budget? (Phase 2 uses a developer-provided local path; this question is about v1 shipping UX.)
- **Tagging** — LLM auto-tags, user-tags, or both? If auto, how is it corrected?
- ~~**Recurring alarm rule format**~~ — *resolved:* iCalendar RRULE (supported subset, with `dtStart` / `until` / `count` as sibling fields). See *Data model*.
- **Multiple alarms per Q** — the model allows a list, but scheduling, completion and the UI only act on the first alarm. Support several, or limit the model to one?

---

## Implementation phases

Each phase delivers something testable end-to-end. Revisit this spec at each phase boundary — decisions in *Open questions* get resolved as they
become blocking.

**Current status (2026-11-16):** Phases 0–4 are done on Android. Work is now pulling forward the Phase 7 UI: the day and week views are in progress
and the month view is a placeholder. Next up per the plan: Phase 5 (desktop), which is currently only a dev playground (in-memory DB, no LLM,
audio or alarms).

### Phase 0 — Scaffolding *(done)*

Monorepo with `shared/`, `client/`, `server/` Gradle modules. Compose Multiplatform shell. Ktor `/health` endpoint.
**Exit:** project builds on at least one client platform; server answers `/health`.

### Phase 1 — Local Q MVP (Android only) *(done)*

Create, list, edit, delete text-only Qs with no alarms. Local persistence (SQLDelight or equivalent KMP store). List + detail screens. No sync, no
LLM, no audio.
**Exit:** create a Q, kill the app, reopen, it's still there.

### Phase 2 — LLM integration (typed prompts) *(done)*

Earliest feasibility check — validates the single biggest unknown: *can Gemma 4 run on an Android device and produce useful structured output?*

- **Stack**: Gemma 4 E2B (instruction-tuned) via LiteRT-LM 0.10.2; model file pushed to device at `getExternalFilesDir(null)/gemma.litertlm` via `adb push`.
- **Data model**: `Q` now carries `alarms: List<Alarm>` (sealed: `OneOffTime`, `RecurringTime`, `Geofence`). Alarms don't fire yet — that's Phase 4.
- **Bridge**: JSON-in-text schema in `shared/commonMain/llm/`:
  - `QCommand` sealed interface (`create` / `update` / `delete` / `answer`) + `Alarm` serializer share `"type"` discriminator.
  - `LlmPromptBuilder` injects current instant, time zone, and the user's existing Qs as a `QSnapshot` list so the model resolves relative times and references existing ids.
  - `LlmCommandParser` extracts the first balanced JSON object (tolerates code fences and stray prose) and decodes it.
  - `QCommandDispatcher` executes the parsed command against `QRepository` and returns a human-readable result.
  - `QAgent` glues engine → prompt → parser → dispatcher.
- **Engine binding**: `LlmEngine` interface in `shared/commonMain`; `expect object LlmEngineFactory`. Android + JVM actuals wrap `com.google.ai.edge.litertlm.Engine` + `Conversation`. iOS actual is a stub until the Swift binding lands.
- **Version bump**: LiteRT-LM 0.10.2 ships with Kotlin 2.3 metadata, so the project moved to Kotlin 2.2.21 / Compose Multiplatform 1.10.3 with `-Xskip-metadata-version-check` on the shared module.
- **UI**: a minimal *Ask* screen in the client prompts the user, streams the LLM response, and shows the dispatched result + raw output.
- Typed prompts only — audio prompts land in Phase 3.

**Exit:** a typed prompt like *"remind me to call the doctor tomorrow at noon"* creates a correctly-populated Q (including a `OneOffTime` alarm); *"any medical Qs tomorrow?"* returns the right answer.

### Phase 3 — Audio prompts + voice notes *(done)*

Gemma 4 E2B is audio-native, so no separate STT library: the same LiteRT-LM pipeline from Phase 2 accepts audio directly.

- Voice prompts for create, edit, query and filter (`QAgent.*FromVoice`), recorded with `AudioRecorder`.
- Create/edit prompt audio is kept as the Q's single `voiceNotePath` and played back with `AudioPlayer`. Users can also attach a recording by hand.
- *Change from the original plan:* there's no separate "dictate content" flow and no `contentAudio` / `creationPromptAudio` split. The voice
  prompt itself is the note, and the LLM extracts `text` from it.

**Exit:** record a voice note → transcript appears in the Q → audio plays back; spoken prompt to the LLM creates a Q end-to-end.

### Phase 4 — Time alarms *(done, Android)*

One-off and recurring time alarms. Platform alarm dispatch (AlarmManager on Android). Notification on fire.

- `AlarmScheduler` interface in `shared/` (`NoopAlarmScheduler` on iOS/desktop); `AndroidAlarmScheduler` computes the next firing with
  `nextRecurringInstant`. The repository re-syncs the schedule on create, alarm edits, completion changes and delete.
- `AlarmReceiver` posts the notification (Snooze 15m / 1h / 1d, Mark done, deep link). A snooze on a recurring alarm adds a one-off side reminder
  and leaves the recurrence alone. `BootReceiver` re-arms after reboot or app update.
- Add/edit alarm UI: a one-time screen and a recurring-interval screen.
**Exit:** alarm fires at the scheduled time with the app closed.

### Phase 5 — Second-platform validation (desktop)

Bring up Phases 1–4 on desktop. Proves the `shared/` abstractions hold; divergence should live only in platform-specific source sets. Desktop alarms =
foreground notifications for v1.
**Exit:** feature parity between Android and desktop.

### Phase 6 — Geolocation alarms

Geofence definition UI (map picker). Platform geofence APIs. One-off and active-until modes.
**Exit:** alarm fires on entering a geofence with the app backgrounded.

### Phase 7 — Remaining platforms + polish

iOS, web (wasmJs). Calendar view *(day/week in progress on Android, month pending)*. Tag filtering UI *(done via the filter/sort sheet)*. Auto-tagging (if chosen). Accessibility pass.
**Exit:** feature parity across all targets listed in *Client ▸ Targets*.

### Phase 8 — Server + sync

Resolve auth and sync-strategy open questions. Implement `/qs` CRUD + audio blob storage. Client-side sync engine. Validate two-device concurrent
edits.
**Exit:** create a Q on one device, open another, it's there; edit on either side and both converge after sync.
