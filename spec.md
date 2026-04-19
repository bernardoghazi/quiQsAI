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
- `contentAudio` — audio the user dictated as the Q's content (optional)
- `creationPromptAudio` — audio of the voice prompt that created or edited the Q, retained for provenance and recovery if STT or the LLM
  misinterpreted intent (optional; not retained for query / delete prompts)
- `tags` — zero or more labels (e.g. `work`, `medical`, `family`)
- `alarms` — zero or more, modeled as a sealed hierarchy:
    - `OneOffTime(at: Instant)` — a single moment
    - `RecurringTime(rule, until: Instant?)` — rule + optional end (e.g. *every Tuesday 21:30 until 2027-12-10*); rule format TBD (likely iCalendar RRULE)
    - `Geofence(latitude, longitude, radiusMeters, activeUntil: Instant?)` — one-off or time-bounded
- `createdAt`, `updatedAt`
- `syncState` — local-only / dirty / synced (client-side bookkeeping)

### Features

- **Create** — typed text, recorded audio, or voice prompt interpreted by the LLM.
    - *Audio-captured Qs* (user dictates content into a Q) keep the audio *and* run STT to populate `text`.
    - *Voice-prompted Qs* (user talks to the LLM to create or edit) attach the prompt audio to the resulting Q as a creation-provenance artifact, so
      the user can replay what they actually said if STT or the LLM misinterprets intent. Prompt audio for **query** and **delete** commands is not
      retained.
- **Edit / delete** — tapping a Q anywhere opens full detail with edit and delete actions.
- **Views** — list view and calendar view, both filterable by tag.
- **Query** — ask the LLM questions about existing Qs (*"any medical Qs this week?"*).
- **Sync** — push local changes and pull remote changes from the server.

### LLM

- **Model**: Gemma 4 **E2B** (instruction-tuned), natively multimodal (text / image / video / audio). E4B or 12B may be offered on desktop-class hardware as an opt-in upgrade.
- **Runtime**: [LiteRT-LM](https://ai.google.dev/edge/litert-lm/overview) — Google's cross-platform on-device LLM engine (supersedes MediaPipe LLM Inference, whose mobile APIs are deprecated). LiteRT-LM runs on Android, iOS, desktop (macOS / Linux / Windows) and Web, so we use one runtime across every KMP target.
- **Bridge**: Gemma 4 has native function-calling; the LLM emits a structured JSON tool call, which the app validates and dispatches to the Q store (create / update / delete / query). The command schema + parser live in `shared/commonMain`; the platform-specific LiteRT-LM binding lives under `client/` via `expect`/`actual`.
- **Model delivery**: the model file is **not bundled** with the app. For development (Phase 2), the file is pushed to a known on-device path (`adb push`, or equivalent). The shipping UX — first-run download vs. user-provided path — is still open (see *Open questions*).
- **Multimodal prompts**: because Gemma 4 is audio-native, voice prompts feed the model directly — there is no discrete STT step. The "STT got it wrong" concern becomes "model misinterpreted." Retaining `creationPromptAudio` for create/edit is still the recovery mechanism.

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
- **Recurring alarm rule format** — iCalendar RRULE, a simpler custom DSL, or natural-language stored verbatim?

---

## Implementation phases

Each phase delivers something testable end-to-end. Revisit this spec at each phase boundary — decisions in *Open questions* get resolved as they
become blocking.

### Phase 0 — Scaffolding *(done)*

Monorepo with `shared/`, `client/`, `server/` Gradle modules. Compose Multiplatform shell. Ktor `/health` endpoint.
**Exit:** project builds on at least one client platform; server answers `/health`.

### Phase 1 — Local Q MVP (Android only)

Create, list, edit, delete text-only Qs with no alarms. Local persistence (SQLDelight or equivalent KMP store). List + detail screens. No sync, no
LLM, no audio.
**Exit:** create a Q, kill the app, reopen, it's still there.

### Phase 2 — LLM integration (typed prompts)

Earliest feasibility check — validates the single biggest unknown: *can Gemma 4 run on an Android device and produce useful structured output?*

- **Stack**: Gemma 4 E2B (instruction-tuned) via LiteRT-LM; model file pushed to device with `adb push`.
- **Data model**: extend `Q` with `alarms: List<Alarm>` (sealed hierarchy defined in *Data model*). Alarms don't fire yet — that's Phase 4.
- **Bridge**: Gemma 4 native function-calling → JSON tool call → validate + dispatch to `QRepository`. Schema + parser in `shared/commonMain`; LiteRT-LM binding under `client/` via `expect`/`actual`.
- Typed prompts only — audio prompts land in Phase 3.

**Exit:** a typed prompt like *"remind me to call the doctor tomorrow at noon"* creates a correctly-populated Q (including a `OneOffTime` alarm); *"any medical Qs tomorrow?"* returns the right answer.

### Phase 3 — Audio prompts + content audio

Gemma 4 E2B is audio-native, so no separate STT library: the same LiteRT-LM pipeline from Phase 2 accepts audio directly.

- Record `contentAudio` for Qs dictated by the user; feed through Gemma 4 to populate `text`.
- Record and retain `creationPromptAudio` for LLM create/edit voice prompts.

**Exit:** record a voice note → transcript appears in the Q → audio plays back; spoken prompt to the LLM creates a Q end-to-end.

### Phase 4 — Time alarms

One-off and recurring time alarms. Platform alarm dispatch (AlarmManager on Android). Notification on fire.
**Exit:** alarm fires at the scheduled time with the app closed.

### Phase 5 — Second-platform validation (desktop)

Bring up Phases 1–4 on desktop. Proves the `shared/` abstractions hold; divergence should live only in platform-specific source sets. Desktop alarms =
foreground notifications for v1.
**Exit:** feature parity between Android and desktop.

### Phase 6 — Geolocation alarms

Geofence definition UI (map picker). Platform geofence APIs. One-off and active-until modes.
**Exit:** alarm fires on entering a geofence with the app backgrounded.

### Phase 7 — Remaining platforms + polish

iOS, web (wasmJs). Calendar view. Tag filtering UI. Auto-tagging (if chosen). Accessibility pass.
**Exit:** feature parity across all targets listed in *Client ▸ Targets*.

### Phase 8 — Server + sync

Resolve auth and sync-strategy open questions. Implement `/qs` CRUD + audio blob storage. Client-side sync engine. Validate two-device concurrent
edits.
**Exit:** create a Q on one device, open another, it's there; edit on either side and both converge after sync.
