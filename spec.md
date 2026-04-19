# quiQs

------------------

# Overview

Plan, design, and create a client application for the user to create reminders (or Qs, as in 'cues') that will alert the user at specific times or
locations. The app will be called quiQs.

The app should use one of the gemma 4 local LLMs: the user should be able to talk to the app using natural language to create Qs (e.g.: "Remember
me to call the doctor's office tomorrow at noon to schedule an appointment"), to edit an existing Q (e.g.: "do you know the Q set to tomorrow
about the doctor's appointment? Change it o Tuesday, 6pm"), to delete a Q, and to ask questions about the existing Qs, such as "are there any
work-related Qs set for tomorrow?".

Also, plan, design, and create a server application that would allow the user to sync Qs between different client platforms the app will target.

Eventually, we'll implement sharing Qs between users (friends and family member, work team members, etc.), but that'll be discussed on a next phase.

## Client application

### Targets

- Desktop (mac, linux, windows), web (browser), android, and iOS.

### Tech

- Kotlin Multiplatform (KMP)
- only use libraries that are KMP ready for all targeted platforms.
- Compose Multiplatform (CMP)

### Features of a Q

- user may type text and/or record audio into a Q, or create it via voice (through the LLM). If the user records audio or creates it via voice, the Q
  should keep the audio, and also use speech-to-text to add the text to the Q.
- a Q can have a single scheduled alarm (e.g. "10/04/2026 at 21:30"), multiple single alarms, re-occurring alarms (e.g. "every Tuesday at 21:30 until
  10/12/2027), or no alarm at all.
- a Q can have a geolocation area that triggers an alarm. It can be a one-off alarm, or it can be set to always trigger the alarm until a date/time.
- a Q may have one tag, more than onetag , or no tags. E.g.: "work", "medical", "family". Qs should be filterable by tags.

### Features

Besides creating a Q, the user should be able to:

- Edit Qs
    - Clicking on a Q anywhere in the app should show the full details of the Q, and allow the user to edit and to delete it.
- See and manage Qs in a calendar view, and in a list view.
- Query Qs via voice, as mentioned in Overview.
- Sync Qs with the backend/server app.

### About the LLM

- The model file should not be part of the application: it should be downloaded and stored separately.

## Server application

### Features

- authentication and authorization
- sync between platforms: create, edit, delete Qs.

### Tech

- Ktor.

## Topics for discussion

- which of the gemma 4 models should we use? It should work well in phones. It should support audio.
- How can the client and server apps best share models and functions?
- What's the auth technology that the server should use? JWT? Regular tokens?
- What's the best approach for syncing Qs? The user may create a Q in one platform, sync it, edit in another platform, sync it again, etc.
- What db should the server use? It should be able to store audio (voice content) and text.
- Can the user download the model themselves, and point the app to it?
- Should the LLM add tags to Qs automatically, or should the user add it themselves?
- How will the LLM act on the Q from a prompt? I mean, how will it create a Q by itself, or edit or delete a Q? How will the application code support
  that?