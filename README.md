<div align="center">

# Luna

**An AI companion for your Android phone, with a voice.**

Luna thinks on her own server: Google's Gemini, the same brain whether you call her through
Agora, type, send a voice message or hold the power button. She speaks in Murf's voice. Your
memory, reminders, photos and files stay on the phone, which runs her tools for her. Nothing to
download before the first chat.

Android 8 or newer · 64-bit phone · Luna's server (`server/`) · about 1 GB free

</div>

## What it can do

- **Talks with you.** **Call** in the chat is a phone call with Luna over Agora's Conversational
  AI. Typed and voice messages get the same Luna, answered aloud in Murf's voice when you ask by
  voice.
- **Answers from the power button.** Hold it over any app, like Gemini, or say "Hey Luna": it
  listens, sends when you pause, and answers out loud. It can see the screen
  you called it over: "summarise this", "share this with Anjali".
- **Remembers you.** Your profile, facts you mention ("my dentist is Dr. Rao"), images you ask it
  to keep, and what you talked about in earlier chats. **About you** shows what it knows, to edit
  or delete.
- **Reminds you.** Reminders and events, repeating ones too ("every Monday"), and reminders for a
  place: "buy milk when I'm near DMart" rings when you get there. Alarms and timers too.
- **Keeps notes and lists.** "Add bread to my shopping list", "is bread on my list?"
- **Calls and messages.** It opens the dialer, SMS, WhatsApp or email ready to send, and finds the
  right contact even when a name is misheard. You tap send.
- **Runs your phone.** Opens apps, turns on the flashlight, sets silent or vibrate, the volume and
  Do Not Disturb, takes screenshots, and works out sums and unit conversions. Ask for several at
  once: "call Mom and set an alarm for 7".
- **Finds your photos and videos** by what's in them, who's in them, where and when: "beach
  videos", "photos of Anjali in Munnar last December". It names faces from your contacts' photos.
- **Finds and reads your files.** "Find my resume PDF", "summarise my rental agreement", "what's
  the deposit in it?": files are found by name and read when you ask, in PDF, Word, Excel, slides
  and text. Attach a document in the chat to ask about it, or "remind me to pay this bill". "What's
  my PAN number?" checks your documents as well as your photos.
- **Knows what people sent you** (off by default). "What did Rahul say about the trip?" finds it in
  the messages that came to your phone: chat apps, email and SMS, without ads or one-time codes.
- **Reads your health data** from Health Connect: steps, distance, calories, heart rate, sleep and
  workouts.
- **Tells you what you missed** (off by default). When you come back to your phone, a card shows
  who called and who's waiting on a reply, with replies ready to send.
- **Sees and hears.** Send it a photo or a voice message.
- **Keeps your things on the phone.** Memory, photos, files and messages are kept and searched on
  the phone; Luna's server sees only what a message needs (see [Privacy](#privacy)). Card numbers,
  ID numbers and photos of your documents stay hidden until you confirm it's you with your
  fingerprint, face or PIN.

## Install

1. **Build.** Build the APK from this repository (see [For developers](#for-developers)):
   `./gradlew :app:installDebug` with the phone connected, or `./gradlew :app:demoApk -Pdemo`
   for a file to copy to the phone, `apk/Luna.apk`.
2. **Install.** Copied over, open the file, and let your file manager install apps when Android
   asks. The app isn't on Google Play and is signed with a development key, so Play Protect may
   warn you: choose to install anyway.
3. **Open Luna.** It asks a few optional things about you and which permissions to give, then
   opens the chat. Calls, chat and voice messages all go to Luna's server: set its address and
   key in Settings → Live voice and family, or build with `live.serverUrl` and `live.appKey` in
   `local.properties`. Without it, or offline, the phone still sets alarms and timers, works out
   sums and switches the flashlight, ringer, Do Not Disturb and volume.

   Once photos are allowed, the app downloads EmbeddingGemma 2 (0.39 GB) by itself, to find
   photos by what's in them and past conversations by meaning. It's optional: Settings → Photo
   and memory search. No account needed.

An older install's Gemma 4 E4B file (3.66 GB) is deleted on the first start after updating.

### Use it from the power button

In the app, go to **Settings → Calling the assistant → Power button** and choose Luna as the
*digital assistant app*. If holding the power button still brings up the power menu, switch it to
the voice assistant in your phone's power-button settings. You can also long-press the app icon
and pick **Talk**.

In the same place:

- While Luna is the phone's digital assistant, the app stays ready between questions. Some phones
  close it in the background anyway; if yours does it often, the chat says so once, with a link
  to the app's battery settings.
- **Use on the lock screen** (off by default) lets it answer without unlocking, with the power
  button or "Hey Luna", but only partly. On the lock screen it sees none of your memory, photos
  or chats. It can set reminders, events, alarms and timers, change phone settings, work out sums
  and look things up. For anything else, like remembering something or messaging someone, it asks
  you to unlock first and then carries on.
- **Voice** and **Answer out loud** set how it speaks. **Speak in Murf's voice** (on by default)
  reads replies in the voice Luna's calls use, made by Murf through Luna's server. Offline, or
  with it off, the phone speaks instead: a natural voice that sounds like a person (a 334 MB
  download that uses about 600 MB of memory while it talks), or the phone's own voices.
- **Wake word** (off by default): say "Hey Luna" to open the assistant, from anywhere, this app
  included, either only while charging or always. **Try it** checks that it hears you. Two small
  models in the app listen on the phone, one of them trained for this phrase, and the assistant
  opens when either hears it. Nothing they hear is kept or sent. It needs the app to be your
  digital assistant app, and it doesn't listen while the phone is locked unless **Use on the lock
  screen** is on.

### Optional extras

- **Photo and memory search** downloads by itself once photos are allowed. If it didn't finish,
  get it from Settings → Photo and memory search. Without it, past chats are still found by
  keywords.
- **Photos, files and messages** are offered on a page when the app first opens; each can be
  skipped and turned on later. Photos and faces are indexed on their own from then on (a
  notification shows it while the first index runs in the background). In **Photos** in the chat
  drawer, **Find people in your photos** groups faces and names them from your contacts' photos.
- **Files**: All files access, from that page or Android's settings, lets it find files by name.
- **Messages**: Settings → Messages → **Keep messages for search**, then allow notification access.
  To search your SMS from before too, make it your digital assistant and tap **Allow SMS**.
  Messages are kept on the phone as long as your chats.
- **Health data**: Settings → Health data → **Manage access**, to let it read Health Connect.
- **While you were away**: Settings → While you were away, then allow notification access. The
  notifications are read on the phone and kept for two days.
- **Live voice (Agora)**: a phone call with Luna in Indian English, with the family hub behind
  her, and a call at each medicine's time, through the same server as the chat. **Call** in the
  chat, the power button and "Hey Luna" start a call while the phone is online. Messages to the family
  go from Luna's WhatsApp, in a call or the chat, and Luna tells you what they answer: in the call
  as it comes, otherwise aloud while the app is open, or as a notification, and in your chat. See
  [Live voice](docs/TECHNICAL.md#live-voice-agora).

### Models and licences

- **Luna** itself: [GNU General Public License v3](LICENSE). The natural voice's
  pronunciation comes from eSpeak NG (GPL v3), linked into the app through sherpa-onnx, so the
  whole app is under the same licence.
- **Luna's replies and voice** come from services her server calls, under their own terms:
  Google's Gemini writes them, Murf speaks them, and Agora's Conversational AI carries calls.
- **EmbeddingGemma 2 (text and vision, 440M)**, memory search and photo search: by Google,
  downloaded from litert-community on Hugging Face, Apache License 2.0.
- **YuNet** (MIT) and **SFace** (Apache License 2.0), finding and telling faces apart for People:
  from OpenCV Zoo, converted to TensorFlow Lite; SFace's weights stored as 8-bit. See
  `app/src/main/assets/faces/NOTICE.txt`.
- **Place names** for searching photos by where they were taken: from
  [GeoNames](https://www.geonames.org), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
  reduced by `tools/places`. See `app/src/main/assets/places/NOTICE.txt`.

## Privacy

- Your memory, photos, files, messages, health data and chat history are kept on the phone.
- **Luna's server** writes every reply, so a message sends it: the chat's recent messages (a
  voice message as the words the phone heard), a photo you attach, the screen you called her
  over only when you ask about it, and the context a call gets (your core facts, the agenda,
  pinned lists and today's steps and sleep if allowed; none of these on the lock screen). What
  her tools find for that message goes too: memory or photos searched, a file she reads, the
  messages she looks up. The server writes with Gemini and, for spoken replies and calls, speaks
  with Murf; calls' audio goes through Agora.
- The internet is also used to download the photo search model, and for these only when you use them:
  - **Place reminders**: the place's name ("DMart") and a rough area about 30 km across go to
    OpenStreetMap's [Nominatim](https://nominatim.openstreetmap.org) search, to find it. Never the
    reminder, nor exactly where you are.
  - **Maps** for place reminders load from [OpenFreeMap](https://openfreemap.org).
  - **Report this reply** (the flag under a reply): that reply, the question before it and the
    reason you pick go to the developer through a Google Form. Nothing that says who you are.
- Card numbers, ID numbers and photos of your documents stay hidden until you confirm it's you
  with your fingerprint, face or PIN.
- No account, no ads, no analytics.

## Troubleshooting

| Problem | What to do |
|---|---|
| "App not installed" | It needs a 64-bit phone with Android 8 or newer, and room for the app. |
| Play Protect warns about it | Expected for apps from outside Google Play. Choose to install anyway. |
| "To answer that I need Luna's server" | Set its address and key in Settings → Live voice and family. |
| "I need the internet to answer that" | The phone is offline; the phone's own commands still work. |
| The photo search download stopped | Open the app again; it carries on from where it stopped. |

## For developers

The app is Kotlin and Jetpack Compose; replies come from Luna's server in `server/` (Node and
TypeScript: Gemini, Agora Conversational AI, Murf). [LiteRT-LM](https://ai.google.dev/edge/litert-lm/android)
runs only EmbeddingGemma 2, for photo and memory search.
To build it you need JDK 17, the Android SDK, NDK 28.2.13676358 and CMake 3.22.1 (for
sqlite-vec):

```bash
./gradlew :app:assembleDebug
```

[docs/TECHNICAL.md](docs/TECHNICAL.md) explains how it all fits together: the memory system,
tools, the power-button assistant, live voice, and the measurements behind each choice.

**An APK to share**, for a phone you can't connect:

```bash
./gradlew :app:demoApk -Pdemo
```

That builds for 64-bit phones only, compresses the native code, signs with the local debug key
(see `app/build.gradle.kts`), and writes `apk/Luna.apk`. APKs and videos stay out of
git: share them directly.
