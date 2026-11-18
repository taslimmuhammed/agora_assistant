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
- reaches the family and the shops through a Telegram bot, and tells her their answers during the
  call (Agora `/speak`) or at her next one;
- follows up: asks again when a task goes unanswered, tells her caregiver when a dose isn't
  confirmed 30 minutes after its time, and sends an 8 PM digest.

```
phone ──RTC audio──▶ Agora ConvoAI ──/v1/chat/completions──▶ this server ──▶ Gemini Flash
  ▲  ◀──RTM transcripts──┘    ▲ Murf Falcon TTS               │  │
  └──────── WebSocket /bridge (phone tools) ──────────────────┘  └──▶ Telegram (family, shops)
```

## Setting up (about an hour)

1. **Agora.** At [console.agora.io](https://console.agora.io), create a project with an **App
   Certificate** (secured mode) and turn on **Conversational AI** for it. Copy the App ID and
   App Certificate. Under **RESTful API**, add a key and download the Customer ID and Customer
   Secret: the secret is shown only once.
2. **Murf.** Get an API key at [murf.ai/api](https://murf.ai/api). Pick a voice that speaks both
   en-IN and hi-IN (Anusha, Priya, Aarav…) and check its exact id:
   ```bash
   curl -s -H "api-key: $MURF_API_KEY" "https://api.murf.ai/v1/speech/voices?model=falcon-2.1"
   ```
3. **Gemini.** An API key from [aistudio.google.com](https://aistudio.google.com), and the id of
   the current Flash model for `LLM_MODEL`.
4. **Telegram.** Message [@BotFather](https://t.me/BotFather), send `/newbot`, and copy the token.
   Then each person opens the bot and sends who they are: `/start rahul`, `/start priya`,
   `/start ramesh-stores`, `/start dr-rao` (the ids in `seed/family.json`). For a family group,
   add the bot to it and send `/family` there. `/status` anywhere shows the day so far.
5. **Settings.** `cp .env.example .env` and fill it in. `APP_KEY` and `LLM_PROXY_SECRET` are
   any long random strings; the phone needs `APP_KEY` too (`live.appKey` in the app's
   `local.properties`).

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

Without `PUBLIC_URL` the server still runs (Telegram polls for updates instead of using a
webhook), but calls can't start.

**Deploying.** On Railway or Render: root directory `server`, start command `npm start`, the same
variables as `.env`, and a region near India (Singapore). Use an instance that doesn't sleep: a
cold start would land in the middle of a call. Give `data/` a persistent disk if the Telegram
links should survive a redeploy; otherwise everyone sends `/start` again.

## Trying a turn without the phone

With the keys set, this checks Gemini, the family tools and Telegram end to end, with no Agora
call and no phone (phone tools then answer that the phone isn't connected):

```bash
SESSION=$(curl -s -X POST localhost:8080/session/token -H "X-App-Key: $APP_KEY" \
  -H 'Content-Type: application/json' -d '{}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).session_id')
curl -N "localhost:8080/v1/chat/completions?session=$SESSION" \
  -H "Authorization: Bearer $LLM_PROXY_SECRET" -H 'Content-Type: application/json' \
  -d '{"stream":true,"messages":[{"role":"user","content":"Ask Rahul to take me to Dr. Rao at 4:30"}]}'
```

Rahul's Telegram gets the request with **I'll do it** and **Can't**.

## For the demo

Each takes the `X-App-Key` header; the phone's debug buttons call them too.

| Call | Does |
|---|---|
| `POST /demo/reset` | Clears tasks, requests, check-ins and events; keeps the Telegram links |
| `POST /demo/missed-dose` | Flags the latest dose to her caregiver now, unless she took it (`{"dose_id":"bp-am"}` for a given one) |
| `POST /demo/nudge` | Asks unanswered tasks' assignees again now; a second call tells the family |
| `POST /demo/digest` | Sends the evening digest now |

`GET /hub/state?key=…` is everything the hub holds, and `GET /hub/stream?key=…` each event as
it happens, for a dashboard.

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
| `src/telegram.ts` | The bot: links, button taps, replies passed on to her |
| `src/followups.ts` | Nudges, missed doses, the digest |
| `src/announce.ts` | News for her: spoken during a call, or kept for the next |
| `src/agora.ts` | Tokens, and the agent's join, leave and speak |
| `src/hub.ts` | The family hub in SQLite |
| `src/when.ts` | Her time words ("4:30", "kal subah") as times, in her zone |

```bash
npm test           # node:test, with fakes for Agora, the model, the phone and Telegram
npm run typecheck
```

The family, the shops and the medicines are in `seed/family.json`; the persona in `src/prompt.ts`
assumes the seed's elder (Amma).
