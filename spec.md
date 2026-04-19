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
- `creationPromptAudio` — audio of the voice prompt that created or edited the Q, retained for provenance and recovery if STT or the LLM misinterpreted intent (optional; not retained for query / delete prompts)
- `tags` — zero or more labels (e.g. `work`, `medical`, `family`)
- `alarms` — zero or more, each one of:
    - **one-off time** — a single timestamp
    - **recurring time** — rule + end date (e.g. *every Tuesday 21:30 until 2027-12-10*)
    - **geofence** — lat/lng + radius; one-off *or* active-until a timestamp
- `createdAt`, `updatedAt`
- `syncState` — local-only / dirty / synced (client-side bookkeeping)

### Features

- **Create** — typed text, recorded audio, or voice prompt interpreted by the LLM.
    - *Audio-captured Qs* (user dictates content into a Q) keep the audio *and* run STT to populate `text`.
    - *Voice-prompted Qs* (user talks to the LLM to create or edit) attach the prompt audio to the resulting Q as a creation-provenance artifact, so the user can replay what they actually said if STT or the LLM misinterprets intent. Prompt audio for **query** and **delete** commands is not retained.
- **Edit / delete** — tapping a Q anywhere opens full detail with edit and delete actions.
- **Views** — list view and calendar view, both filterable by tag.
- **Query** — ask the LLM questions about existing Qs (*"any medical Qs this week?"*).
- **Sync** — push local changes and pull remote changes from the server.

### LLM

- Runs **locally** on-device; Gemma variant TBD (see open questions).
- The model file is **not bundled** with the app — downloaded on first run (or user-provided) and stored outside the app bundle.
- The LLM turns user prompts into structured commands (create / update / delete / query) which the app executes against the local Q store.
- If the chosen Gemma is natively multimodal (audio-in), the prompt audio feeds the model directly and there is no discrete STT step; the provenance concern shifts from *"STT got it wrong"* to *"model misinterpreted"*, but retaining create/edit prompt audio is still useful for user recovery and debugging.

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

- **Gemma variant** — which model runs well on phones *and* supports audio? Tradeoffs: size, latency, audio support, license.
- **Shared code** — data classes and validation in `shared/` are obvious; does any sync / business logic belong there too, or does the server use it
  only for types?
- **Auth** — JWT vs. opaque tokens. Session duration, refresh flow, revocation.
- **Sync strategy** — last-writer-wins, CRDT, or operational log? How do we handle *created-offline-on-A + edited-on-B before A syncs*?
- **Database** — Postgres + object storage (S3-compatible) for audio? Or embedded (SQLite) for v1 simplicity?
- **Model delivery** — app-managed first-run download, or user-provided path? If download: from where, under what license, size budget?
- **Tagging** — LLM auto-tags, user-tags, or both? If auto, how is it corrected?
- **LLM-to-app bridge** — how does LLM output drive real CRUD? Structured JSON tool calls, a DSL, function calling via a local runtime (MediaPipe /
  llama.cpp / etc.)?

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

### Phase 2 — Time alarms

One-off and recurring time alarms. Platform alarm dispatch (AlarmManager on Android). Notification on fire.
**Exit:** alarm fires at the scheduled time with the app closed.

### Phase 3 — Second-platform validation (desktop)

Bring up Phase 1 + 2 on desktop. Proves the `shared/` abstractions hold; divergence should live only in platform-specific source sets. Desktop
alarms = foreground notifications for v1.
**Exit:** feature parity between Android and desktop.

### Phase 4 — Server + sync

Resolve auth + sync-strategy open questions. Implement `/qs` CRUD. Client-side sync engine. Validate two-device concurrent edits.
**Exit:** create a Q on Android, open desktop, it's there; edit on desktop, it updates on Android after sync.

### Phase 5 — Audio + STT

Record audio inside a Q; run speech-to-text to populate `text`. Upload audio blobs to server. Decide STT engine (on-device library vs. LLM).
**Exit:** record a voice note → transcript appears in the Q → audio plays back after a sync round-trip.

### Phase 6 — LLM integration

Model download / configuration flow. Wire natural-language prompts → structured commands → local Q operations. Query, create, edit, and delete via
prompt.
**Exit:** operate the app end-to-end using only voice or typed prompts.

### Phase 7 — Geolocation alarms

Geofence definition UI (map picker). Platform geofence APIs. One-off and active-until modes.
**Exit:** alarm fires on entering a geofence with the app backgrounded.

### Phase 8 — Remaining platforms + polish

iOS, web (wasmJs). Calendar view. Tag filtering UI. Auto-tagging (if chosen). Accessibility pass.
**Exit:** feature parity across all targets listed in *Client ▸ Targets*.
