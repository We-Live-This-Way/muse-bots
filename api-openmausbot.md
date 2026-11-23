# OpenMausBot (MausBot) server API — as a third-party Android client would use it

Investigation only. No code written, no live server contacted.
Source: local clone `~/workspace/vision-investigation/openmausbot`, commit
`c444029f3dc97a65caab20b4c9a9f398f435c2f2`, v0.1.170 (package.json).
the operator runs **v0.1.161** on the-openmausbot-host (`/opt/openbot-app`) — anything below may
differ by version; see §8.

Legend: **[V]** verified from source (file:line cited) · **[I]** inferred from
source · **[U]** unknown / not established from source alone.

## 0. What the server is

A plain Node `http` server (`server/index.ts`). One process serves the web UI,
the JSON API, an SSE event stream, and the desktop viewer. There is no separate
public API spec; the web app and the phone app both speak the same `/api/*`
routes below. The Android/iOS phone app source is **not** in this repo
(`apps/` holds only docs) — the wire protocol it speaks is
`packages/phone-protocol/src/`, and the shared wire types are re-exported by
`shared/wire.ts` (1-line re-export **[V]**).

## 1. Auth

**Base URL / port [V]:** `http://<host>:8799` by default —
`server/index.ts:655`: `PORT = Number(process.env.OMB_PORT || process.env.OGB_PORT || 8799)`.
Listens on `127.0.0.1` unless `OMB_LAN=1`, which binds `0.0.0.0` but destroys
any connection whose ends are not both private-network addresses
(`server/lan-address.ts`, `listenHost`).

**Auth model [V]** (`server/request-auth.ts`): every request passes
`resolveRequestAuth`. Two auth kinds:

- `loopback` — Host *and* Origin are both loopback. Trust is `owner` or
  `service`. Headless self-hosted default is `owner`; hosted/shared workspaces
  get `service` (`resolveLoopbackTrust`). Owner-trust loopback has full admin
  scope; service-trust loopback may reach only `SERVICE_ALLOW`
  (`request-auth.ts`, list includes `GET /api/health`, `GET /api/bots`,
  `GET /api/threads/:id/messages`, `POST /api/bots/:id/messages/guarded`,
  `POST /api/threads/:id/respond` (decline only), `GET|POST|… /api/internal/*`).
- `session` — via `bearer` (`Authorization: Bearer <token>`), `cookie`, or
  `ticket` (SSE only). A session swaps the loopback rule for same-origin
  (CSRF) + scope check (`admin` / `client` scopes).

**Pairing — how a phone gets a session [V]:**

- Pairing code: 12 symbols from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, shown as
  `7KQ2-M9XD-H4TP` (dashes/spaces/case-insensitive)
  (`packages/phone-protocol/src/pairing-code.ts`). Created in
  Settings → Phone & devices, or `openmausbot pair` / `pair --phone`.
  One-time use, windowed, lockout after too many wrong codes
  (`PairingErrorCode` in the same file: `pairing_bad_request`,
  `pairing_code_wrong`, `pairing_not_open`, `pairing_too_many`,
  `pairing_link_expired`, `pairing_qr_only`, `pairing_device_limit`, …).
- Pairing credential (QR): `omb_pair_` + 43 chars, high-entropy, never shown
  (`PAIRING_CREDENTIAL`, same file). Accepted wherever a code is.
- `POST /api/auth/pair` `{code, cookie?, label?, attemptId?, browser?}` →
  `{token, session, environment}`; with `cookie: true` it sets a session
  cookie instead (`server/index.ts:12554`). JSON content-type required (415
  otherwise).
- `POST /api/pair` `{credential|code, deviceName?, pairRequestId?}` → the
  companion (phone) apps' route: `{token, device: {id, name, createdAt,
  lastSeenAt}, serverName, e2e?}`. "Redeeming the credential IS the sign-in"
  (`server/index.ts:12589`). Through the relay only the QR credential pairs,
  never a typed code (`pairing_qr_only`).
- `GET /api/auth/pairing` → open pairings, `publicUrl`, relay status.
  `POST /api/auth/pairing` mints a new pairing (needs a live session).
- `GET /api/auth/session` → session info; `POST /api/auth/stream-ticket` →
  `{ticket}` for the SSE stream (loopback gets `{ticket: null}`);
  `POST /api/auth/logout`; `GET|DELETE /api/auth/sessions[/:id]`.
- Email sign-in also exists (`POST /api/auth/email/start|verify`) and
  account sign-in via the upstream's Clerk tenant (`server/account-signin.ts`)
  — for cloud/hosted; on a self-hosted box pairing is the path.
- **Sessions [V]:** `OMB_SESSION_TTL_DAYS` default **30**, sliding renewal at
  half-term (`server/sessions.ts:33-34,41`). Script tokens
  (`openmausbot token create`) never renew. Cookie name is
  `` `omb_session_${port}_${environmentId-alnum-first-12}` ``
  (`server/request-auth.ts:286`), `HttpOnly; SameSite=Lax; Path=/`.

**Public (no auth) [V]:** `GET /api/health` → `{app: "openmausbot"}`;
`GET /api/brand`; `GET /.well-known/openmausbot/environment` (environment
descriptor: id, label, desktopManaged, emailSignIn, sharedComputers, cloudHome,
cloudTeam, ownHome).

**Credential the app keeps [V]:** the session bearer token (or the session
cookie). Keep it per server; sessions are per-server, not portable.

## 2. Phone reachability — how a phone reaches the server at all

**[V]** (`docs/self-hosting.md:15-33,70`, `docs/phone-relay-server.md`,
`docs/phone-web-app.md`, `server/phone-relay.ts`):

| Way in | Command | Address the phone uses |
|---|---|---|
| LAN only | `serve --lan` | `http://192.168.1.20:8799/pair#code=…` (same Wi-Fi, plain http) |
| Tailscale | `serve --tailscale` | `https://<machine>.<tailnet>.ts.net/pair#code=…` |
| Own HTTPS | `--public-url https://maus.example.com` (or `--domain`, `--tunnel`) | that HTTPS address |
| **Relay** (phone only, from anywhere) | `login --relay` once, then `serve --relay` | QR in the MausBot app; no domain, open port, VPN or tunnel |

- **Relay [V]:** `serve --relay` — the server dials *out* to MausBot Relay,
  the phone dials the relay, and the relay splices sealed bytes it cannot
  open. End-to-end encrypted (Noise protocol, `companion/src/e2e/`). Relay
  nodes match `/^relay[1-9][0-9]?\.openmausbot\.com$/`
  (`companion/src/e2e/descriptor.ts`). Override via `OMB_PHONE_RELAY_URL`
  (must be `wss://host` or `ws://127.0.0.1`). Off unless asked for. Only for
  a machine with **no tunnel address**; cannot join `--tunnel`; `login --relay`
  signs the machine in with an emailed code (no tunnel reserved). Limits:
  max 20 phones, 4 live sessions per phone, relay carries 16 sessions per
  server (`server/phone-relay.ts`). The relay itself is also in this repo
  (`relay/src/`: WS routes `/v1/host`, `/v1/host/data`, `/v1/phone`,
  Ed25519-signed 12h tokens, `RELAY_TOKEN_KEYS` env) — self-hostable, but the
  default is MausBot-hosted.
- Pairing link format: `https://<host>/pair#code=XXXX-XXXX-XXXX` (or the relay
  QR). Phone scans it or pastes it in the MausBot app (＋ → Connect to a server).
- **Phone web app [V]** (`docs/phone-web-app.md`): the browser UI can be
  installed to the Home Screen and uses the same workspace/pairing; needs an
  HTTPS address (or same-Wi-Fi plain http). A phone cannot reach the server
  through a localhost link.
- Tailscale is built in: `createTailnetShare` (`server/index.ts:776`);
  `serve --tailscale` runs `tailscale serve --https=443 http://127.0.0.1:8799`.

**[U]** How the operator's phone reaches the-openmausbot-host's MausBot *today* (relay? public
URL? tunnel? Tailscale?) is not in source — it depends on the flags/env the
running server was started with. Must be checked on the box or asked.

## 3. List bots

- `GET /api/bots` → `{bots: WireBot[], botQueuedMessages, sections, groups,
  computerControl, together: {newBot: true}}`
  (`server/routes/threads.ts:103`). Query `?messages=N` includes the N latest
  messages per bot. `GET /api/bots/:id` → `{bot}` (same file).
- `POST /api/bots` creates a bot (`server/routes/bots-create.ts:103`).

**WireBot [V]** (`packages/phone-protocol/src/wire.ts:263` — "a bot as a client
may see it; avatarUrl is always present (null when the bot has none)"):
`id`, `threadId` (the task selected in the UI), `mainThreadId`,
`tasks?: WireTask[]` (newest first), `projects?`, `name`, `title`,
`description`, `soul?` (standing instructions — the persona body),
`soulHash?`, `soulDrift?`, `notifications: boolean`, `color: MausColor`,
`mascotExpression?`, `mascotBody?`, `mascotFaceColor?`,
`avatarUrl: string | null`, `avatarCrop?`, `avatarZoom?`, `avatarFocusX/Y?`,
`unread: boolean`, `modelSelection: ModelSelection`,
`computer?: Surface | "off"` (`Surface = "cloud" | "vm" | "local" |
"browser"`), `cloudBackend?: "box" | "vps"`, `autoStartVps?`, `vpsAlias?`,
`cwd?`, `autoApprove?`, `approvalMode?`, `alwaysAllow?: string[]`,
`speakReplies?`, `replyStyle?`, `voice?`, `liveVoice?`, … (fields continue
past the section read; see source for the full list).

**Model / engine [V]:** `ModelSelection = {instanceId: string, model: string,
effort?: EffortLevel, variant?: string}` (`wire.ts:56`). `instanceId`
identifies the engine instance; the engine list comes from
`GET /api/internal/models` → `ModelMenu {current: ModelSelection, engines:
MenuRow[], unavailable?}` where a row is `{instanceId, name, models,
more?, efforts?, unusable?, blocked?}` and `MenuEngine.driverKind` names the
driver (`server/routes/internal-models.ts:119`, `MenuEngine`). **[I]** The
engine is a CLI/driver (claude, codex, grok, mistral, …) — the bot's model is
the `model` id on that `instanceId`.

## 4. Chat

**Read history [V]** (`server/routes/threads.ts`):
- `GET /api/threads/:id/messages?limit=&before=&around=` →
  `{messages, hasMore, activeLeafId}`. `before`/`around` are message ids;
  they cannot be combined; an unknown cursor 404s (so a client can't
  paginate in a circle). Default page size via `DEFAULT_PAGE` in the same file.
- `GET /api/threads/:id/messages/:messageId/image` → the message's image bytes.
- `GET /api/search?q=&threadId=` → `{hits}` (with bot/group attribution).
- `GET /api/threads/:id/export?format=markdown|json`.

**Send [V]** (`server/routes/bot-messages.ts`):
- `POST /api/bots/:id/messages` (and `/guarded` variant) with body
  `{threadId, sendId (16–80 chars, at-most-once id), text | reaction,
  expectedActiveLeafId?, expectedApprovalMode?: "ask"|"full", replyToId?,
  onBehalfOf?: {email?, name?}}` → `202 {ok: true, message}`.
  (`guardedSendSchema`, same file.)
- `DELETE /api/bots/:id/queue/:queueId` cancels a queued message;
  `POST /api/bots/:id/queue/:queueId/steer` steers one into a running turn;
  `POST /api/bots/:id/active-branch` rewinds to a message
  (`{threadId, messageId}`).

**Live updates [V]:** `GET /api/events` — SSE/EventSource
(`server/index.ts:14581`). First frame is always
`{kind: "hello", cursor: "<stream id>:<seq>", resumed: bool}`; replay via
`Last-Event-ID` header or `?since=`; heartbeat comment frames; query params
`?screens=off`, `?frames=thread`, `?window=1`. Auth: session cookie/bearer,
or `?ticket=` from `POST /api/auth/stream-ticket` (a ticket does **not**
renew the session — `server/request-auth.ts:653-682`).
Frame kinds (`packages/phone-protocol/src/wire.ts:953-993`, `ServerFrame`):
`hello`, `companion.endpoints`, `sections`, `bot.queued`, `message`,
`message.patch`, `thread`, `bot`, `group`, `notify`, `notifications.read`,
`group.deleted`, `routine`, `routine.deleted`, `routine.run`, `webhook*`,
`app-trigger*`, `runtime`, `mcp.changed`, `cli-login`, `screen`
(`{botId, threadId, png, mime?, surface?: "browser"|"computer"}`),
`computer` (`provisioning|waking`), `computer-control`, `bot.deleted`,
`bots.order`, `groups.order`, `theme`, …

**Message shape [V]** (`WireMessage`, `wire.ts:439`; stored `Message =
WireMessage & {sentInTurn?}`, `server/store.ts:57`): `id`,
`role: "bot" | "user"`, `kind: "text" | "options" | "activity" | "screen" |
"connector" | "secret" | "signin" | "routine.run" | "goal.run" | "burst" |
"digest" | "compaction" | "call" | "widget" | "app"`, `text?`,
`attachments?` (image/file/audio entries with `/api/attachments` URLs;
`file` entries open through the message-scoped file route, never by path),
`card?: OptionCardData`, `widget?`, `app?`, `connector?`, `secret?`,
`signIn?`, `routineRun?`, `burst?`, `call?`, `tool?: WireTool` (activity
messages: tool name + outcome), `png?` (screen messages: base64 frame),
`mime?`, `at` (epoch ms), `parentId` (null = thread root), `replyToId?`,
`sendId?`, `turnId?`, `turnTerminal?`, `requestMessageId?`,
`requestPending?`, `requestCancelled?`, `sender?`, `via?: "api" | "call"`,
`from?` (group threads), … A bot's main thread is `bot.threadId`; `tasks[]`
are its other threads; group (room) threads work the same way.

## 5. Computer screen

- **Thumbnail frames [V]:** `GET /api/computers/frames?have=ID:VERSION,…` →
  `{configured, available, now, computers[]}` with base64 JPEG frames
  (480px wide, quality 60; a turn frame only goes to a caller with no frame
  yet — the event stream carries the rest). Admin-only
  (`server/routes/computer-frames.ts:108`).
- **Full live viewer [V]:** `GET /api/desktop-viewer/<target>[/websockify]`
  (`server/routes/desktop-viewer.ts`). Target is `local/shared`,
  `local/bot-<64 hex>`, `local/pool-<n>`, or `vps/<id>`. Without
  `/websockify` it returns `{password}` (the VNC password). With
  `/websockify` it requires a WebSocket upgrade and proxies it to
  `127.0.0.1:<port>/websockify` — i.e. **RFB/VNC over WebSocket**
  (noVNC-style). Admin scope **and same-origin only**; a native app is not a
  browser origin — see §8.
- **Take Control [V]:** query `?botId=&controlLeaseId=&threadId=` — the lease
  binds control and pauses every bot on that computer; pool targets are
  refused ("Phone control is not available in pool mode"). Without a lease
  the viewer is view-only; with one, input flows. `409 "Take control of this
  computer first"` when no lease is held (same file).
- Screen frames also arrive as `WireMessage` kind `"screen"` (`png` base64)
  in the thread and as SSE `screen` frames.
- Related: `GET /api/local-computer`, `/api/local-computer/instances`,
  `POST /api/local-computer/screenshot`, `GET /api/team-computers`,
  `/api/computers/boxes`, `/api/computers/vps` (`server/routes/computers.ts`).
- **Not this:** `server/routes/phone-screen.ts` (`/api/usb-phones`,
  `/api/usb-phones/:id/stream`) is scrcpy-over-adb viewing of a USB-plugged
  *physical phone* — unrelated to bot desktops **[V]**.

## 6. Approvals

- **How they surface [V]:** as messages in the thread — `WireMessage` kinds
  `options` (with `card: OptionCardData`), `secret` (`secret:
  SecretRequestCardData`), `signin` (`signIn: SignInRequestCardData`),
  plus `live-approval` types (`shared/live-approval.ts`,
  `packages/phone-protocol/src/live-approval.ts`). They also arrive over SSE
  as `message` frames.
- **Answer [V]:** `POST /api/bots/:id/respond`
  `{threadId, requestId, behavior: "allow" | "deny" | "answer",
  reviewedSha256?, rememberCommand?}` (`server/routes/bot-messages.ts`,
  `…/respond` handler). `POST /api/threads/:id/respond` is decline-only.
- **Safety rule [V]:** a bot's own shell must never approve its own card —
  loopback counts only from the desktop app, or from a browser while every
  bot is idle; otherwise admin scope is required (`ownerReview`, same file).
- **Credential/sign-in cards [V]:**
  `POST /api/bots/:id/secret-cards/:message/(saved|use|save|provide|provided|resume|dismiss)`,
  `POST /api/internal/request-credential` (a value is never echoed back,
  logged, or added to the card — `server/routes/secret-cards.ts:2-5`);
  `POST /api/bots/:id/sign-in-cards/:message/(done|cancel|save-login)`,
  `GET …/save-login?threadId=` (`server/routes/sign-in-cards.ts:1`).
- Per-bot approval config lives on `WireBot`: `approvalMode?` (canonical;
  missing resolves through `autoApprove`), `autoApprove?`, `alwaysAllow?`
  (`wire.ts:263`).
- Docs describe the phone as "chat and approvals only" (`docs/self-hosting.md`
  pairing section) — i.e. approvals are a first-class phone flow **[V]**.

## 7. Anything else a chat client needs

- **Push / notifications [V]:** server code exists and is **switched off by
  default**. `PUT /api/push/device {platform: "apns" | "fcm", token,
  environment?, pairing?, kinds?}` (also `GET`/`DELETE` on the same route;
  the phone is known by what authenticated the request, never the body)
  (`server/routes/phone-push.ts`, `PHONE_PUSH_ROUTE` in
  `shared/phone-push.ts`). The server registers the token with the control
  plane (`POST /v1/installations/self/push/register`). Env:
  `OMB_PHONE_PUSH`, `OMB_PUSH_CREDENTIAL`; max 50 devices; coalesce 10 s;
  max age 10 min; retries 5 s / 30 s / 120 s (`server/phone-push.ts`).
  `docs/phone-push.md`: "Today the MausBot phone app only gets a notification
  while it is connected… The code for every part is in place and switched
  off. Nothing changes until the owner supplies the keys and turns it on."
  In-app notifications are built from the live event stream (`notify`
  frames) — they arrive while the app is open, ~25 s after backgrounding on
  iPhone, or always on Android with Always-on. **So a third-party client
  cannot rely on push unless the server owner enables it and the app is
  built with FCM/APNs config [I].**
- **Presence [V]:** `POST /api/presence/active {source: "phone" |
  "desktop", timeZone?, language?, threadId?}` — the person opened/used the
  app with that conversation open; only the owner's counts
  (`server/routes/presence.ts`). No per-keystroke typing indicator was found
  in the routes **[I — likely absent]**.
- **Routines / schedules [V]:** `GET /api/routines`, `POST /api/routines`,
  `GET /api/routines/wake` (`server/index.ts:14469-14489`). Runs surface as
  `WireMessage` kind `routine.run` with a `routineRun` card, and as SSE
  `routine` / `routine.run` frames.
- **Phones management [V]:** `GET /api/phones`, `GET /api/phones/self`,
  `GET /api/phones/self/jobs` (`server/routes/phones.ts`).
  `POST /api/internal/phone/claim` (`server/routes/phone-gate.ts`) is an
  internal turn-resource claim, not a client API.
- **Notifications center [V, shallow]:** `server/routes/notifications.ts`,
  `server/mausbot-notifications.ts` (in-app notification log; `notify`
  frames carry entries). Not read in depth.
- **Bot API keys [V, note]:** there is a separate Bot API for external
  programs (`server/bot-api.ts`, `server/bot-api-fence.ts`) — key-based,
  per-thread, and deliberately fenced (e.g. a key cannot approve cards).
  It is a possible alternative client path for *sending* to a bot, but it is
  not the phone path and was not mapped in detail.

## 8. Open questions (not establishable from source alone)

1. **Version drift (0.1.161 vs 0.1.170).** This clone has no git tags, so no
   diff was possible. Any endpoint above may differ on the running box.
   Verify the key calls (`GET /api/bots`, `POST /api/bots/:id/messages`,
   `GET /api/events`, `POST /api/bots/:id/respond`) against the-openmausbot-host before
   building on them.
2. **How the operator's phone reaches the-openmausbot-host today.** Relay, public URL,
   tunnel, or Tailscale? Depends on the running server's flags/env — check
   the box (or ask a collaborator/the operator). This decides the unified app's connection
   path for the MausBot half.
3. **Relay for a third-party client.** The relay path is end-to-end
   encrypted (Noise, `companion/src/e2e/`); using it requires implementing
   the companion E2E protocol (transport key + relay token + sealed frames),
   not just HTTPS. Direct HTTPS (public URL / Tailscale / LAN) avoids all
   of that. If the-openmausbot-host is relay-only, the app needs the E2E client.
4. **Desktop viewer from a native app.** `desktop-viewer` requires admin
   scope **and same-origin**. How the official phone app satisfies (or
   bypasses) the same-origin check was not established — check whether the
   phone app uses a different viewer path or a same-origin WebView.
5. **Exact JSON shapes.** Only the shapes quoted above were read; several
   endpoints (bots-create body, routines body, notification entries, group
   create/update) were not read in full. Confirm each shape against the
   running server or the phone-protocol types before coding to it.
6. **Push on the-openmausbot-host.** Server code supports APNs/FCM, but whether it is
   switched on there (keys + `OMB_PHONE_PUSH` + app built with FCM config)
   is a deployment fact, not a source fact. Assume off until proven otherwise.
7. **Phone app internals.** The Android/iOS app source is not in this repo,
   so its exact use of these endpoints (headers, reconnect/backoff,
   background behaviour, token storage) is unverified. The wire protocol
   types in `packages/phone-protocol/src/` are the best available spec.
8. **SSE `screens`/`frames` defaults.** `GET /api/events` defaults were
   read only partially (`screens` on unless `?screens=off`,
   `?frames=thread` for per-thread frames); confirm against the live stream.
