# Leaf for HarmonyOS

One ArkTS / ArkUI codebase for HarmonyOS phone, tablet and 2-in-1 (HarmonyOS PC), desktop first. Same
product as the Android app: essays and tags on 51's Notion database, local first.

- `entry/src/main/ets/core/` — pure logic shared in spirit with Android: Markdown ↔ Notion blocks
  (DeskText), focus spans (DeskFocus), Chinese style check and authorship (DeskStyle), the editor's look
  (DeskLook), Notion JSON mapping (NotionJson), sync decisions (SyncRules), tag filters (Tags). Written in
  the ArkTS subset that is also TypeScript, with no @kit imports, so `tools/core-test` type-checks it,
  runs the shared acceptance cases on Node and runs the ArkTS linter, all without the HarmonyOS SDK.
- `entry/src/main/ets/data/` — RDB library and outbox (LeafDb), Notion over Remote Communication Kit
  (NotionApi), sync engine, token in the Asset Store, keyboard watch (电脑模式 / 平板模式).
- `entry/src/main/ets/ui/`, `pages/` — home, the 写作台 (desk editor), the tablet block editor, settings.

Build, signing, install and the first-run checklist: [HANDOFF.md](HANDOFF.md).

```bash
cd harmony/tools/core-test && npm ci && npm test   # 40 cases + ArkTS lint, no SDK needed
```

IBM Plex (OFL, `entry/src/main/resources/rawfile/fonts/IBM-Plex-OFL.txt`) is bundled for the 写作台; iA
Writer's fonts ask not to be built into editors, so Leaf does not use them.
