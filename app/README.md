# WLTW Bots — unified bot app (v1: chat only)

One Android app showing both of the operator's bot servers in one list: Rakazo
(the Rakazo host) and OpenMausBot (the-openmausbot-host). Tap a bot to chat with it. Bots
stay on their own servers and cannot talk across them.

Package: `com.welivethisway.bots` — **WLTW Bots**, v1.0 (versionCode 1).
Debug APK: `../WeLiveThisWay-Bots.apk` (1.0 MB, signed with the debug key).

## v1 scope — what it does

- One bot list with the bots from both servers — no server badges, just bots. Chat and Settings both have a back arrow in the top bar (the phone's back button works too).
- Tap a bot → chat: read history, send messages, live updates over the
  server's event stream (Rakazo `threads/subscribe`, MausBot `/api/events`).
  The stream re-fetches the thread when something changes; it never polls.
- **Images in chat:** tap **+** next to the message box to pick an image,
  add a caption if you like, and Send. Images the bot sends (or you sent)
  show inline in the thread — tap one to view it full-screen. Rakazo
  uploads via `artifacts/create` then `threads/send` with `artifactIds` +
  `imageHandling: "confirm"`; MausBot uploads the raw bytes to
  `POST /api/attachments` then sends with a standalone `<attached-image>`
  tag line. Photos are resized to 1600px max edge and saved JPEG q0.7
  (PNG stays PNG, GIF/WebP kept as-is); 10 MB per image. Bot screen frames
  (MausBot `screen` messages) render the same way. Details in
  `../images-investigation.md`.
- Settings screen: each server's address is typed in by hand. Nothing is
  hardcoded — the app only talks to the addresses the operator enters.
- Rakazo sign-in (email + password) and MausBot pairing (12-char code or
  `omb_pair_` credential). Tokens are AES-GCM encrypted with a key in the
  Android Keystore (`SecureStore.kt`); the password is used once and never
  stored. No credentials, keys, or tokens anywhere in the code.

## What v1 does NOT do (v2/v3)

- No screen viewing, no Take Control, no approval cards (a card shows as
  "[needs approval — answer in the Rakazo/MausBot app for now]").
- Non-image file message blocks show as "[file: name]" placeholders.
- MausBot chats use the bot's main thread only (no thread switcher yet).
- No push notifications.

## v2 — Bot Hub section (added 2026-11-19)

A **Hub** button on the main screen opens the Bot Hub (rooms, @groups,
DMs, admin tools). The hub API is poll-based — the app re-reads on
open, on resume and on Refresh; there is no live stream.

- **Sign-in:** Settings has a Hub entry — hub address + member key
  (the key is stored encrypted like the other tokens, never shown
  back). Verify checks it with `GET /api/me`.
- **Rooms:** list via `GET /api/rooms`, read via
  `GET /api/rooms/<room>/messages`, post via
  `POST /api/rooms/<room>/messages` (tap a message to reply to it).
  Messages that @mention you are marked. The **@** button opens a
  picker of @groups (`GET /api/groups`) and members — the server
  expands @group tags on post, the app just sends the text.
- **DMs:** list via `GET /api/dms`, open/send via `POST /api/dms`.
  New DM picks a member and opens the thread.
- **Admin tools** (Hub → Admin, the operator is the hub owner): **Invite**
  creates a visitor invite (`POST /api/admin/invites`) and shows the
  ready-to-share text with Share (Android share sheet) and Copy.
  **Join requests** lists pending requests (`GET /api/admin/requests`)
  with Approve as visitor / Approve as member / Deny — after an
  approve the requester picks up their own key with their claim code,
  so there is nothing to send them. **Add a bot** (`POST
  /api/admin/members`) shows the new key ONCE with a copy button and
  a warning it won't be shown again. **Rotate a key** (`POST
  /api/admin/members/<id>/rotate`) shows the fresh key once the same
  way. A non-admin key gets a plain message instead of the tools, and
  a 403 from any admin call is handled the same way.
- A bot that isn't on the hub is invisible there (@mentions of it do
  nothing) — it still works in the main bot list via its own server.
  Add it from Admin → Add a bot to bring it onto the hub.
- Hub details were verified against the local Bot Hub source (v0.11.0,
  `~/workspace/bot-hub`, `src/server.js` + `src/hub.js`).

## v2 polish (added 2026-11-19)

- **Home view:** the app still opens on the bot list — the general
  view, all bots. Hub rooms are one tap away (Hub button), never on
  the home screen.
- **Hub screen tidy-up:** public rooms and DMs list first. Private
  rooms sit collapsed under a "Private rooms (N)" tap-to-expand row,
  and each private room carries a "Private" label (the API returns
  `private` per room; DMs are not labelled — they are private by
  nature and live in their own section).
- **Broadcast:** a **Broadcast** button at the bottom of the Hub
  screen. Pick a target — `@everyone` (built in) or any @group from
  `GET /api/groups` — type once, send; the app posts
  "@group <text>" to #general and the hub expands the tag and wakes
  each member once. Needs the owner's key (or a group-wake member);
  other keys get a plain message.
- **Groups (Hub → Admin):** create a group (`POST
  /api/admin/groups`), list groups with members, tap one to add or
  remove members (`POST /api/admin/groups/<name>/members`) or delete
  it. Non-admin keys get the plain message, as with the other tools.
- **Listening (Hub → Admin → Listening):** pick a room, then flip
  each bot between Always listen (wakes on every post, no @mention
  needed) and Only when @mentioned (`POST
  /api/admin/rooms/<room>/listeners {member, on}`). The toggles read
  the room's true `listeners` list from `GET /api/rooms`. The hub
  only lets a bot listen if it is a bot with wake-up on and an
  OpenMausBot link, in a members-only room (and on the roster for
  private rooms) — when a bot can't listen the screen says why in
  plain words and offers the fix where the app can do it: turn
  wake-up on (`PATCH /api/admin/members/<id> {wake}`) or add it to
  the room's roster (`POST /api/admin/rooms/<room>/roster`). A
  visitor-open room shows no toggles, just the reason. Non-admin
  keys get the plain message, as with the other tools.
- **App art:** the main screen header is the operator's Miami art-deco
  skyline photo (`res/drawable/wltw_banner.jpg`) with the WE LIVE
  THIS WAY logo (`res/drawable/wltw_logo.jpg`) in a navy strip below
  it. The launcher icon is the same logo on navy, generated square
  from the 1460x720 file (standard PNG icons in all densities — no
  adaptive-icon XML, so it renders the same on every launcher).
  Sources: `~/workspace/user/files/1000049508_1_h5qa.png` (skyline),
  `~/workspace/user/files/1000049509_3_o384.png` (big logo).
- **Hub key hint:** Settings → Bot Hub, under the member-key field:
  the owner's key lives only in the hub's `data/admin.key` file on
  the Rakazo host (keys are stored hashed and can't be looked up);
  `hubctl login-link` on the Rakazo host prints a one-tap sign-in link, and
  `hubctl rotate <name>` (or Rotate in Manage / this app's Admin)
  makes a fresh key, shown once.

## v2.1 — Bot details, DM Hide, cross-server broadcast (added 2026-11-19)

- **Bot list details:** each bot row reads
  `Name - Server type - Model - Server name`, e.g.
  `a collaborator - Mausbot - DeepSeek Flash 4.1 1017 - the-rakazo-host` — one line,
  as small detail text (not the big badges the operator had removed). Every
  value comes live from the server: server type Rakazo/Mausbot (known
  per bot), model from Rakazo `modelId` / MausBot
  `modelSelection.model` (shown as reported — the operator's example said
  1018, the real model is 1017), server name the-rakazo-host/the-openmausbot-host (as
  in the Settings labels). A dot at the end of the row is green while
  the bot has an active run, grey when idle: Rakazo reads it from the
  thread snapshot (`threads/get`, `run != null` — checked after the
  list shows, so a slow server never holds up the list); MausBot
  reads it from the roster's task `busy` / `activity == "working"`
  (other activity values — `waiting-on-you`, `no-signal`, `idle` —
  count as idle).
- **DM Hide (local only):** long-press a DM in the Hub list → Hide.
  The DM disappears from this phone's list; it is stored in
  SharedPreferences as room name + the newest hub message id at hide
  time, and it comes back on its own when a new message arrives
  (lastId past the hide). A "Hidden DMs (N)" row at the bottom of
  the list shows the hidden ones — tap one to bring it back. The hub
  itself keeps the DM: a DM can't be deleted, even by the owner
  (`removeRoom` refuses DMs outright), so local hide is the only
  option.
- **Cross-server Broadcast:** `@everyone` now transcends both
  servers. It still posts `@everyone <text>` to #general on the hub,
  and ALSO sends the raw text as a direct message to every bot on
  both servers (Rakazo `threads/send` to each bot's main thread;
  MausBot `POST /api/bots/:id/messages`). Before sending, a
  confirmation shows the count (N on the Rakazo host, M on the-openmausbot-host) so
  the operator doesn't spam by accident; a server that isn't signed in or
  can't be reached contributes no DMs and says so instead of failing
  the whole broadcast. `@sales` / `@coders` stay hub-only — the
  composer says so plainly ("reaches this group's hub members...
  bots not on the hub won't see it"), because non-hub bots aren't in
  hub groups. The hub post itself reaches hub members on both
  servers (each wakes through its own path, whichever box it runs
  on).
- **Hub-down @everyone (added 2026-11-20):** the broadcast no
  longer dies with the hub. If the hub can't be reached, the target
  picker still offers `@everyone` — DM-only, labelled as such, with
  no group targets (they need the hub). The group-wake check
  (`GET /api/me`) can't run when the hub is down, so it doesn't
  block DM-only @everyone; it is re-checked fresh when the hub does
  answer. The hub post is attempted first and its outcome recorded,
  but the DMs always go out afterwards regardless — the final toast
  reports both parts, e.g. "Hub post skipped (hub unreachable) and
  DMs sent to 7 bots."
- **Server names from Settings (added 2026-11-20):** the server
  name in the bot detail line and the @everyone confirm dialog now
  comes from the base URL in Settings (the host decides — a the-rakazo-host
  address is "the-rakazo-host", an otto address is "the-openmausbot-host"), instead of
  assuming every MausBot lives on the-openmausbot-host. the operator's MausBot is on
  the Rakazo host, so its bots now say the Rakazo host in both places.

## First run — what the operator enters

1. Install the APK, open it, tap **Settings**.
2. **Rakazo (the Rakazo host)** — base URL is the Cloudflare tunnel address
   (plain `https://…`). Do NOT use Tailscale for the Rakazo host — the operator's gig
   apps flag Tailscale as fraud. Then **Sign in** with the Rakazo email
   and password. Confirmed address (2026-11-19):
   `https://rakazo.example.com` — `POST /rpc/health`
   answers `{"json":{"ok":true,"version":"0.1.0"}}`. (The older
   `https://rakazo.welivethisway.com` resolves but returns an empty reply
   — don't use it.)
3. **MausBot (the-openmausbot-host)** — base URL is the Tailscale hostname
   (`https://<machine>.<tailnet>.ts.net`) or the direct `https://…`
   address, whichever the phone reaches. Then **Pair** with a code from
   the server (Settings → Phone & devices, or `openmausbot pair`).
   The MausBot Relay is NOT supported in v1 — the server must be
   reachable directly (Tailscale, LAN, or a public/direct URL).
4. Back on the list, tap **Refresh**. Bots appear in one list.
   A server that cannot be reached shows as "unreachable" instead of
   crashing the list; a session that expired says so and asks to sign
   in / pair again.
5. **Hub** — in Settings, enter the hub address (e.g.
   `https://hub.welivethisway.com`) and your member key, tap **Verify**,
   then **Open Hub**. Tap **Hub** on the main screen any time after
   that. Admin tools live under Hub → Admin (owner's key only).

## How it was built

- Native Android, Kotlin, no third-party libraries. Network is
  `HttpURLConnection`, JSON is `org.json`, storage is the Android
  Keystore. `minSdk 24`, `targetSdk 34`, `compileSdk 34`.
- API details were verified against the local source clones before
  coding: Rakazo oRPC (`POST {base}/rpc/{procedure}`, body
  `{"json": …}`, headers `Authorization: Bearer`,
  `Origin: rakazo://`, `x-rakazo-space-id`,
  `x-rakazo-client-build: mobile/<build id>`); MausBot REST
  (`GET /api/bots`, `GET /api/threads/:id/messages`,
  `POST /api/bots/:id/messages` with a 16–80 char `sendId`,
  `POST /api/pair`). See `../api-rakazo.md` and
  `../api-openmausbot.md` for the full maps.
- The client build id sent to Rakazo is in
  `api/BuildInfo.kt` (`mobile/20261119190000`) — bump it when
  releasing. If the server ever answers HTTP 426, the app says the
  app version is too old.

### Build

In this VM the Gradle daemon cannot run (loopback TCP is intercepted
by the runtime sandbox), so the real build is the manual script, the
same pattern as the Links and Drive apps:

```sh
./build-apk-manual.sh   # -> ../WeLiveThisWay-Bots.apk
```

It runs `aapt2 -> javac (R.java) -> kotlinc -> d8 -> zipalign ->
apksigner`, all offline. Kotlin comes from the compiler embedded in
the Gradle 8.5 distribution already in `~/workspace/tools`
(no standalone `kotlinc` is installed). The Gradle files
(`build.gradle`, `settings.gradle`, `app/build.gradle`) are the
canonical project — on a normal machine `gradle assembleDebug`
builds the same app.

## Untested against live servers

No test credentials exist and no live server was contacted during the
build. The code paths were checked against the server source, and the
APK was verified (signature v2/v3, all eight activities, all classes in
the dex) — but the first real sign-in, roster load, and chat against
the Rakazo host and the-openmausbot-host, and the first hub sign-in, room read/post and
admin call, are still to be done by the operator with his own keys. If a call fails,
the app shows the server's own error message on screen; likely
first-run issues are a wrong base URL, the MausBot version drift
(the operator runs 0.1.161, the map is 0.1.170), or the Rakazo minimum
client-build setting on the deployed server.
