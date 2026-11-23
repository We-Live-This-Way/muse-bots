# Images in chat — send (attach) and view, for both servers

Research only. No app code written, no live server contacted.
Sources: local clones `~/workspace/vision-investigation/rakazo` (commit
`64dcd8a`) and `~/workspace/vision-investigation/openmausbot` (commit
`c444029`, v0.1.170). the operator runs MausBot v0.1.161 and Rakazo v0.1.0, so
small drift is possible.

Legend: **[V]** verified from source (file:line cited) · **[I]** inferred
from source · **[U]** unknown / not established from source alone.

What the earlier maps already said, and what this closes: `api-rakazo.md`
§8 left the artifact-upload flow behind `threads/send` `artifactIds`
open — **closed below (§1)**. `api-openmausbot.md` §4/§5 described
incoming `attachments` entries and `GET .../messages/:messageId/image`
but not how a client *sends* an image — **closed below (§4)**.

---

## 1. Rakazo — sending an image (upload, then send)

### Step 1 — upload the file as an artifact

- **[V]** `artifacts/create` RPC: `POST {base}/rpc/artifacts/create`,
  body `{"json": {...}}` like every oRPC call. Contract:
  `packages/contracts/src/artifacts/artifacts-rpc.ts:62-79` (`artifacts.create`).
- **[V]** Request input (threadTarget + file fields):
  ```json
  {
    "botId": "<bot id>",            // exactly one of botId / groupId
    "name": "photo.jpg",            // 1–255 chars
    "description": "…",             // optional, max 280 chars
    "mimeType": "image/jpeg",
    "contentBase64": "<base64 bytes>",
    "clientNonce": "<unique per upload, max 200 chars>"  // optional; a retry with the same nonce returns the artifact it created
  }
  ```
  (`artifacts-rpc.ts:62-79`; name/description limits
  `packages/contracts/src/threads/attachments.ts:9-10`.)
- **[V]** Response is `ArtifactSchema`
  (`packages/contracts/src/artifacts/artifacts.ts:3-13`):
  ```json
  { "id": "<artifact id>", "botId": "<bot id|null>", "groupId": "<…|null>",
    "runId": "<…|null>", "name": "photo.jpg", "description": null,
    "mimeType": "image/jpeg", "size": 123456, "version": 1, "createdAt": "…" }
  ```
  The `id` is what goes into `threads/send` as an `artifactId`. A retry
  with the same `clientNonce` returns the already-created artifact
  instead of duplicating it (server:
  `apps/api/src/content/artifacts.ts:51`, `findReplayedArtifact`).
- **[V]** Size limit: **10 MB per file** by default
  (`ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024`,
  `packages/contracts/src/threads/attachments.ts:2`; server default
  `attachmentMaxBytes()` same file `:126`). Base64 is capped before
  decode at `ATTACHMENT_MAX_BASE64_LENGTH` (`:12`). The server may
  raise the limit via config `maxUploadSizeBytes`
  (`apps/api/src/content/artifacts.ts:59`,
  `decodeAttachmentBase64(input.contentBase64, deps.maxUploadSizeBytes)`).
- **[V]** Mime allowlist enforced server-side:
  `validateAttachmentMimeType(mimeType)`
  (`apps/api/src/content/artifacts.ts:58`). Allowed image types are
  `image/jpeg`, `image/png`, `image/webp`, `image/gif`
  (`ATTACHMENT_IMAGE_MIME_TYPES`, `attachments.ts:16-21`); the full
  allowed list also covers audio, `video/mp4`, `video/quicktime`,
  `video/3gpp`, `image/heic`, `image/heif`, PDF, plain/markdown/csv
  text, JSON, HTML, spreadsheets and zip/rar/7z
  (`ATTACHMENT_ALLOWED_MIME_TYPES`, `attachments.ts:72-85`). A
  non-listed type is rejected.
- **[V]** Max **4 attachments per message** by default
  (`ATTACHMENT_MAX_COUNT = 4`, `attachments.ts:3`; hard ceiling for
  the configurable limit `ATTACHMENT_COUNT_CEILING = 100`, `:5`;
  `threads/send` caps `artifactIds` at that ceiling,
  `packages/contracts/src/threads/thread-send.ts:20`).

### Step 2 — send the message referencing the artifact(s)

- **[V]** `threads/send` input
  (`packages/contracts/src/threads/thread-send.ts:16-46`): threadTarget
  plus `text` (max 100,000 chars), `artifactIds` (max 100), `mentions`,
  `replyToMessageId`, `replyQuote`, `imageHandling: "confirm" | "file"`,
  `clientNonce`. Text or at least one attachment is required.
- **[V]** `imageHandling` semantics (server,
  `apps/api/src/threads/thread-target.ts`):
  - `"confirm"` (the official mobile app's default — see §3): image
    artifacts are stored as `image` blocks carrying
    `imageHandling: "confirm"` (`sendAttachmentBlocks`, `:478-489`).
    It means "explicit visual input requested by a confirmation-aware
    client" (contract comment,
    `packages/contracts/src/threads/message-blocks.ts:296`). It also
    makes the send *steer* a waiting run when the model must see the
    image (`answerSteersRun`, `thread-target.ts:80-94`).
  - `"file"`: the same image artifact is stored as a `file` block
    instead (with its `size` filled in) — the model gets it as a file,
    not as vision input (`sendAttachmentBlocks`, `:478-489`).
  - Omitted: plain `image` block, no flag.
- **[V]** The send is two calls in the official flow: one
  `artifacts/create` per attachment (with per-file nonce
  `<sendNonce>:<index>`), then one `threads/send` with the collected
  `artifactIds` — see §3.

### Viewing an incoming image (Rakazo)

- **[V]** An image in a thread arrives as a message block
  (`packages/contracts/src/threads/message-blocks.ts:293-299`):
  ```json
  { "kind": "image", "artifactId": "<id>", "mimeType": "image/jpeg",
    "name": "photo.jpg", "imageHandling": "confirm" }
  ```
  A non-image attachment is the sibling `file` block:
  `{kind: "file", artifactId, mimeType, name, size}`
  (`message-blocks.ts:300-306`).
- **[V]** To display it, fetch the bytes with `artifacts/get` or
  `artifacts/getById`, both returning `ArtifactWithContentSchema` =
  `ArtifactSchema` + `contentBase64`
  (`packages/contracts/src/artifacts/artifacts.ts:25-28`;
  contract `artifacts-rpc.ts:80-81`). `get` takes threadTarget +
  `artifactId`; `getById` takes just `artifactId`.
- **[V]** The official clients do exactly this: mobile
  `apps/mobile/lib/artifacts/artifacts.ts:24` calls
  `artifacts/getById` (with a download timeout); web
  `apps/web/src/lib/pages/artifact-open.ts:18` calls
  `artifacts/get`. A dedicated viewer exists at
  `apps/mobile/components/media/image-artifact-viewer.tsx`.
  **[I]** So a third-party client should base64-decode `contentBase64`
  and render it; there is no direct image-URL endpoint in the contract.

### How the official mobile app sends an image (§3 of the task)

- **[V]** Picking: `apps/mobile/lib/composer/pick-attachments.ts`.
  `pickFromLibrary` (multi-select from the photo library),
  `takePhoto` (camera), `pickDocuments`, plus drag-drop and share-sheet
  paths. Images are prepared before upload: HEIC/HEIF convert to JPEG;
  GIF/WebP are kept as-is (animation/transparency preserved); photos
  larger than `ATTACHMENT_IMAGE_MAX_EDGE` = **1600 px** are resized and
  saved at quality **0.7** JPEG (PNG stays PNG);
  `ATTACHMENT_IMAGE_MAX_EDGE` / `ATTACHMENT_IMAGE_QUALITY`,
  `attachments.ts:6-7`. Each picked file is read to base64 in memory
  (`readUriAsBase64`), filtered by size (10 MB) and count (4), and
  shown from a local preview URI.
- **[V]** Upload + send: `apps/mobile/lib/thread/send/thread-feedback.ts:75-126`
  (`deliverSend`). For each pending attachment it calls
  `request("artifacts/create", {...target, name, mimeType,
  contentBase64, clientNonce: "<sendNonce>:<index>"}, {timeoutMs:
  60_000})`, caches the returned id per pending attachment (so a
  retry reuses it), then calls `threads/send` (or
  `threads/sendWithRoutines`) with `artifactIds`, `imageHandling:
  attempt.imageHandling ?? "confirm"`, text, mentions, reply data.
  **[I]** A third-party client should copy this shape: upload each
  file first, then one send. Uploading base64 in memory is the
  official pattern — there is no multipart/binary upload endpoint.

---

## 2. MausBot — sending an image (upload, then send with a tag)

### Step 1 — upload the raw bytes

- **[V]** Images: `POST /api/attachments` (server:
  `server/routes/threads.ts:344-398`). **Raw bytes in the request
  body, not JSON, not base64.** `Content-Type` must be an image type;
  anything else gets `400 {error: "content-type must be an image type"}`.
  Optional query `?uploadId=<uuid>` (UUID v1–8 format,
  `server/attachments.ts` `validateAttachmentUploadId`) — retrying
  with the same UUID returns the same committed file instead of a
  duplicate.
- **[V]** Response `201` with the saved file
  (`SavedAttachment`, `server/attachments.ts`):
  ```json
  { "path": "/<server data dir>/attachments/<uuid>.png",
    "mime": "image/png", "bytes": 123456 }
  ```
  `path` is the **server filesystem path**. Keep it: it is what the
  send step references.
- **[V]** Image limits: only `image/png`, `image/jpeg`, `image/gif`,
  `image/webp` are accepted (`IMAGE_MIMES`, `server/attachments.ts`);
  per-image max **10 MB** (`IMAGE_MAX_BYTES = 10 * 1024 * 1024`,
  same file); over it → `413`. Whole-store quota: **512 MB** default
  (`DEFAULT_ATTACHMENTS_MAX_BYTES`), **8 GB** on a Cloud home, raisable
  with `OMB_ATTACHMENTS_MAX_BYTES`; a full store answers `507`
  (`attachments.ts` `AttachmentReservation`).
- **[V]** Other files: `POST /api/files?name=<filename>` (same file,
  `threads.ts:400-439`). Raw bytes, `?uploadId=` supported, per-file
  max **100 MB** (`FILE_MAX_BYTES`,
  `packages/phone-protocol/src/upload-limits.ts:6`). Returns `201`
  `{path, name, mime, bytes}`. Known types keep their extension
  (video, audio, pdf, office docs, archives); anything else is stored
  as an inert `.bin` download.

### Step 2 — send the message with an attachment tag in the text

- **[V]** `POST /api/bots/:id/messages` takes **no attachment field**.
  The send schema is exactly `{threadId, sendId (16–80 chars),
  text | reaction, expectedActiveLeafId?, expectedApprovalMode?,
  replyToId?, onBehalfOf?}` (`guardedSendSchema`,
  `server/routes/bot-messages.ts:153-165`). Images travel inside
  the **text**, as a standalone tag on its own line:
  ```
  <attached-image path="<path from the upload response>" name="photo.png" />
  ```
  (For a non-image file, `<attached-file path="…" name="…" />`.)
- **[V]** The server parses that tag into native image input for the
  turn (`server/turn-images.ts:142-196`, `extractTurnImages`):
  - Only a **standalone** tag counts — the tag must be alone on its
    line. A tag inside prose stays visible as ordinary text and never
    becomes image input.
  - The path must resolve to a file **inside the attachments dir**
    (`ownedImage` / `ownedAttachment`, `turn-images.ts:80-115`):
    basename must be `<uuid>.png|.jpg|.gif|.webp`, the file must
    exist there, be a real file (not a symlink), and be ≤ 10 MB.
    **This is why the tag carries the filesystem path the upload
    returned, not the `/api/attachments/…` URL** — a URL-form path
    does not resolve into the store and is silently dropped.
  - Per-turn caps: max **10 images** (`TURN_IMAGE_MAX_COUNT`) and
    **40 MB total** (`TURN_IMAGE_MAX_BYTES = 4 * IMAGE_MAX_BYTES`);
    over either → `413` (`turn-images.ts:13-16`).
  - Admitted image tags are removed from the text the model sees;
    the images ride as native vision input. A video's
    `<attached-file>` tag stays in the text (its sampled frames are
    added on top); max 1 video per turn.
- **[V]** Auth on both steps is the same session auth as other API
  calls (bearer/cookie; see `api-openmausbot.md` §1). The upload
  routes live in the same `createThreadRoutes` handler as the message
  routes (`server/routes/threads.ts`).

### Viewing an incoming image (MausBot)

- **[V]** Images arrive as `WireMessage.attachments` entries
  (`packages/phone-protocol/src/wire.ts:450-458`):
  ```json
  { "kind": "image", "path": "/api/attachments/<uuid>.png",
    "mime": "image/png", "name": "photo.png" }
  ```
  (`file` entries: `{kind:"file", path, mime, name}`;
  `audio` entries: `{kind:"audio", path, mime, durationMs?, text?}`.)
  The wire comment states renderers receive **only the allowlisted
  `/api/attachments` URL** as `path` — `file` entries are opened
  through the message-scoped file route, never by path.
- **[V]** Fetch the bytes: `GET /api/attachments/<name>`
  (`server/routes/threads.ts:455-491`). Returns the raw bytes with
  `content-type` = the stored mime, `cache-control: private,
  no-store`, `x-content-type-options: nosniff`. Audio supports Range
  requests (206 / 416). Unknown name → 404. The route comment says
  this GET is "open to clients" (client scope, not admin-only).
  `DELETE /api/attachments/<name>` removes your own upload (404 if
  not yours, 409 if a message uses it).
- **[V]** Screen frames are a different path: a `WireMessage` of kind
  `"screen"` carries `png` (base64) + `mime`, and
  `GET /api/threads/:id/messages/:messageId/image`
  (`server/routes/threads.ts:164-181`) returns that message's PNG
  bytes (`content-type: message.mime ?? "image/png"`); 404 when the
  message has no `png`. This is for bot screen frames, not user
  attachments.
- **[V]** Bot-sent images arrive the same way: when a bot attaches a
  file from its computer, the server builds a message with
  `attachments: [{kind:"image", path, mime, name, source?}]`
  (`server/bot-attachment.ts:164-169`, `219`).

---

## 3. What a third-party client must do (per server)

| | Rakazo | MausBot |
|---|---|---|
| Pick | Any picker; resize photos to ≤1600 px longest edge, JPEG q0.7, keep GIF/WebP; HEIC→JPEG | Any picker; no resize required by the server |
| Upload | `POST /rpc/artifacts/create` with base64, `{"json": {botId\|groupId, name, mimeType, contentBase64, clientNonce?}}` → `ArtifactSchema.id` | `POST /api/attachments` with raw bytes, `Content-Type: image/*`, optional `?uploadId=<uuid>` → `{path, mime, bytes}` |
| Send | `POST /rpc/threads/send` with `artifactIds: [id]`, `imageHandling: "confirm"` (default) or `"file"` | `POST /api/bots/:id/messages` with text containing a standalone `<attached-image path="<upload path>" name="…" />` line |
| Limits | 10 MB/file, 4/message (default), jpeg/png/webp/gif (+files) | 10 MB/image, 10 images / 40 MB per turn, png/jpg/gif/webp |
| View | `image` block `{artifactId, mimeType, name}` → `artifacts/get` or `artifacts/getById` → `contentBase64`, base64-decode and render | `attachments[]` entry `{kind:"image", path:"/api/attachments/<name>", mime, name}` → `GET` that URL (same session auth) → raw bytes |

## 4. Still unknown / to verify against a live server

- **[U]** Rakazo: the exact client UX of an `imageHandling: "confirm"`
  image block (whether the bot/user sees a confirmation step, or the
  flag is only a steering hint). Verified only that the server marks
  the block and that `"file"` downgrades it to a file block.
- **[U]** Rakazo: whether `artifacts/get` vs `getById` differs in
  auth/scope for a third-party client (both exist in the contract;
  the mobile app uses `getById`, the web app `get`).
- **[U]** Rakazo: the deployed server's `maxUploadSizeBytes` /
  `maxAttachmentsPerMessage` config values (defaults are 10 MB / 4).
- **[U]** MausBot: whether a user-sent message, read back via
  `GET /api/threads/:id/messages`, carries a populated `attachments`
  array, or the client must render its own sent image from the tag it
  sent. (Bot messages do carry the array; the user-message read-back
  shape was not traced.)
- **[U]** MausBot: the stored `attachments[].path` form — the wire
  comment says renderers receive the `/api/attachments` URL, while
  bot-side creation stores the filesystem path
  (`server/bot-attachment.ts:164`). A client should handle both by
  taking the basename and requesting
  `GET /api/attachments/<basename>`; verify against a live server.
- **[U]** MausBot: `POST /api/attachments` and `POST /api/files`
  auth scope — they sit in the same route handler as the message
  routes, so session auth is expected, but the exact scope check was
  not traced line-by-line.
- **[U]** Version drift: MausBot v0.1.161 (running) vs v0.1.170
  (mapped); Rakazo v0.1.0 (running) vs the 2026-11-18 commit.
