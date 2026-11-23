# Unified Bot App — plan (investigation only, no building)

One Android app showing both of the operator's bot servers in one list: Rakazo
(the Rakazo host) and OpenMausBot (the-openmausbot-host). Bots stay on their own servers and
cannot talk across them; the app just lets the operator see and chat with all of
them from one place.

Sources: `api-rakazo.md` and `api-openmausbot.md` in this folder, mapped
from the local source clones (rakazo @ 64dcd8a, openmausbot @ c444029
v0.1.170, checked 2026-11-18). the operator runs MausBot v0.1.161 — drift is
flagged where it matters.

## Verdict

Both servers are usable by a third-party client. Neither needs any change
on the server. Rakazo's API is one typed contract shared by its own web
and mobile apps, so a third-party client speaks the same API the
first-party apps do. MausBot's API is plain REST + SSE with pairing auth.
The hard parts are screen viewing and push, not chat.

## The two halves

### Rakazo (the Rakazo host)

- **Transport:** oRPC over HTTP. `POST {origin}/rpc/{procedure}`, body
  `{"json": <input>}`, response `{"json": <output>}`. Live updates are the
  same POST with `Accept: text/event-stream` (SSE). Contract:
  `packages/contracts/src/rpc/app-contract.ts`.
- **Base URL:** the server's public origin (web origin proxies `/rpc`,
  `/api`, `/novnc` to the API on port 3100, which is loopback-bound).
  Public servers need `https://`. Probe: `POST {origin}/rpc/health`.
  Server can refuse outdated clients (`RAKAZO_MIN_CLIENT_BUILD`) — send a
  plausible client-build header.
- **Auth:** better-auth, email/password or email-OTP.
  `POST {base}/api/auth/sign-in/email` → bearer token (from
  `set-auth-token` header / session cookie / body). Send
  `Authorization: Bearer` + `Origin: rakazo://` on every call, plus
  `X-Rakazo-Space-Id` (space membership enforced server-side).
- **Roster:** `spaces/list` returns the whole roster in one call (bots,
  groups, sections). `bots/list` → `BotSchema[]`. No avatar image URL —
  avatars are derived client-side from `color` + `avatarSet`
  (`"organic" | "rakazo"`). `bots/setComputer` takes `"team" | "dedicated"`.
- **Chat:** threads by `{botId}` or `{groupId}`. `threads/head`,
  `threads/get` (snapshot incl. run + computer status),
  `threads/messages` (paginated by sequence number),
  `threads/send` (text ≤100k chars, `artifactIds`, mentions,
  `clientNonce` idempotency), `threads/subscribe` (SSE `ProductEvent`s).
  Messages are typed **blocks** (`text`, `image`, `file`, `ask`,
  `choice`, `tool_question`, `mcp_approval`, `progress`, …) — no plain
  text field. Never poll; re-read on stream signals.
- **Screen:** `computer/status`; `computer/screenUrl` → noVNC page
  `/novnc/session/{view|control}/{expiresAt}.{token}` with a **single-use**
  sign-in code — most practical in a WebView. `computer/preview` → still
  image data URL (cheap v1 alternative). Take Control:
  `computer/takeover` → lease, `computer/input` (key/pointer/clipboard/
  scroll), `computer/release`. Files: `computer/files`, `readFile`,
  `downloadFile`, `uploadFile`.
- **Approvals:** cards arrive as `ask` / `tool_question` / `mcp_approval`
  blocks; answer via `threads/answer` (`"allow"` / `"always"` / `"deny"`)
  and `threads/answerToolQuestion` (`accept` / `decline`). Unwatched-run
  cards expire after 30 min.
- **Push:** Expo push tokens (`notifications/registerPush`) — needs the
  app's own Expo/Firebase project, cannot reuse Rakazo's. In-app alerts
  via `notifications/subscribe`. **No typing indicator or presence** —
  "bot is working" comes from `runs/list` and live run events.

### OpenMausBot (the-openmausbot-host)

- **Transport:** plain Node `http` server, port 8799, loopback by default.
  JSON API under `/api/*`, SSE event stream, desktop viewer. No separate
  public API spec; web app and phone app speak the same routes.
- **Base URL / reachability — the critical question:** four ways in: LAN
  (`--lan`, same Wi-Fi), Tailscale (`--tailscale`), own HTTPS
  (`--public-url` / `--domain` / `--tunnel`), or **MausBot Relay**
  (`serve --relay`: server dials out to `relay[1-9][0-9]?.openmausbot.com`,
  E2E-encrypted with Noise). **If the app must use the relay, it has to
  implement the companion E2E protocol (`companion/src/e2e/`) — a big
  job. Direct HTTPS avoids that entirely.** How the operator's phone reaches
  the-openmausbot-host today is unknown from source; check the box.
- **Auth:** pairing-based, no password. 12-char code (`7KQ2-M9XD-H4TP`
  format) or `omb_pair_…` QR credential via `POST /api/auth/pair` or
  `POST /api/pair` → session bearer token. Sessions last 30 days
  (sliding). Keep the token per server.
- **Roster:** `GET /api/bots` → `WireBot[]` (id, name, title, soul,
  avatarUrl, unread, `modelSelection {instanceId, model}`, computer
  surface, approvalMode…). Engines/models via `GET /api/internal/models`.
- **Chat:** `GET /api/threads/:id/messages` (cursor pagination),
  `POST /api/bots/:id/messages` (202, 16–80 char `sendId`),
  `GET /api/events` (SSE: hello frame + replay via `Last-Event-ID` /
  `?since=`, auth by cookie/bearer or `?ticket=`). Messages are
  `WireMessage` with `kind` (`text`, `options`, `activity`, `screen`,
  `connector`, `secret`, `signin`, `routine.run`, `call`, `widget`,
  `app`, …).
- **Screen:** `GET /api/computers/frames` (base64 JPEG thumbnails,
  admin-only); full live view is RFB/VNC-over-WebSocket at
  `/api/desktop-viewer/<target>/websockify` — **admin scope and
  same-origin only**, and how the official phone app satisfies
  same-origin is unresolved. Take Control needs `botId` +
  `controlLeaseId`. Screen frames also arrive as `screen` messages
  (base64 png) and SSE `screen` frames.
- **Approvals:** cards arrive as messages (`options` / `secret` /
  `signin` kinds); answer via `POST /api/bots/:id/respond`
  (`behavior: allow | deny | answer`). A bot's own shell can never
  approve its own card.
- **Push:** server code exists but is **switched off by default**; needs
  owner keys (`OMB_PHONE_PUSH`, `OMB_PUSH_CREDENTIAL`) and an app built
  with FCM config. Assume off on the-openmausbot-host. In-app notifications come
  from the live event stream while the app is open.

## App shape

- **Stack:** native Android (Kotlin), same approach as the Links and
  Drive apps. Two API client modules (Rakazo oRPC client, MausBot
  REST/SSE client), one shared UI: server accounts in Settings, one
  unified bot list (badged per server), one chat thread view that renders
  each server's message kinds, secure storage for two tokens.
- **v1 — chat:** both auth flows (Rakazo email login, MausBot pairing
  code/QR), roster for both servers, read history, send messages, live
  SSE updates, mark read/unread. This is the tractable build.
- **v2 — screen + approvals:** Rakazo noVNC in a WebView (or preview
  stills), MausBot frames / VNC-over-WebSocket; Take Control on both;
  approval cards on both. Blocked on the two screen-viewer open
  questions below.
- **v3 — push:** own Firebase project. Rakazo needs its own Expo/Firebase
  project; MausBot needs the server keys enabled plus FCM config in the
  app build. Until then, in-app alerts while open only.

## Design notes for down the road

- **MausBot multi-threads (the operator, 2026-11-19):** a MausBot bot has a
  main thread (`mainThreadId`) plus more threads (`tasks[]`, newest
  first), and managing them is a pain. The unified app should handle
  threads better than the MausBot app does — e.g. a clean thread switcher
  per bot, auto-titles, easy archive — rather than copying the current
  layout. Rakazo is simpler here: one thread per bot (plus group chats),
  so the thread UI needs to cover both shapes.

## Hub integration (possible)

- The Bot Hub already has a simple REST API at
  `https://hub.welivethisway.com` — `GET /api/rooms`,
  `GET /api/rooms/{room}/messages?since=&limit=`,
  `POST /api/rooms/{room}/messages` (with replyTo), bearer-key auth per
  member. Same API the check-in job uses. Easiest of the three servers to
  add.
- In the app: a Hub section with the room list, read, post, @mention.
  Auth is the operator's own member key, not Muse-proto's.
- Caveats: no live push/SSE endpoint found in the helper — the app would poll (refresh on open + periodic); rate limits exist (visitors ~30 req / 6 posts per min; member limits unverified); hub lives on the Rakazo host so it's dark when the box is off.
- **Live notifications (the operator, 2026-11-19): required. Verified from hub source (~/workspace/bot-hub, v0.11.0): the hub ALREADY has phone push via ntfy** (`src/notify.js` — POSTs to ntfy.sh/<random topic>, topic in data/ntfy.json, admin-only; `hubctl notify test|rotate`). Currently wired only to join requests (1/min cap). Extending it to @mentions/replies is a small change: call the same push when a message mentions someone. No Firebase needed — the operator installs the ntfy app and subscribes to the hub's topic, or the unified app subscribes to the topic itself. The hub also has outbound wake webhooks (`src/wake-webhooks.js` — POSTs a JSON note to a member's HTTPS URL on @mention) as a second live primitive. No SSE/live endpoint found — the REST API is poll-based. The watcher-sidecar option is now the fallback, not the plan.
- **Hub section scope (the operator, 2026-11-19): @groups, #rooms, DMs — all doable from the hub REST API (verified in src/server.js).** Rooms: `GET /api/rooms`, `GET/POST /api/rooms/<room>/messages`, `GET /api/members?room=`. @groups: `GET /api/groups` for autocomplete; post text with @group and the server expands it and wakes each member once (only the owner + `BOT_HUB_GROUP_WAKE` members can trigger a group wake; @class in #classroom is owner + `BOT_HUB_CLASS_WAKE` only). DMs: `GET /api/dms`, `POST /api/dms` {member, text} — 1:1, member-to-bot DM wakes that bot; visitor-to-bot DMs blocked.
- **A bot that isn't on the hub:** no member record, no key — the hub doesn't know it. @mentions of its name are inert text (no wake, no DM, no notification). It still works fine in the app's main bot list via its own server. To bring it onto the hub, the admin adds it in Manage → Members with a key + a wake path (local webhook, wake webhook, or polling agent). Private rooms also enforce rosters — a bot off a room's roster gets 403 and its @mentions there are skipped. Wake caps: 40/hour, one at a time per bot, reply can wake one more bot then the chain stops.
- **Hub admin in the app (the operator, 2026-11-19): invites + keys were a pain to do before, so the app must make them easy.** Invite: `POST /api/admin/invites` {name, days} → ready-to-share invite text/link, shared via the Android share sheet. Pending requests: `GET /api/admin/requests`, approve/deny `POST /api/admin/requests/<id>/approve|deny`. Add bot: `POST /api/admin/members` → key shown ONCE, display with a copy button. Rotate key: `POST /api/admin/members/<id>/rotate`. Admin screens hide gracefully on 403 for non-admin keys.
- **Home view (the operator, 2026-11-19): the app opens on the bot list — the general view, all bots, like the other apps. Hub rooms are one tap away (Hub button), never on the home screen. Inside the Hub screen, public rooms + DMs show first; private rooms sit collapsed under a "Private rooms" tap-to-expand section, each labelled private (the API returns `private` per room — use it).**
- **Broadcast (the operator, 2026-11-19): a Broadcast row at the bottom of the Hub screen — pick a target (@everyone, @sales, @coders, any group from `GET /api/groups`), type once, send. The app posts "@group <text>" to #general (default) and the hub expands the tag and wakes each member once. @everyone/@all/@here are built-in reserved names (not creatable groups); @sales/@coders are DB groups — if missing, the app's Admin area creates them (`POST /api/admin/groups`) and adds members (`POST /api/admin/groups/:name/members`). Only the owner + BOT_HUB_GROUP_WAKE slugs can trigger a group wake, and the operator is the owner. CROSS-SERVER (the operator, 2026-11-19): the broadcast must transcend both servers — a hub post reaches hub members on BOTH servers (each member wakes through its own path, whichever box it runs on), but bots NOT on the hub are invisible to @groups. So: for @everyone the app ALSO sends the text as a direct message to every bot on both servers (Rakazo threads/send + MausBot send); for @sales/@coders, non-hub bots are only covered once added to the hub (Admin → Add a bot, then Groups). HUB-DOWN (2026-11-20): @everyone still works when the hub is down — the picker offers it DM-only (no group targets, no wake check, which can't run), the hub post is attempted first but the DMs always go out afterwards regardless, and the toast reports both parts. Server names in the confirm dialog and the bot detail line come from the base URL in Settings (host decides: the-rakazo-host vs the-openmausbot-host), not from the server type.**
- **Minting bot keys (the operator, 2026-11-19): how the operator mints a key for a bot to register on the hub — in the app: Hub → Admin → Add a bot (key shown once, copy button); or on the Rakazo host: `node scripts/hubctl.js add-bot <name>` (prints key once). The bot registers with that key (its wake path / bot-hub-agent).**
- **Avatars + sorting (the operator, 2026-11-19): pull each bot's avatar from its server and show it in the app the way the official apps do, and add a sort control for the bot list. Avatar fields/flows under investigation first (`avatars-sorting-investigation.md`).**
- **App art (the operator, 2026-11-19): use his images — Miami art-deco skyline at sunset (`workspace/user/files/1000049508_1_h5qa.png`, 816x421) as the main-screen header banner, and the WE LIVE THIS WAY logo in the header. the operator then sent the big logo (`workspace/user/files/1000049509_3_o384.png`, 1460x720) — use it as the app launcher icon too. All three files are JPEGs despite the .png extension.**
- **Listening (the operator, 2026-11-19): make it easy to set some bots to always listen and others to only answer when called. The hub already has this: a room "listener" bot wakes on EVERY post in that room with no @mention needed (`setListener`, `POST /api/admin/rooms/:name/listeners` {member, on}); a non-listener only wakes on @mention. Constraints (hub.js:324-343): listeners only in members-only rooms (visitor rooms refuse them), and a bot needs wake on + an omb_bot_id first. The room object already returns its `listeners` list. App work: a Listening screen in Admin — pick a room, toggle each bot Always listen / Only when @mentioned, with plain explanations when a room or bot can't listen.**
- **DM close (the operator, 2026-11-19): a DM can't be closed on the hub — it's a permanent room, no close/archive for members, so it stays in the list and a new private message brings it back. The app can only hide it locally on the phone (a Hide option on a DM in the list; it reappears if they message again, because the hub still has it).**
- **Bot list details (the operator, 2026-11-19): next to each bot's name show server name (the Rakazo host / the-openmausbot-host), server type (Rakazo / MausBot), model type, and a working indicator — as small detail text under the name, NOT the big badges he had removed. Format the operator gave: `a collaborator - Mausbot - Deepseek Flash 4 1018 - the-rakazo-host`, i.e. Name - Server type - Model - Server name, one line — with the CORRECT numbers as the servers report them (the operator: "except of course of the correct numbers" — his 1018 was a slip for 1017; never hardcode the example, always use the live modelId). the working dot is green when the bot has an active run, grey when idle (Rakazo `isWorking`, RakazoClient:270; MausBot equivalent to be found from source).**
- **Row subtitle = last thing the operator said (the operator, 2026-11-21):** the line under each bot's name is now his last message to that bot — his last order — shown as `You: <text>`, one line, capped at 140 chars, max 2 lines in the row. MausBot: `GET /api/bots?messages=20` carries the thread page in the same call, last user-role text wins. Rakazo: the `threads/get` snapshot already fetched for the working dot in `refreshWorking` is parsed the same way. No user message yet → the bot's title/description stays as the fallback. Server name in the detail line no longer trusts the address typed in Settings (the operator's MausBot bots showed "the-openmausbot-host" while running on the Rakazo host): `refreshServerNames` asks the Hub's admin members for each bot's real machine (`ombBotId` → `machine`, the-rakazo-host/laptop) and overrides the label; Hub missing/down → URL-derived name stays. Built 2026-11-21 13:29 ET.

## Images in chat (the operator, 2026-11-19)

- Attach images and view images in bot chats. Both platforms have image concepts (Rakazo `image` blocks + `threads/send` `artifactIds`; MausBot `WireMessage.attachments` + `GET /api/threads/:id/messages/:messageId/image`), but the exact upload/send flows were not traced in the API maps — under investigation first (report: `images-investigation.md` in this folder), then built.

## Decisions needed before building (not code)

1. **How does the operator's phone reach the-openmausbot-host MausBot today** — relay,
   public URL, tunnel, or Tailscale? This decides whether the app needs
   the relay E2E protocol (big) or plain HTTPS (easy). Check the box or
   ask a collaborator.
2. **the Rakazo host Rakazo base URL** (tunnel host) and whether its web origin
   proxies `/rpc` the same as the compose default; server version vs the
   2026-11-18 commit; `RAKAZO_MIN_CLIENT_BUILD` values on the deployed
   server. 2026-11-19: confirmed base URL is
   `https://rakazo.example.com` — `POST /rpc/health`
   returns `{ok:true, version 0.1.0}`. (`rakazo.welivethisway.com`
   resolves too but returns an empty reply.)
3. **Screen-viewer viability:** Rakazo noVNC page from a foreign WebView;
   MausBot desktop-viewer same-origin rule from a native app. Both need a
   runtime check against a live server.
4. **Rakazo artifact-upload flow** (behind `threads/send` `artifactIds`)
   — not traced from source.
5. **MausBot version drift** (0.1.161 running vs 0.1.170 mapped) — verify
   the key calls (`GET /api/bots`, `POST /api/bots/:id/messages`,
   `GET /api/events`, `POST /api/bots/:id/respond`) against the-openmausbot-host.

## Long-term direction (not now)

- **Universal / "hub compatible" (the operator, 2026-11-19):** eventually the
  app should be advertiseable as hub-compatible so other people running
  their own hubs can use it, and accept 5+ different agent platforms
  plus other people's hubs and tools. Explicitly deferred: get a fully
  functional app for the operator first; generalization comes after.
- Why it's feasible later: the hub is open-source (MIT) and every hub
  running it speaks the same REST API, so "hub compatible" means
  speaking that API, not a custom integration per hub. Each agent
  platform is one more client module beside the Rakazo and MausBot ones.

## What this investigation did not do

No code written, no servers contacted, no accounts created, no keys or
tokens recorded anywhere. Everything above comes from reading the two
local source clones.

## Avatars + sorting (built 2026-11-20, in the APK)

- **Avatars, bot list + chat header** (`api/BotAvatar.kt`, `api/AvatarData.kt`,
  `api/SvgPath.kt`, `BotAvatarView` in `item_bot.xml` and `chat_header.xml`).
  Rakazo: derived client-side from `color` + `avatarSet` — data-image photo,
  one of the 8 shipped SVG mascot shapes, the Rakazo logo mark, or a seeded
  organic blob — with the same shapes, colours and eye positions as the
  official app. MausBot: `avatarUrl` fetched with the pairing token
  (crop/zoom/focus honoured), falling back to the gradient mouse mascot
  (static, idle face, no blink). The old initials-in-a-circle row is gone
  from the bot list; the colour bug is fixed (MausBot colour names mapped to
  real colours, Rakazo `hex::shape_N` and data-image forms handled).
  `item_bot.xml` keeps the old `tvAvatar` TextView for the Hub rows, which
  reuse the layout as a generic initials circle — Hub code untouched.
- **Sorting** (`Prefs.BotSort`, spinner on the home screen): Pinned first,
  Most recent, Name (A–Z). Default Pinned first then most recent. Choice
  persists in SharedPreferences. Rakazo uses `BotSchema.updatedAt` (epoch
  or ISO) + `pinned`; MausBot uses max over `tasks[]` of
  `updatedAt`/`lastThreadOrderAt`/`createdAt` + `pinned`.
- **Deliberate deviations:** eyes are a contrasting ink instead of the
  official cut-outs (so they read on any background); the logo mark is the
  flat body path + eyes (no hands/lighting); the mascot is static.
  Avatar photos share the 12-entry session-only `ImageStore` with chat
  images. Untested live: which avatar forms the operator's actual bots use,
  MausBot photo fetch over the pairing token, and whether Rakazo
  `updatedAt` tracks last-message activity.
