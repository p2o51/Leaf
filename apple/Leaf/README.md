# Leaf for Mac

The same Leaf as on Android (随笔 + 标签, Markdown body, on one Notion database), with the 写作台 as the
editor. Plan: Notion 公文包「Leaf 做 macOS、iOS 和鸿蒙版的技术路线与分期」. The Android desk (Leaf PR #15,
`ui/essay/DeskText.kt`, `DeskStyle.kt`) is the reference for every editing rule; `Tests/LeafCoreTests/
DeskTextTests.swift` runs the same 21 cases.

## Layout

- `Sources/LeafCore`: no UI. Markdown ⇄ Notion blocks (`DeskText`), sentence / paragraph spans
  (`DeskFocus`), 文风 and 来源 (`DeskStyle`), the Notion client and mapping, the local store and sync.
  iPhone/iPad can reuse it later.
- `Sources/LeafMac`: SwiftUI shell (sidebar: 全部随笔 / 涂鸦墙 / 颜色 / 标签, essay list, desk) and the
  TextKit 2 editor (`DeskController`): the whole essay in one `NSTextView`, first line = title, photos and
  other blocks Leaf only shows as cards that can't be typed over.

## Local first

The library lives in `~/Library/Application Support/Leaf` (JSON files). Typing only touches the text view;
600 ms after the pen stops the essay is written there and its changes are queued for Notion, and the queue
is pushed about 1.5 s later. While an essay is open and Leaf is in front, that one page is fetched again
every 30 s (and right away when you come back to the window); everything else syncs every 5 minutes.
A fetched body never overwrites edits still waiting to go out. 来源 marks stay on this Mac.

## Build and run

Needs macOS 14+ and the Xcode command line tools (no Xcode project, no developer account).

```sh
cd apple/Leaf
swift test                      # LeafCore: desk rules (21 cases shared with Android), Notion mapping, sync
scripts/bundle.sh               # → build/Leaf.app (ad-hoc signed)
open build/Leaf.app
```

`swift run LeafMac` also works for a quick look, but sign-in with Notion needs the bundled app.

## Notion

Settings (⌘,) → 用 Notion 登录 goes through `notion-oauth-relay`, the same Worker and `leaf://notion-oauth`
callback as Android, so nothing changes on the relay. A pasted internal-integration token works too. The
credential is kept in the login Keychain. Because the app is ad-hoc signed, macOS may ask once after each
rebuild whether Leaf may read its Keychain item.

## Shortcuts

⌘N new essay · ⌘S save and send now · ⌘R sync · ⌘D cycle focus (全文 / 句 / 段) · ⌘T typewriter ·
⌘1 ⌘2 ⌘3 IBM Plex Serif / Sans / Mono · ⌘= ⌘- text size · ⇧⌘L 文风 · ⇧⌘O 来源.

## Not yet

Photos are shown, not added; the wall can add lines but not recolour or delete them; covers and voice
stay on the phone. Fonts are IBM Plex (OFL, see `Resources/Fonts/IBM-Plex-OFL.txt`), not iA Writer's.
