# Rakazo server API — map for a third-party Android client

**Scope:** investigation only. No code was built, no server was contacted.
**Source:** local clone `~/workspace/vision-investigation/rakazo`, commit
`64dcd8a92c41634c253385a7151df562a74c4f7c` (checked 2026-11-18). Rakazo
v0.1.0 in this tree; the project calls itself beta.
**Goal:** what a unified Android app (Rakazo + OpenMausBot in one bot list)
needs to talk to a self-hosted Rakazo server (the Rakazo host instance).

Legend: **[V]** verified from source · **[I]** inferred from source, not
directly observed · **[U]** unknown from source alone.

---

## 0. Transport — one API for web, desktop and mobile

- **[V]** The whole product API is **oRPC** (typed RPC over HTTP), defined by
  the shared contract in `packages/contracts/src/rpc/app-contract.ts`
  (namespaces: `account`, `billing`, `spaces`, `server`/`deployment`,
  `computers`, `models`, `bots`, `threads`, `meetings`, `routines`,
  `artifacts`, `skills`, `integrations`, `runs`). Server implements it in
  `apps/api/src/router.ts` (route files under `apps/api/src/routes/`).
- **[V]** Regular call: `POST {base}/rpc/{procedure}` with
  `Content-Type: application/json`, body `{"json": <input>}`, response
  `{"json": <output>}`. Errors come back as
  `{"json": {"code", "status", "message"}}`.
  (`apps/mobile/lib/api/api.ts:670` `rpc()`, `:735` URL, `:739` headers;
  error-envelope comment at `:780`.)
- **[V]** Event streams (live updates) are the same POST with
  `Accept: text/event-stream`; frames are SSE `data: {"json": {...}}` lines,
  `[DONE]` terminator, `heartbeat` events for liveness; the client
  reconnects after silence. (`apps/mobile/lib/api/api-live.ts`,
  `subscribeRpcEvents`.)
- **[V]** Every request carries `Origin: rakazo://` and
  `Authorization: Bearer <session token>` when signed in.
  (`apps/mobile/lib/api/api.ts:172-175` `authHeaders()`, `:739-742`.)
- **[V]** The API process listens on **port 3100**. The published-images
  compose binds it loopback-only by default:
  `127.0.0.1:${RAKAZO_API_PORT:-3100}:3100`
  (`infra/compose/docker-compose.images.yml:547`). The **web service (port
  5173) proxies `/api`, `/rpc` and `/novnc` to the API**
  (`apps/web/server/api-proxy.ts:17-24`, `API_ROUTES`; target
  `API_PROXY_TARGET`, default `http://127.0.0.1:3100`,
  `infra/compose/docker-compose.images.yml:639`). A remote client should
  therefore use the server's **public origin** (the web origin) as its API
  base and hit `/rpc/*` through that proxy — not the loopback API port.
- **[V]** The Expo mobile app's default base is `http://127.0.0.1:3100`
  (`LOCAL_API`), overridable by `EXPO_PUBLIC_API_URL`
  (`apps/mobile/lib/api/server-config.ts:3-4`). A user-typed base is
  normalized to origin-only; plain `http:` is refused unless the host is a
  private-network host — public servers need `https://`
  (`apps/mobile/lib/api/endpoint.ts`, `normalizeApiBase`).
- **[V]** Server probe (how a client checks "is this a Rakazo server"):
  `POST {base}/rpc/health` with body `{"json":{}}`, expecting
  `{json:{ok:true, version}}`
  (`apps/mobile/lib/api/endpoint.ts:62-73` `probeApiBase`;
  `packages/contracts/src/account/account-rpc.ts:39` `health`).
- **[V]** The server can refuse outdated clients: `clientBuildMiddleware`
  enforces minimum client builds (`RAKAZO_MIN_CLIENT_BUILD`,
  `apps/api/src/app.ts:1170`, `apps/api/src/env.ts:164,353`); the mobile app
  names its build in headers on every request
  (`apps/mobile/lib/api/api.ts`, `configureClientBuild`). **[I]** A
  third-party client should send a plausible client-build header or risk
  being refused; the exact minimum values are deployment config, not source.
- **[V]** `GET /api/auth/capabilities` reports what sign-in methods the
  server offers (password, email code, passkey, SSO providers)
  (`apps/api/src/app.ts:1007`).

## 1. Auth

- **[V]** Auth is **better-auth** (Prisma adapter), with email/password,
  email-OTP code, passkey, social and OIDC SSO plugins
  (`packages/auth/src/index.ts:1-39,197,291` `createAuth`).
- **[V]** Native (non-browser) clients authenticate by **bearer token only**
  and keep no cookie
  (`apps/api/src/app.ts:984` comment; `:1136-1148` `requestSession`: "The
  bearer plugin turns a signed `Authorization: Bearer` into the session
  cookie").
- **[V]** Sign-in endpoints (better-auth, under `{base}/api/auth/`):
  - `POST /api/auth/sign-in/email` `{email, password}` → session token
  - `POST /api/auth/sign-up/email` `{email, password, name}`
  - `POST /api/auth/email-otp/send-verification-otp` `{email}`
  - `POST /api/auth/sign-in/email-otp` `{email, otp, name?}` (creates the
    account when none exists)
  (`apps/mobile/lib/auth/api-auth.ts:107-128`.) Requests use
  `credentials: "omit"`, `Content-Type: application/json`,
  `Origin: rakazo://`, `Accept-Language`, and a signup-surface header
  (`api-auth.ts:29-52` `postAuth`).
- **[V]** The token is extracted from the `set-auth-token` response header,
  else the `better-auth.session_token` cookie, else `body.token` /
  `body.session.token`
  (`apps/mobile/lib/api/session.ts:438-463` `tokenFromAuthResponse`).
  The mobile app stores it in **Expo SecureStore** under key
  `rakazo.session_token` (`session.ts:13`) and sends it as
  `Authorization: Bearer <token>`. Sign-up may require email verification
  (`api-auth.ts:74`).
- **[V]** Space scoping: every RPC call also sends an
  `X-Rakazo-Space-Id` header with the selected space
  (`apps/mobile/lib/api/api.ts:172-175`). The server resolves the actor from
  the bearer session + that header and requires space membership
  (`apps/api/src/app.ts:1136-1148`). **[I]** A client with no space header
  presumably gets a default/empty actor and most procedures fail; the exact
  behaviour for a missing header was not traced.
- **[V]** Sign-out: the mobile app records a pending sign-out (server base +
  token) and revokes it server-side; a token is also rotated on password
  change (`apps/mobile/lib/auth/api-auth.ts:268`, `apps/mobile/lib/api/api.ts:312-387`).

## 2. Spaces and bot roster

- **[V]** `spaces/list` → `SpaceNavigationSchema`: `{ current: { id, name,
  bots[], groups[], externalConversations[], botSections[] }, spaces[] }`
  (`packages/contracts/src/spaces/spaces-rpc.ts:25-26`;
  `packages/contracts/src/spaces/space.ts:111-121`). **This one call returns
  the whole roster for the current space** — the primary roster call for a
  unified app. `spaces/create|rename|remove`, `spaces/members/*`,
  `spaces/invites/*`, `organizations/*`, `groups/*` also exist.
- **[V]** `bots/list` → `BotSchema[]` (no input; scoped by the actor's
  space), `bots/listArchived`, `bots/get` `{botId}`
  (`packages/contracts/src/bots/bots-rpc.ts:55-57`;
  `apps/api/src/routes/bots.ts:340`).
- **[V]** `BotSchema` fields include: `id`, `spaceId`, `name`, `description`,
  `instructions`, `color`, `avatarSet`, `notifyOnFinish`, `pinned`,
  `unread`, `threadId`, `preview` (last-message preview), `status`,
  `threadStatus`, `computerMode`, `modelProvider`, `modelId`,
  `thinkingLevel`, `voiceId`, `autoSpeak`, `updatedAt`, `createdAt`
  (`packages/contracts/src/bots/bot.ts:31-71`).
- **[V]** Avatar: there is **no avatar image URL** in `BotSchema`. Avatars
  are derived client-side from `color` + `avatarSet`
  (`BotAvatarSetSchema = "organic" | "rakazo"`,
  `packages/contracts/src/bots/bot-avatar.ts:4-10`). **[I]** A third-party
  client must render its own avatar from those two fields.
- **[V]** `bots/setComputer` `{botId, mode}` switches a bot between the
  shared and its own computer. API values are **`"team"` and
  `"dedicated"`** (the UI calls the latter "Private")
  (`packages/contracts/src/computers/computer.ts:6`;
  `packages/contracts/src/bots/bots-rpc.ts:77`).
- **[V]** `bots/update`, `create`, `duplicate`, `archive/restore/remove`,
  `reorder`, `templates`, `createFromTemplate`, `emailAddress(es)`,
  `botSections/*` exist (`bots-rpc.ts:58-112`).

## 3. Chat — threads, messages, sending, live updates

- **[V]** A chat is addressed by `threadTarget`: **exactly one of**
  `{botId}` or `{groupId}`
  (`packages/contracts/src/threads/thread-send.ts:9-22`). Bot 1:1 chats
  and group chats use the same procedures.
- **[V]** `threads/head` `{botId|groupId}` → `{threadId, cursor}`
  (`packages/contracts/src/threads/threads-rpc.ts:29-34`). The `cursor` is
  the sequence number to subscribe from.
- **[V]** `threads/get` → `ThreadSnapshotSchema`: `{threadId, cursor,
  messages[], olderCursor, botId?, groupId?, groupName?, members?,
  run: RunSchema|null, activeRuns?, computer?: ComputerStatusSchema}`
  (`packages/contracts/src/threads/thread.ts:44-54`).
- **[V]** `threads/messages` `{botId|groupId, before?, includePeerRuns?,
  includePeerReceipts?, around?}` → `{threadId, messages[],
  olderCursor, coveredThroughSeq?}`. Pagination is by **sequence number**:
  pass `before` (a seq) to page older; `olderCursor` is null at the top
  (`threads-rpc.ts:36-50`; `thread.ts:36-42`).
- **[V]** Message shape (`ThreadMessageSchema`,
  `packages/contracts/src/threads/thread-message.ts`): `{id, threadId, seq,
  role, blocks[], botId?, author?, replyToMessageId?, replyQuote?,
  replyPreview?, runId?, callId?, createdAt}`. `role` is a `MessageRole`;
  content lives in typed **`blocks`**, not a plain text field.
- **[V]** Block kinds (`packages/contracts/src/threads/message-blocks.ts`):
  `text`, `card`, `ask`, `choice`, `app_connect`, `connect`, `computer`,
  `voice_call`, `call_request`, `meta`, `delivery`, `progress`, `steps`,
  `subagent`, `child_bot`, `cloud_agent`, `skill_draft`, `mcp_approval`,
  `import_setup`, `image`, `file`, `app`, `tool_question`, `page`,
  `handoff`, `channel_message`, `bot_message_sent`, `bot_message_received`,
  `chart`. A chat client must render at minimum `text`, `image`, `file`,
  `progress`, `ask`, `choice`, `tool_question`, `mcp_approval`, `card`.
- **[V]** `threads/send` input (`thread-send.ts`): threadTarget plus
  `text` (max 100,000 chars), `artifactIds` (attachments, uploaded first),
  `mentions` (bot/group/routine/connector chips), `replyToMessageId`,
  `replyQuote` (max 2,000 chars), `imageHandling: "confirm"|"file"`,
  `clientNonce` (max 200 chars, idempotency). Requires text or at least
  one attachment. Output: `{taskId, runId, seq, runIds?}`.
  `threads/sendWithRoutines` additionally test-runs mentioned routines.
- **[V]** `threads/subscribe` `{botId|groupId, cursor}` → SSE event
  iterator of `ProductEvent` (`threads-rpc.ts:51-53`). Server handler is an
  async generator with heartbeats
  (`apps/api/src/routes/threads.ts:191-211`). Event types
  (`packages/contracts/src/threads/events.ts:3-48`): `thread.message.created`,
  `thread.message.updated`, `thread.cleared`, `thread.message.reaction`,
  `thread.progress`, `thread.artifact`, `thread.ask`, `thread.choice`,
  `thread.meta`, `thread.computer`, `thread.subagent`, `run.started`,
  `run.waiting_input`, `run.completed`, `run.failed`, `run.cancelled`,
  `computer.status`, `computer.takeover.requested/granted/released`,
  `computer.command`, `memory.revised`, `routine.*`, `heartbeat` (liveness
  only, seq 0, never persisted). Each event: `{id, spaceId, threadId,
  botId, seq, type, runId?, createdAt, payload}`.
- **[V]** Other thread procedures: `react` (message reactions, with
  `clientNonce`), `stop` / `stopHelper`, `followUp`, `clear`, `markRead`
  (returns `unreadAfterSeq`), `markUnread`, `snooze`, `search/query`,
  `links/favicon`, `answerCall` / `declineCall` / `endCall` (voice calls).
- **[V]** Clients never poll: read once, then re-read when the server
  signals a change over the live stream (`notifications.subscribe`); also
  re-read on window focus / app foreground (repo `AGENTS.md`, realtime
  rule; `apps/mobile/lib/api/api-live.ts`, `subscribeRosterSignals`).
- **[V]** `runs/list` `{filter: "active"|"recent"}` lists runs
  (`packages/contracts/src/runs/runs-rpc.ts:6-7`) — the "what are my bots
  doing right now" call.

## 4. Computer screen and Take Control

- **[V]** `computer/status` `{botId}` → `ComputerStatusSchema`
  (`packages/contracts/src/computers/computers-rpc.ts:27`): `{botId, mode,
  kind, state: "stopped"|"booting"|"running"|"suspended"|"error",
  controlHolder: "bot"|"user"|"none", controlBotId, takeoverRequested,
  screenAvailable, screenWidth, screenHeight, homeRevision, busyBotName,
  canUpdate, terminalAvailable, ...}`
  (`packages/contracts/src/computers/computer.ts:32-58`).
- **[V]** `computer/boot|stop|recover|reset|update`, `computer/updates`,
  `computer/dismissUpdate`, `computer/dismissDiskWarning`,
  `computer/heartbeat`, `computer/terminalUrl` (`{url|null}`)
  (`computers-rpc.ts:28-82`).
- **[V]** `computer/screenUrl` `{botId, preview?}` →
  `{url: string|null, singleUseSignIn?: {screen}}`
  (`computers-rpc.ts:86-88`;
  `computer.ts:60-70`). The URL is a **noVNC page**:
  `/novnc/session/{view|control}/{expiresAt}.{token}...`
  (`packages/core/src/node/screen-capability.ts:119,184`). The sign-in code
  is **single-use**: loading it a second time, or loading a newer one
  beside it, loses the screen (`computer.ts:63-69`). **[I]** A third-party
  Android client can most practically load this URL in a WebView (it is a
  plain web page behind the same origin; the web server proxies `/novnc` to
  the API). Implementing a native VNC client instead would be a separate
  large job. **[U]** The exact WebSocket path the noVNC client opens, and
  whether the page works from a foreign (non-Rakazo) WebView, were not
  verified from source alone.
- **[V]** `computer/preview` `{botId, runId?}` →
  `{image: string|null}` — a still of the screen as an image data URL
  (`computers-rpc.ts:89-98`). This is the cheap v1 alternative to live
  screen viewing.
- **[V]** Take Control: `computer/takeover` `{botId}` →
  `{leaseId, expiresAt}`; `computer/release`
  `{botId, reason?, runId?, leaseId?}`; `computer/input` `{botId, kind,
  payload}` where kind is `key|pointer|clipboard|scroll` with payloads:
  key `{key, sensitive?}`, pointer `{x, y, button: left|right, type:
  move|down|up|click}`, clipboard `{text, sensitive?}`, scroll
  `{direction, amount, ...point}` (`computers-rpc.ts:39-60`;
  `apps/api/src/routes/computer.ts:380-440`). Input requires holding
  control; otherwise `FORBIDDEN`. Releasing also ends a teaching recording
  made under that control (`routes/computer.ts:343-355`).
- **[V]** Files on the computer: `computer/files`, `readFile`,
  `downloadFile`, `uploadFile` (`computers-rpc.ts:61-80`).
- **[V]** Screen-proxy internals (server-side, not for clients):
  `POST /api/internal/screen-target` requires
  `Authorization: Bearer <screen proxy secret>` (a server secret, not a
  user credential) and a sealed capability naming bot + computer
  (`apps/api/src/computers/screen-proxy.ts`). A client never calls this
  directly; it uses the `screenUrl` from the RPC.

## 5. Approvals (approval cards)

- **[V]** Modes (docs/approvals.md): **Ask** (ask before any action that
  changes something, costs money, sends something out, or reads an unnamed
  site), **Auto** (an approval model checks actions; needs an Approval
  model in Settings → Models), **Never ask** (only Block rules and the
  spending limit stop an action). Cards never block the chat; an approval
  card from an unwatched run (routine, webhook, another bot) **expires
  after 30 minutes**.
- **[V]** Cards arrive as message blocks: `ask` (question / instruction
  change / settings change / payment receipt / login / secret / one-time
  code; `status: "pending"|"answered"`, `answer`, `actions: [{id, label}]`,
  `approvalEffectId?`, `expired?`, `dismissed?`) and `tool_question`
  (an MCP tool's question while its call waits; `status:
  "pending"|"accepted"|"declined"|"expired"`, `expiresAt`, sealed values)
  and `mcp_approval` (MCP server OAuth; `status:
  "pending"|"connected"|"dismissed"`)
  (`message-blocks.ts:86-144` `ask`, `:145-153` `choice`, `:279-286`
  `mcp_approval`, `:329-339` `tool_question`).
- **[V]** Answering: `threads/answer`
  `{botId|groupId, runId, messageId, answer, clientNonce?, fields?,
  username?, remember?: false, dismiss?: true}`. For approval cards the
  `answer` string is the action id: **`"allow"`** (Allow once),
  **`"always"`** (Always allow), **`"deny"`** (Deny)
  (`apps/web/src/components/messages/AskCard.tsx:89-91,113-115`;
  `apps/mobile/lib/thread/thread-answer.ts`, `answerThreadMessage`).
  Login cards send `fields[]` with empty `answer`; `dismiss: true`
  dismisses a secret request; `remember: false` uses a login for one run
  only (`threads-rpc.ts`, `answer`).
- **[V]** `threads/answerToolQuestion` `{botId|groupId, messageId, action:
  "accept"|"decline", values?}` answers a tool's waiting question card
  (`threads-rpc.ts`, `answerToolQuestion`).
- **[V]** Allow/Block rules live in Settings → Approvals: `integrations`
  approve procedure (`packages/contracts/src/integrations/integrations-rpc.ts:149`)
  and rule effects `always_allow | require_approval | deny`
  (`packages/contracts/src/integrations/approvals.ts:6`). "Always allow"
  choices are saved per rule; a Block is refused without a card.
- **[U]** The exact `runId`→card correlation for a card arriving over the
  live stream (which event carries a new `ask` block) was not traced
  end-to-end; in practice the client re-reads the thread on
  `thread.ask` / `run.waiting_input` events.

## 6. Push, live notifications, presence, routines, misc

- **[V]** Push is **Expo push tokens**. Mobile: `Notifications.getExpoPushTokenAsync({projectId})`
  (needs an EAS project id; Android builds without a Firebase config cannot
  mint one) → `notifications/registerPush` `{token, presentation?}`
  (`"android_sender"` when the native module supports sender avatars);
  `notifications/unregisterPush`; calls use
  `notifications/registerCallPush` `{deviceId (uuid), platform: "ios"|
  "android", expoToken?, voipToken?}` (voipToken is iOS-only per the
  contract refine) (`apps/mobile/lib/notifications/push.ts:20-108`;
  `packages/contracts/src/threads/threads-rpc.ts:224-253`).
  **[I]** A third-party Android app would need its own Expo/Firebase
  project to mint Expo tokens, or its own push path; it cannot reuse
  Rakazo's Expo project id.
- **[V]** Live notifications (in-app, no push needed):
  `notifications/subscribe` → SSE stream of `{type, alert?}` where type is
  `alert | roster | <SpaceChangeKind> | organizations | heartbeat`; the
  doc comment states each kind is sent once when the stream opens, then on
  change, and tells the client which list to re-read (`threads-rpc.ts`,
  `notifications.subscribe`). `notifications/subscribeCalls` streams
  incoming-call lists. `notifications/followWorkActivity` /
  `registerWorkActivityStarts` drive the phone's live status card of bots
  at work (`threads-rpc.ts:259-285`).
- **[V]** `ChatAlertSchema` (`packages/contracts/src/threads/thread.ts`):
  `{kind: "completion"|"failure"|"help"|"takeover", title, body, spaceId,
  botId, threadId, groupId?}` — run alerts already filtered by the chat's
  mute settings; these are what push notifications carry.
- **[V]** Presence/typing: **no typing-indicator or presence procedure was
  found in the contracts.** "Is a bot working" is derived from
  `runs/list`, `threads/get` (`run`, `activeRuns`), and live events
  (`run.started/completed`, `thread.meta` with activity `{thinking:
  boolean}`, `computer.status`). **[I]** A unified app should show run
  state, not a typing bubble, unless it invents its own.
- **[V]** Routines: `routines/list` `{botId}`, `listForMentions`,
  `history` `{routineId, before?, limit?}`, `create`, `update`, `remove`,
  `testRun` (`packages/contracts/src/routines/routines-rpc.ts:21-92`).
- **[V]** `models/*`, `artifacts/*`, `skills/*`, `integrations/*`,
  `meetings/*`, `billing/*`, `account/*` (`me`, `preferences`,
  `health`, `onboarding`, `export`, `security`, `audit`) exist in the
  contract but were not mapped in detail here. Artifacts (file uploads
  backing `threadSendInput.artifactIds`) are in `artifactsRpc`; the upload
  flow was not traced — see open questions.
- **[V]** The mobile app uses the **same API as the web app**: both go
  through the shared `@rakazo/contracts` oRPC contract
  (`apps/mobile/lib/api/api.ts:670` `rpc()`; `apps/web/src/lib/query/rpc.ts`).
  A third-party client speaking this contract is speaking the same API the
  first-party apps speak. The only known client-side gate is the minimum
  client-build enforcement (§0).

## 7. What a unified-app client must implement (Rakazo side)

1. Server probe: `POST {origin}/rpc/health` (§0).
2. Sign-in: `POST {origin}/api/auth/sign-in/email` (or email-OTP), keep the
   bearer token in secure storage, send `Authorization: Bearer` +
   `Origin: rakazo://` on every call (§1).
3. `spaces/list` → current space + roster (bots, groups); remember
   `X-Rakazo-Space-Id` for all calls (§1–2).
4. `threads/head` + `threads/get` / `threads/messages` for history;
   `threads/send` to send; `threads/subscribe` (SSE) for live events;
   re-read on `notifications/subscribe` signals; never poll (§3, §6).
5. `computer/status` + `computer/screenUrl` (WebView) or `computer/preview`
   (still image) for the live screen; `takeover`/`input`/`release` for
   Take Control (§4).
6. Render `ask` / `choice` / `tool_question` / `mcp_approval` blocks and
   answer via `threads/answer` / `threads/answerToolQuestion` (§5).
7. Push (optional, needs own Expo/Firebase project):
   `notifications/registerPush` + `registerCallPush`; in-app alerts come
   over `notifications/subscribe` (§6).

## 8. Open questions (not established from source alone)

- **[U]** Exact production base URL for the the Rakazo host Rakazo instance (tunnel
  host) and whether its web origin proxies `/rpc` identically to the
  compose default. Confirm from the deployment, not source.
- **[U]** Whether the deployed server's version matches this commit
  (2026-11-18 snapshot). `health` returns a `version` string; compare at
  runtime. If the server is newer, procedures may have been added/changed.
- **[U]** Artifact upload flow backing `threadSendInput.artifactIds`
  (which procedure/endpoint uploads a file, size limits, mime handling).
  `artifactsRpc` exists but was not traced.
- **[U]** Exact noVNC WebSocket path and whether the `screenUrl` page works
  from a foreign WebView (different origin, no Rakazo cookies). Needs a
  runtime check against a live server.
- **[U]** Behaviour of RPC calls with a missing/invalid
  `X-Rakazo-Space-Id` header (default space? 401?). Not traced.
- **[U]** Exact `RunSchema` fields (run lifecycle object in snapshots and
  `runs/list`) — not mapped in detail.
- **[U]** Whether `bots/list` ordering/pinning matches the mobile roster
  order, and whether `spaces/list` alone is sufficient for the roster or
  the app also needs `bots/list` + `groups/list` for edge cases.
- **[U]** `threads/send` `imageHandling: "confirm"` vs `"file"` semantics
  in the client (confirmation-aware image sending) — only partially
  understood from the contract.
- **[U]** Minimum client-build values (`RAKAZO_MIN_CLIENT_BUILD`) on the
  deployed server — whether a third-party client build string is accepted.

## Appendix — procedure index (contract paths)

From `packages/contracts/src/rpc/app-contract.ts` and the per-area
`*-rpc.ts` files (paths are the URL suffix under `/rpc/`):

- `health`, `me`, `preferences/update`, `preferences/adoptTimezone`,
  `aiConsent/status|allow|revoke`, `onboarding/*`, `export/*`,
  `security/events`, `audit/events`, `analytics/*`
- `spaces/list|create|rename|remove`, `spaces/members/*`,
  `spaces/invites/*`, `organizations/*`, `groups/*`
- `bots/list|listArchived|get|create|update|duplicate|reorder|archive|
  restore|remove|removePreview`, `bots/setComputer`, `bots/templates`,
  `bots/createFromTemplate`, `bots/emailAddress(es)`,
  `bots/claimEmailAddress`, `bots/rotateWebhookSecret`,
  `bots/revealWebhookSecret`, `bots/restoreInstructions`, `botSections/*`
- `threads/head|get|messages|subscribe|send|sendWithRoutines|react|stop|
  stopHelper|followUp|endCall|answerCall|declineCall|clear|answer|
  answerToolQuestion|markRead|markUnread|snooze`
- `notifications/subscribe|subscribeCalls|declineIncomingCall|
  registerCallPush|registerPush|unregisterPush|followWorkActivity|
  registerWorkActivityStarts`
- `search/query`, `links/favicon`, `externalConversations/updatePolicy`
- `computer/status|boot|stop|recover|reset|update|updates|
  releaseInterrupted|dismissUpdate|dismissDiskWarning|takeover|release|
  input|files|readFile|downloadFile|uploadFile|terminalUrl|screenUrl|
  preview|heartbeat`, `computerProvider/get|update`,
  `computerProvider/account/get|update`
- `routines/list|listForMentions|history|create|update|remove|testRun`
- `runs/list`
- `deployment/get|update`, `updater/status|check|apply`
- `models/*`, `artifacts/*`, `skills/*`, `integrations/*`, `meetings/*`,
  `meetingCalendar/*`, `billing/*` (not mapped in detail here)
