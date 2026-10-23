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
