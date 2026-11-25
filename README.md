# quiQs

> ⚠️ **Experimental, work in progress.** This is a personal side project for exploring on-device LLMs on mobile.
> It isn't a finished app: features are missing, APIs change without notice, and some things only work on Android.
> Expect rough edges.

quiQs is a reminder app built with Kotlin Multiplatform. You create **Qs** (as in *cues*) by typing or by holding a button and
saying something like *"remind me to call the doctor tomorrow at noon"*. A **local** LLM turns that into a structured reminder with
an alarm. Nothing is sent to a cloud model.

## What's interesting here

The main experiment is running a small LLM fully on the phone and getting reliable structured output from it.

- **Model:** [Gemma 4 E2B](https://huggingface.co/litert-community) (instruction-tuned, multimodal), run with
  [LiteRT-LM](https://ai.google.dev/edge/litert-lm/overview) on the device CPU/GPU.
- **Voice in, actions out:** by default, voice prompts go to the model as audio, with no separate speech-to-text step. An
  experimental on-device speech-to-text path also exists for comparison.
- **Commands as JSON:** the model emits one command (`create` / `update` / `delete` / `answer` / `filterSort`). The app parses
  it, validates it and runs it against the local database. While the JSON streams in, the details sheet fills in field by field.
- **Compact alarm notation:** small models are slow at writing ISO timestamps and RRULEs and often get the calendar wrong. The
  model writes what the user said instead (`{"when":"tomorrow","time":"12:00"}`, `{"in":"30m"}`, `{"every":"week",...}`).
  [`LlmAlarmDecoder`](shared/src/commonMain/kotlin/com/quiqs/shared/llm/LlmAlarmDecoder.kt) does the date math in code.
- **Forgiving parsing:** the parser takes the first balanced JSON object and ignores code fences and stray prose. It also repairs
  common mistakes before decoding.
- **Prompt harness:** [`TextPromptHarness`](shared/src/jvmTest/kotlin/com/quiqs/shared/llm/TextPromptHarness.kt) runs a list of
  prompts through the real model on the desktop JVM and writes out the results, so you can check prompt changes without a phone.

## Status

| Area | State |
|---|---|
| Android: Qs list, details sheet, filter & sort, day/week views | Working |
| Android: typed and voice prompts through the local LLM | Working |
| Android: one-off and recurring alarms with notifications | Working |
| Month view, geofence alarms | Not done |
| Desktop | Dev playground only (no LLM, audio or alarms) |
| iOS, web | Stubs |
| Server / cross-device sync | Only a `/health` endpoint |

[`spec.md`](spec.md) has the full design notes, open questions and phased plan.

## Project layout

- `shared/`: data model, SQLDelight schema, recurrence logic and the LLM pipeline (prompt builder, parser, alarm decoder,
  dispatcher, `QAgent`)
- `client/`: Compose Multiplatform UI plus platform code (audio, alarms, LiteRT-LM engine)
- `server/`: Ktor skeleton

## Running it (Android)

The model isn't included in the repo. Download a Gemma 4 E2B `.litertlm` build from
[litert-community on Hugging Face](https://huggingface.co/litert-community) (accept the Gemma terms there). Then:

```bash
./gradlew :client:installDebug
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.quiqs.client/files/gemma.litertlm
```

The app looks for the model at `getExternalFilesDir(null)/gemma.litertlm`. A recent mid-range or better device is recommended.

To try prompts on your computer instead:

```bash
QUIQS_MODEL=/path/to/gemma.litertlm ./gradlew :shared:jvmTest --tests '*TextPromptHarness*'
```
