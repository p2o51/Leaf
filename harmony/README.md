# Leaf for HarmonyOS

One ArkTS / ArkUI codebase for HarmonyOS phone, tablet and 2-in-1 (HarmonyOS PC), desktop first. Same
product as the Android app: essays and tags on 51's Notion database, local first.

- `entry/src/main/ets/core/` — pure logic shared in spirit with Android: Markdown ↔ Notion blocks
  (DeskText), focus spans (DeskFocus), Chinese style check and authorship (DeskStyle), the editor's look
  (DeskLook), Notion JSON mapping (NotionJson), sync decisions (SyncRules), tag filters (Tags). Written in
  the ArkTS subset that is also TypeScript, with no @kit imports, so `tools/core-test` type-checks it,
  runs the shared acceptance cases on Node and runs the ArkTS linter, all without the HarmonyOS SDK.
- `entry/src/main/ets/data/` — RDB library and outbox (LeafDb), Notion over Remote Communication Kit
  (NotionApi), sync engine, Notion sign-in through notion-oauth-relay (NotionOAuth), the essay folder
  (LocalFolder: one Markdown file per essay, front matter for Notion page / tags / colour / pin, kept in step
  both ways; core/LocalFiles holds the rules), 语音输入 with ElevenLabs Scribe (Voice), keyboard watch
  (电脑模式 / 平板模式). Notion is optional: without it essays live in the library and the folder.
- `entry/src/main/ets/ui/`, `pages/` — home, the 写作台 (desk editor), the tablet block editor, settings.
  Home is laid out as Leaf for Mac: sidebar (search, 全部随笔, 颜色, 标签, sync line), essay list, and the
  写作台 beside it; narrower windows fold the sidebar into a drawer, then open an essay over the list. The
  frame is HarmonyOS's own: HDS (UI Design Kit) side bar and navigation, `sys.symbol` icons, and on a 2-in-1
  the window's title bar merged into each column's top row (`ui/WindowChrome.ets`). What stays Leaf's: the
  写作台's type (IBM Plex), the lime / paper / indigo palette and the Notion colours. HDS needs HarmonyOS 6.0
  (API 20), so that is the minimum.

Build, signing, install and the first-run checklist: [HANDOFF.md](HANDOFF.md).

```bash
cd harmony/tools/core-test && npm ci && npm test   # 73 cases + ArkTS lint, no SDK needed
```

IBM Plex (OFL, `entry/src/main/resources/rawfile/fonts/IBM-Plex-OFL.txt`) is bundled for the 写作台; iA
Writer's fonts ask not to be built into editors, so Leaf does not use them.
