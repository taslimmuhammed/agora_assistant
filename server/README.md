# Luna server

The cloud half of Luna's live voice mode, for the Build with Agora hackathon. Agora's
Conversational AI Engine hears her (ASR), sends each turn here as its custom LLM, and speaks the
reply with Murf Falcon. This server:

- starts and stops the agent, and makes the RTC + RTM tokens the phone and the agent use;
- runs each turn with Gemini Flash (through Google's OpenAI-compatible endpoint), offering the
  server's family tools and the phone's own tools together;
- sends phone tools to the phone over a WebSocket (`/bridge`) and waits for the result: the
  phone's ToolExecutor sets reminders, edits lists, sets alarms, reads health data;
- keeps the family hub (SQLite): who's who, medicines and check-ins, tasks asked of the family,
  requests to shops and clinics;
- reaches the family and the shops on WhatsApp, at the numbers in the seed (Baileys, signed in
  the way WhatsApp Web is), and tells her their answers during the call (Agora `/speak`) or at her
  next one;
- follows up: asks again when a task goes unanswered, tells her caregiver when a dose isn't
  confirmed 30 minutes after its time, and sends an 8 PM digest.

```
phone ──RTC audio──▶ Agora ConvoAI ──/v1/chat/completions──▶ this server ──▶ Gemini Flash
  ▲  ◀──RTM transcripts──┘    ▲ Murf Falcon TTS               │  │
  └──────── WebSocket /bridge (phone tools) ──────────────────┘  └──▶ WhatsApp (family, shops)
```

## Setting up (about an hour)

1. **Agora.** At [console.agora.io](https://console.agora.io), create a project with an **App
   Certificate** (secured mode) and turn on **Conversational AI** for it. Copy the App ID and
   App Certificate; that's all the server needs; it signs its calls to Agora with a token made
   from them. (A Customer ID and Secret from Developer Toolkit → RESTful API also work, if set.)
2. **Murf.** Get an API key at [murf.ai/api](https://murf.ai/api). Pick a voice that speaks both
   en-IN and hi-IN (Anusha, Priya, Aarav…) and check its exact id:
   ```bash
   curl -s -H "api-key: $MURF_API_KEY" "https://api.murf.ai/v1/speech/voices?model=falcon-2.1"
   ```
3. **Gemini.** An API key from [aistudio.google.com](https://aistudio.google.com), and the id of
   the current Flash model for `LLM_MODEL`.
4. **The family's numbers.** `cp seed/family.json seed/family.local.json` and fill in each
   person's WhatsApp number with its country code (`"+91 98450 12345"`). The server uses the
   local copy whenever it exists, and git leaves it out.
5. **Settings.** `cp .env.example .env` and fill it in (here in `server/`, or at the repository
   root: `npm start` reads both, this one winning). `GEMINI_API_KEY`, `GEMINI_MODEL_ID` and
   `MURF_VOICE_ID` are read too, for `LLM_API_KEY`, `LLM_MODEL` and `MURF_VOICE`. `APP_KEY` and `LLM_PROXY_SECRET` are
   any long random strings; the phone needs `APP_KEY` too (`live.appKey` in the app's
   `local.properties`).
6. **WhatsApp.** Luna sends from a WhatsApp number linked to the server, the way WhatsApp Web
   is: Amma's in the story, a spare phone for the demo. Start the server, open
   `http://localhost:8080/whatsapp/qr?key=<APP_KEY>` (the terminal shows the code too), and on that
   phone go to WhatsApp → **Linked devices** → **Link a device** and scan it. The session stays in
   `data/whatsapp-auth`, so it's once. At each start the log says whose number isn't on
   WhatsApp. For a family group, add that number to the group and send `/family` in it from that
   phone; `/status` in any chat with it shows the day so far.

**About Baileys.** It isn't WhatsApp's own API: WhatsApp can restrict or ban a number it sees
sending automated messages. Link a spare number rather than your own, send to people who already
have a chat with it, and keep to the demo's handful of messages.

**Testing with one number.** Give Rahul (or anyone in the seed) the number you link. Luna's
messages to him then arrive in **Message yourself** on that phone, and what you type or react
there is his answer: `1`, `haan`, 👍. Nothing else you write from that phone counts. There's no
notification for messages to yourself, and while this is set, notes to yourself in that chat
reach her as his; for the demo itself, give him a phone of his own.

## Running it

Node 22.18 or newer (it runs the TypeScript directly, and keeps the hub in Node's own SQLite).

```bash
npm install
npm start
```

Agora must reach `/v1/chat/completions` over public https, so on a laptop put a tunnel in front
and set `PUBLIC_URL` to its address before starting:

```bash
cloudflared tunnel --url http://localhost:8080
```

Without `PUBLIC_URL` the server still runs, WhatsApp included, but calls can't start.

**Deploying.** On Railway or Render: root directory `server`, start command `npm start`, the same
variables as `.env`, and a region near India (Singapore). Use an instance that doesn't sleep: a
cold start would land in the middle of a call. Give `data/` a persistent disk, or WhatsApp asks
for a new scan after each deploy. On the laptop none of this applies.

## Trying a turn without the phone

With the keys set, this checks Gemini, the family tools and WhatsApp end to end, with no Agora
call and no phone (phone tools then answer that the phone isn't connected):

```bash
SESSION=$(curl -s -X POST localhost:8080/session/token -H "X-App-Key: $APP_KEY" \
  -H 'Content-Type: application/json' -d '{}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).session_id')
curl -N "localhost:8080/v1/chat/completions?session=$SESSION" \
  -H "Authorization: Bearer $LLM_PROXY_SECRET" -H 'Content-Type: application/json' \
  -d '{"stream":true,"messages":[{"role":"user","content":"Ask Rahul to take me to Dr. Rao at 4:30"}]}'
```

Rahul gets the request on WhatsApp, with **1** I'll do it and **2** Can't under it.

## How the family answers

WhatsApp shows no buttons to an account like this, so the choices under Luna's messages are
numbered. Any of these answer them:

- the number: `1`, or `1, I'll be there by 4` (the choice, and the rest is passed on to her);
- a short word, English or Hindi: `yes`, `ok`, `haan`, `no`, `nahi`, `can't`, `done`, `she took it`,
  `within the hour`, `kal`;
- a 👍 (or 👎) on the message;
- a reply quoting an older message, which answers that one rather than the latest.

With `LLM_API_KEY` set, Gemini reads everything else: a sentence in English, Hindi or Hinglish,
a voice note, a photo, a sticker, or any other reaction (🥰, 🙏). It picks the choice the reply
means, if any ("Sorry Amma, Pune mein hoon Sunday tak" is "Can't"), and says the rest for her in
plain English. The choice and the words reach her as one update: "Rahul can't do it: bring milk.
Rahul says: I'm in Pune till Sunday. Shall I ask someone else?" A location ("I'm at Apollo
Pharmacy, MG Road"), a shared contact or a document is described in words too.

Luna puts a ✅ on each note she'll pass on, and writes "✅ Luna will tell Lakshmi" only the first
time in six hours, so a chatty family isn't answered with a wall of the same line. A reaction on
one of her own messages is told to her in words ("Rahul sent love for your message: …").

**When Gemini is busy.** The big Flash models often answer 503 or hang. Calls and replies try
`LLM_MODEL` first; if it's busy, or silent for 8 seconds, `LLM_FALLBACK_MODEL` (default
`gemini-3.5-flash-lite`) answers for the next two minutes. With no key, or both busy, a text is
passed on as it is and settles nothing, and a voice note is "Rahul sent you a voice note." A
message a shop sends after confirming is kept with the order as its note.

## Family numbers from the phone

In the app, Settings → Live voice → Family on WhatsApp lists the family. Tap **Pick** to choose
someone's number in the phone's contacts (no contacts permission: the picker hands over only the
one chosen), or **Add from contacts** for someone new: a neighbour, the chemist. Numbers are kept
on the server, over the seed's. Each save says whether the number is on WhatsApp, and with "Luna
says hello" on, Luna introduces herself to a new number so her first message isn't from a stranger.

| Call | Does |
|---|---|
| `PUT /family/members/:id/phone` | `{"phone": "+91 …", "introduce": true}`; blank clears it |
| `POST /family/members` | `{"name", "relation", "role": "family" \| "caregiver" \| "provider", "phone", "introduce"}` |
| `DELETE /family/members/:id` | Removes someone added from the app; the seed's people stay |

## Answers outside a call

In a call, an answer is said as it comes. Outside one, the phone tells her. While Luna is closed,
each answer is a notification: the background asks `GET /family/answers` every minute while
someone may answer soon, otherwise every quarter of an hour. When she opens Luna (and every few
seconds while it's open), what came since she last looked is one short message from Luna in the
chat on screen, written by Gemini with times said from now ("Filu will bring your tablets this
evening at 6"), and spoken in Murf's voice, the one her calls use; offline, by Kokoro or the
phone's own voice. Her next call still opens with the latest answer.

The phone's chat sends to the family through Luna too: "message Filu: can you call me tonight?"
goes from Luna's WhatsApp at once, as in a call, when Filu is in the family. Anyone else, or an
SMS asked for, opens WhatsApp or SMS on the phone ready to send, as before.

| Call | Does |
|---|---|
| `GET /family/answers?after=<id>` | `{answers: [{id, kind, text, at, told}], last, waiting}`. With no `after`, the last few of the past 12 hours; `told`: she heard it in a call |
| `POST /family/briefing` | `{after, up_to?, greet?, audio?}`: `{text, up_to, audio}`, what Luna says as she opens the app, in one message (Gemini), with Murf's MP3 of it (base64) unless `audio` is false |
| `POST /family/speech` | `{text}`: `{audio}`, Murf's MP3 of it, in her calls' voice; null when Murf can't |
| `POST /family/tool` | `{"name": "message_family", "args": {"to": "Filu", "text": "…"}}`: one of Luna's family tools without a call; a name not in the family is a 400 |
| `POST /family/whatsapp` | `{"to": "Ravi", "text": "…", "name"?, "phone"?}`: the phone's `send_message` WhatsApp, from Luna's number, to anyone. Someone in the hub by name; else 404 `{unknown: true}`, and the phone asks again with the number from her contacts, who joins the hub as a `contact` so their answer is told to her (mid-call, or when she opens the app) |

## For the demo

Each takes the `X-App-Key` header; the phone's debug buttons call them too.

| Call | Does |
|---|---|
| `POST /demo/reset` | Clears tasks, requests, check-ins and events; keeps the family group |
| `POST /demo/missed-dose` | Flags the latest dose to her caregiver now, unless she took it (`{"dose_id":"bp-am"}` for a given one) |
| `POST /demo/nudge` | Asks unanswered tasks' assignees again now; a second call tells the family |
| `POST /demo/digest` | Sends the evening digest now |

`GET /hub/state?key=…` is everything the hub holds, and `GET /hub/stream?key=…` each event as
it happens, for a dashboard. `GET /whatsapp` says whether WhatsApp is signed in, and
`/whatsapp/qr?key=…` shows the code to scan when it isn't.

## The phone's side

Every request sends `X-App-Key`.

| Call | Body | Answer |
|---|---|---|
| `GET /family` | | `{elder, members, doses}`: the doses set the check-in calls |
| `POST /session/token` | `{user_id?}` | `{session_id, app_id, channel, uid: 1001, token, agent_uid: 2001, bridge_url}` |
| `POST /session/start` | `{session_id, context}` | `{agent_id, status}` |
| `POST /session/stop` | `{session_id}` | `{ok: true}` |

The token covers RTC and RTM, with `"1001"` as the RTM user id. The phone logs in to RTM,
subscribes with the client toolkit, joins the channel and opens the bridge, and only then calls
`/session/start`, so it hears the agent from its first word.

`context` is what the phone shares for this call: `user_name`, `locale`, `time_zone`, `now`,
`purpose` (`user` or `checkin`, with `dose_id`), `locked`, `memory` (its prompt prefix: core
facts, last summary, agenda, notes), `lists`, `health`, `tool_rules` and `device_tools` (its
ToolCatalog declarations, `{name, description, parameters}`).

On the bridge:

```jsonc
// server → phone: run one of your tools; answer within 8 seconds
{"type":"tool_call","call_id":"…","name":"add_task","args":{"title":"Call Meena","when":"kal subah 9 baje"},"turn_id":"7","user_text":"…"}
// phone → server
{"type":"tool_result","call_id":"…","result":"{\"ok\":true,\"id\":42,\"due\":\"Sun 22 Nov 09:00\"}","chip":{"title":"Reminder","detail":"Tomorrow 9:00 AM"}}
// server → phone: a family tool acted (a card for the call screen)
{"type":"action","tool":"assign_task","ok":true,"title":"Asked Rahul","detail":"Take Amma to Dr. Rao's clinic · 4:30 PM today"}
// server → phone: someone answered (also spoken in the call)
{"type":"hub_update","kind":"task_reply","text":"Rahul said yes: take Amma to Dr. Rao's clinic, 4:30 PM today.","item":{"task":1,"status":"accepted"}}
```

## Code

| File | What it does |
|---|---|
| `src/app.ts` | The routes and the bridge's WebSocket |
| `src/llm.ts` | The custom LLM: one streamed turn, its tool rounds, tool calls rebuilt from Gemini's deltas |
| `src/prompt.ts` | The persona and each turn's system message; the greeting |
| `src/tools.ts` | `assign_task`, `message_family`, `request_service`, `log_checkin`, `family_status` |
| `src/bridge.ts` | Phone tools over the WebSocket, with a timeout |
| `src/whatsapp.ts` | WhatsApp through Baileys: sign-in, sending with numbered choices, reading answers |
| `src/replies.ts` | What an answer does: a task, an order or a dose settled, a note passed on to her |
| `src/followups.ts` | Nudges, missed doses, the digest |
| `src/announce.ts` | News for her: spoken during a call, or kept for the next |
| `src/agora.ts` | Tokens, and the agent's join, leave and speak |
| `src/hub.ts` | The family hub in SQLite |
| `src/when.ts` | Her time words ("4:30", "kal subah") as times, in her zone |

```bash
npm test           # node:test, with fakes for Agora, the model, the phone and WhatsApp
npm run typecheck
```

The family, the shops and the medicines are in `seed/family.json`; the persona in `src/prompt.ts`
assumes the seed's elder (Amma).
