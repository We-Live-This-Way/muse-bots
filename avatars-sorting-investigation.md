# Bot avatars + bot-list sorting — investigation

Research only. No app code written, no live server contacted.
Sources: local clones `~/workspace/vision-investigation/rakazo` (commit
`64dcd8a`) and `~/workspace/vision-investigation/openmausbot` (commit
`c444029`, v0.1.170), the existing maps `api-rakazo.md` /
`api-openmausbot.md` / `images-investigation.md`, and the app project
`~/workspace/unified-bot-app/app/` (read-only).

Legend: **[V]** verified from source (file:line cited) · **[I]** inferred
from source · **[U]** unknown / not established from source alone.

---

## 1. Rakazo — avatars

### Where the avatar lives

- **[V]** There is **no avatar image URL** in `BotSchema`. A bot's avatar
  is derived client-side from two fields: `color` (string) and
  `avatarSet` (`"organic" | "rakazo"`, default `"organic"`)
  (`packages/contracts/src/bots/bot.ts:42`;
  `packages/contracts/src/bots/bot-avatar.ts:4-10`).
- **[V]** `color` takes three forms
  (`packages/contracts/src/bots/bot-avatar.ts:88-133`,
  `parseBotAvatarValue` / `isBotAvatarValue`):
  - plain hex, e.g. `#FF781C` — the bot's one color (its avatar, name
    and bubble all start from it; the palette is the 11-entry
    `BOT_COLOR_LIST`, same file `:15-84`);
  - `hex::shape_N`, e.g. `#8B5CF6::shape_2` — a hex plus one of **8
    shipped mascot shapes** (`BOT_AVATAR_SHAPE_COUNT = 8`, same file);
  - `data:image/(png|jpeg|webp|gif);base64,…` — a custom picture, capped
    at 200,000 chars (`BOT_AVATAR_VALUE_MAX_LENGTH`), "256x256 webp
    data URLs from Avatar Studio stay well under this".
- **[V]** `spaces/list` returns **full `BotSchema` entries** in
  `current.bots`, so `color`, `avatarSet`, `updatedAt`, `preview`,
  `unread` and `pinned` all arrive in the one call the app already
  makes (`packages/contracts/src/spaces/space.ts:111-121`;
  `packages/contracts/src/bots/bot.ts:31-71`). No new call is needed
  for avatars. Auth is the bearer session token already used.

### How the official apps render it

- **[V]** The renderer is `BotAvatar`
  (`packages/ui-web/src/bot-avatar.tsx:89-170`; the mobile app has its
  own copy at `apps/mobile/components/bots/bot-avatar.tsx`, same
  inputs). Logic, in order:
  1. `data:image/…` → a round photo (`<img>`, `object-cover`), with a
     working ring drawn around it while the bot is running. Only
     `data:` URLs ever become an `<img>` — arbitrary http(s) values
     are deliberately never rendered (SSRF/tracking comment, same
     file `:54-56`).
  2. `hex::shape_N` → one of the 8 shipped SVG shape paths in that
     color, with bar eyes (`packages/core/src/avatars/
     bot-avatar-shapes.ts`: shape keys `hex, wedge, squircle, tablet,
     pebble, blob, teardrop, cloud`; viewBox `-15 -15 259 259`).
  3. Plain hex + `avatarSet: "rakazo"` → `LogoAvatar` — the animated
     Rakazo logo mark in the bot's color.
  4. Plain hex + `avatarSet: "organic"` (or missing) →
     `OrganicAvatar` — a seeded organic blob path derived from the
     bot's identity (`organicAvatarPath(seed)`, same file `:275-277`),
     in the bot's color, with tilted bar eyes. Deterministic per bot.
  5. Empty/garbage color → fallback `#F97316` (Forge Orange).
- **[V]** No initials anywhere in the official rendering: a bot with no
  picture still gets a colored shape/blob, never letters.

### What the WLTW app would have to do

- Parse `avatarSet` (and keep `color` as-is) in `RakazoClient.
  botsFromSpaces`. On Android the SVG shape paths are plain path data,
  so they can be drawn with `android.graphics.Path` (an SVG-path
  parser) or approximated; the organic blob is a seeded path the same
  way. Simplest first step that still looks like the official app:
  data-image → the photo; `::shape_N` / plain hex → a colored circle
  with the bot's shape drawn in, or a circle + initials as today.
  Full fidelity means porting the 8 shape paths + the logo mark.

---

## 2. MausBot — avatars

### Where the avatar lives

- **[V]** `GET /api/bots` → each `WireBot` carries `avatarUrl: string
  | null` — "always present on the wire, null when the bot has none"
  (`packages/phone-protocol/src/wire.ts:263`, `avatarUrl` field).
  The value is a **relative path** of the form
  `/api/attachments/<name>` (an app-owned stored image).
- **[V]** Companion fields on the same entry: `avatarCrop?`
  (`"circle" | "rounded" | "mascot"`), `avatarZoom?` (absent = 1),
  `avatarFocusX/Y?` (0–1, absent = center), plus the mascot fields
  `mascotExpression?`, `mascotBody?`, `mascotFaceColor?` and `color:
  MausColor` (same file).
- **[V]** `color` is a **name, not a hex**: `green | blue | red |
  orange | purple | cyan | pink | yellow | teal | coral | silver |
  chrome`, mapped to hex by `MAUS_COLORS`
  (`src/lib/mascot.ts:51-64`: green `#009957`, blue `#377FE6`, red
  `#D94B52`, orange `#E78531`, purple `#8057C8`, cyan `#0EA5C6`,
  pink `#D84F8B`, yellow `#D8A729`, teal `#01A492`, coral `#E5634E`,
  silver `#A8ADB4`, chrome `#B3BAC4`).

### How the official app renders it

- **[V]** `BotAvatar` in `src/components/Avatar.tsx`, via
  `resolveBotAvatarOutcome` (mirrored in
  `ios/Sources/CompanionCore/BotAvatarRendering.swift`):
  - no `avatarUrl`, or `avatarCrop === "mascot"`, or the image fails
    to load → **gradient mascot**: a mouse silhouette
    (`MASCOT_BODIES[botMascotBody(mascotBody)]`) filled with a
    3-stop gradient from the bot's color, face ink from
    `mascotFaceColor`, animated expression/motion states (blink,
    thinking, working, happy…).
  - `avatarUrl` present with crop `circle`/`rounded` → **flat
    `<img>`**, `object-cover`, cropped with `objectPosition` =
    focus point and `scale(avatarZoom)`, corner radius from the crop.
  - Image load failure falls back to the gradient mascot at runtime.
- **[V]** `InitialsAvatar` in the same file is for **people**, not
  bots — bots never get initials in the official app.

### Fetching the picture

- **[V]** `GET /api/attachments/<name>` serves the raw bytes
  (`server/routes/threads.ts:455+`, `readAttachment`, `cache-control:
  private, no-store`). The route is in the **client-scope** allow
  list (`server/request-auth.ts:421`) and `SERVICE_ALLOW` (`:49`),
  i.e. reachable with the pairing session token — not admin-only.
  The route comment (`threads.ts:443`) says "that GET is open to
  clients". The earlier images investigation reached the same
  conclusion ("same session auth"). **[U]** Whether the WLTW app's
  `Http` layer sends the token in a way this route accepts is not
  proven without a live call — the attachments fetch in chat already
  does this for message images, so the same code path applies.

---

## 3. What the WLTW app shows today

- **[V]** `MainActivity.kt:144`: `bots.sortBy { it.name.lowercase() }`
  — alphabetical, the only ordering.
- **[V]** `item_bot.xml`: a 52dp circle `TextView` (`tvAvatar`) with
  initials (first letters of the first two words,
  `MainActivity.kt:152-159`), white bold text; tint =
  `avatarColor(bot)` (`:162-177`): `bot.colorHex` if it parses via
  `Color.parseColor`, else a 6-color palette hashed by bot id.
  Name + ` •` if unread; subtitle line underneath.
- **[V]** `BotItem` (`api/Models.kt:5-14`) carries only `kind, id,
  name, subtitle, colorHex, unread, threadId, preview` — **no avatar
  fields at all**.
- **[V]** `RakazoClient.botsFromSpaces` (`api/RakazoClient.kt:104-117`)
  reads `color` but **not** `avatarSet`, `updatedAt` or `pinned`.
  `MausBotClient.listBots` (`api/MausBotClient.kt:80-97`) reads
  `color` but **not** `avatarUrl`, `avatarCrop`, or `tasks`.
- **[V]** The chat screen header shows the bot name only, no avatar
  (`ChatActivity.kt:94-96`, `title = botName`).
- **[I]** Consequence, from the value forms above:
  - Rakazo bots whose `color` is a plain hex show their real color
    circle + initials. Bots with `hex::shape_N` or a `data:image/`
    color **do not** — `Color.parseColor` throws on both, so they
    fall through to the hash palette and show a wrong color.
  - MausBot bots **never** show their real color today: their
    `color` is a name like `"green"`, `Color.parseColor("green")`
    throws, so every MausBot bot falls through to the hash palette.
    Their custom pictures (`avatarUrl`) are never fetched.
  - Nothing anywhere in the app shows a bot's real picture, mascot,
    or shape today.

### What changes to match the official look

1. `BotItem` gains: Rakazo `avatarSet`; MausBot `avatarUrl`,
   `avatarCrop`, `avatarZoom`, `avatarFocusX/Y`; both: `updatedAt`
   (and `pinned`) for sorting.
2. A `MAUS_COLORS` name→hex map (12 entries, above) so MausBot
   colors resolve.
3. List row: Rakazo → photo if `data:image/`, else the shipped shape
   path in the bot color (or logo-mark / organic blob per
   `avatarSet`); MausBot → `GET /api/attachments/<basename>` image
   with crop/zoom/focus, else the gradient mascot silhouette. The
   mascot silhouette paths live in `shared/mascot-bodies.ts` /
   `MASCOT_BODIES`; drawing them on Android needs the same SVG-path
   approach as the Rakazo shapes, or a simplified colored-circle
   fallback.
4. Same rendering can be reused in the chat header (currently
   name-only).

---

## 4. Sorting the bot list

### Orderings available from data already fetched (or one cheap add)

| Ordering | Data needed | New API call? |
|---|---|---|
| **Name A–Z** (current) | `name` — already parsed | No |
| **Most recent activity** | Rakazo: `updatedAt` on `BotSchema` (string) — in `spaces/list` already, just not parsed. MausBot: `tasks[]` (newest first) with `updatedAt` / `lastThreadOrderAt` / `createdAt` (epoch ms) — in `GET /api/bots` already, just not parsed; or `GET /api/bots?messages=1`, which includes the latest message per bot with its `createdAt` | No |
| **Pinned first** | Rakazo: `pinned` boolean on `BotSchema`. MausBot: `pinned?` on `WireBot` and on `WireTask` | No |
| **Unread first** | `unread` boolean — already parsed on both | No |
| **By server** | `BotItem.kind` — already known | No, but the operator asked for server badges to be removed earlier; a server-grouped sort would reintroduce a server split in the list |
| **Manual order** | No server field for a custom list order in the traced API (Rakazo has a `bots/reorder` RPC, `packages/contracts/src/bots/bots-rpc.ts:58-112`, but that orders within Rakazo's own UI; MausBot has a server-side `botOrderFor` hook, `server/routes/threads.ts:110`, semantics not traced) | Simplest is local: persist an ordered list of bot ids on the phone (e.g. long-press to drag / move up-down) |

- **[V]** MausBot's own recency helper:
  `src/components/AwakeBotsPicker.tsx:12` — `lastActive(bot) =
  max over tasks of (updatedAt ?? createdAt)`. Thread ordering in
  `packages/phone-protocol/src/wire.ts:608-614`
  (`orderedThreadList`): pinned first, then
  `lastThreadOrderAt ?? createdAt` descending. `WireTask.updatedAt`
  is "epoch ms of the newest message, or createdAt when the thread
  has none" (`wire.ts:150`).
- **[V]** Rakazo's mobile app has an inbox with a **pinned strip**
  (`apps/mobile/lib/inbox/inbox-spaces.ts:49,136-143`;
  `inbox-swipe.ts:26-37` — swipe actions include pin/unpin), so
  pinned-first is the official app's primary ordering concept.
- **[I]** A natural default for the operator: **pinned first, then most
  recent activity, then name** — matches both official apps'
  concepts and needs no new calls, only parsing fields already in
  the responses. A sort picker (Name / Recent / Unread first) is a
  small menu on the list screen; the choice persists in
  `SharedPreferences`.

### What the official apps offer

- **[V]** Rakazo mobile: inbox with pinned strip; pin/unpin via swipe.
  **[U]** The exact default sort of its bot list (recency vs
  manual vs server order) was not established from the files traced.
- **[V]** MausBot web: the awake-bots picker sorts idle-first, then
  `lastActive` descending (`AwakeBotsPicker.tsx:18`).
  **[U]** The main sidebar's bot order was not established from the
  files traced (there is a server-side `botOrderFor` hook, but its
  ordering rule was not traced).

---

## 5. Still unknown

1. Whether Rakazo `BotSchema.updatedAt` tracks last-message activity
   or last bot edit — decides whether it is a trustworthy "recent"
   sort key. Only live data (compare against `preview`) settles it.
2. The exact default sort of the Rakazo web/mobile bot list and the
   MausBot main sidebar (both official apps) — not established from
   the traced files.
3. Whether any of the operator's Rakazo bots actually use a `data:image/`
   color (up to 200KB each, would bloat the list payload) or a
   `::shape_N` color, and which `avatarSet` each uses — visible only
   from a live `spaces/list` response.
4. Whether any of the operator's MausBot bots have a custom `avatarUrl`
   (vs the gradient mascot) and what `avatarCrop`/zoom/focus values
   they carry — visible only from a live `GET /api/bots` response.
5. Whether the WLTW app's `Http` layer fetches
   `GET /api/attachments/<name>` with the pairing token cleanly —
   the chat image path already does this, but the list-avatar path
   has not run live.
6. The MausBot `botOrderFor` server hook's ordering rule, and
   whether Rakazo's `bots/reorder` RPC is a usable cross-client
   manual order — neither was traced to a conclusion.
