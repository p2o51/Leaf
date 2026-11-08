# Leaf for Mac · editor spike

Step 0 of the multi-platform plan (Notion 公文包「Leaf 做 macOS、iOS 和鸿蒙版的技术路线与分期」): one window,
one TextKit 2 `NSTextView`, a generated ~50 000-character Chinese/English document, focus mode and
typewriter scrolling. No Notion, no sync. It answers one question: does native TextKit 2 stay smooth enough
to build Leaf's editor on?

```sh
cd apple/EditorSpike
swift build -c release
.build/release/EditorSpike              # opens the window; ⌘1 focus sentence, ⌘2 paragraph, ⌘0 off, ⌘T typewriter
.build/release/EditorSpike --bench      # types 2 000 characters into the middle, prints per-keystroke timings, quits
```

Fonts: if iA Writer Duo / Mono are installed (github.com/iaolo/iA-Fonts, SIL OFL) they are used, otherwise
the system monospaced font. CJK falls back to the system (PingFang SC).

Needs Xcode command line tools and macOS 14+.
