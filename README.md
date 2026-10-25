# Leaf

An Android writing app, sister to Sheaf. One Notion database holds everything: one row is the
**涂鸦墙** (scribble wall, each short line a block with a Notion highlight colour), every other row
is an essay with a cover or a Keep-style card colour.

- Plan: https://claude.ai/artifact/1yprJbPjFiszgDCEPap3Tc
- Interactive prototype: https://claude.ai/artifact/8YRQivJRgP2GjKTGFhuMMQ

## Milestones

1. **M0 skeleton** (this): theme, sample data, home strip + essay grid, the + menu, the full wall with
   a continuous open animation, select / recolour / delete / 复制到随笔, a basic editor, tablet split.
2. M1 scribble wall on Notion: OAuth, field mapping, Room + WorkManager sync, hold-to-talk through
   Relay (ElevenLabs Scribe v2), quick-note panel, widgets, QS tile.
3. M2 essays: Craft-style block editor, photo and cover upload, card colours.
4. M3 scribbles meet essays: insert / quote as synced blocks, keyword recommendations, drag-in rail.
5. M4 voice IME and vector recommendations. M5 (pending): question-style suggestions, multi-user, Pro.

## Build

JDK 21 and the Android SDK. `./gradlew assembleDebug testDebugUnitTest`. CI does the same plus
`assembleRelease` and uploads the debug APK as an artifact.

## Notion sign-in

Settings leads with **用 Notion 登录**; a pasted internal-integration token stays as the fallback.
Sign-in goes through `notion-oauth-relay` (the Worker shared with Sheaf, 墨记 and CronPaw), which holds
the client secret; the app only sends it the code and the refresh token, and keeps the token pair in
the Keystore-encrypted vault. Notion's 401 triggers one refresh and a retry; a refresh Notion refuses
means signing in again. The relay address is `NotionOAuthConfig.RelayBaseUrl` (blank hides sign-in).

One-time setup:

1. In the Notion developer portal, create a **Public** connection named `Leaf`: Read content, Update
   content and Insert content on, no user information, redirect URI
   `https://notion-oauth-relay.p2o51willam.workers.dev/leaf/callback`.
2. In `~/Developer/notion-oauth-relay`: `npx wrangler secret put LEAF_CLIENT_ID`, then
   `npx wrangler secret put LEAF_CLIENT_SECRET`, then `npx wrangler deploy`.
