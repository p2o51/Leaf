import AppKit
import Combine
import LeafCore

/// The text view: pastes are plain text and marked as pasted (来源); drags bring only text.
final class DeskTextView: NSTextView {
    var pasting = false

    override func paste(_ sender: Any?) {
        pasting = true
        defer { pasting = false }
        pasteAsPlainText(sender)
    }

    override var readablePasteboardTypes: [NSPasteboard.PasteboardType] { [.string] }
    override var acceptableDragTypes: [NSPasteboard.PasteboardType] { [.string] }
}

/// The 写作台 on the Mac: the whole essay in ONE TextKit 2 text view, as Markdown, like the Android desk
/// (Leaf PR #15). The first line is the title. Blocks Leaf only shows (photos, files, rules, synced
/// scribbles, paragraphs Notion holds more of) sit in the text as attachment characters (U+FFFC) that
/// can't be typed over, so the writing is never cut into one field per paragraph.
///
/// Speed rules from the spike (apple/EditorSpike, p99 under 3.4 ms a keystroke on 56k characters):
/// 专注, 文风 and 来源 are *rendering attributes* (repaint only, the storage is never touched); only
/// Markdown styling (heading sizes, quiet markers) changes the storage, and only for the edited paragraphs.
@MainActor
final class DeskController: NSObject, NSTextViewDelegate, NSTextStorageDelegate {
    static let writeAfter: TimeInterval = 0.6
    static let attachmentChar: unichar = 0xFFFC
    static let column: CGFloat = 680

    let scrollView = NSScrollView()
    let textView: DeskTextView
    private let model: AppModel
    private let settings: DeskSettings
    private(set) var essayId: String?

    /// The page between edits: what each slot holds. Slot k is the run before shown block k; the last slot
    /// follows the last shown block.
    private var runs: [[Block]] = [[]]
    private var shown: [Block] = []
    /// Each slot's Markdown as last written, and the title.
    private var writtenRuns: [String] = [""]
    private var writtenTitle = ""
    /// Per UTF-16 character of the whole text: typed or pasted.
    private var origins: [Origin] = []
    private var hits: [StyleHit] = []

    private var loading = false
    private var dirty = false
    private var writeTimer: Timer?
    private var styleTask: Task<Void, Never>?
    private var bag: Set<AnyCancellable> = []

    private var ink: NSColor { .textColor }
    private var quiet: NSColor { .tertiaryLabelColor }
    private var pasted: NSColor { NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 0.68, green: 0.70, blue: 1, alpha: 1) : NSColor(srgbRed: 0.23, green: 0.25, blue: 0.80, alpha: 1) } }
    private var leaf: NSColor { NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 0.66, green: 0.84, blue: 0.31, alpha: 1) : NSColor(srgbRed: 0.18, green: 0.42, blue: 0, alpha: 1) } }
    private var styleMark: NSColor { NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(srgbRed: 0.85, green: 0.76, blue: 0.35, alpha: 0.28) : NSColor(srgbRed: 0.95, green: 0.82, blue: 0.30, alpha: 0.35) } }

    init(model: AppModel) {
        self.model = model
        self.settings = model.desk
        // usingTextLayoutManager: true = TextKit 2 (NSTextLayoutManager + NSTextContentStorage).
        textView = DeskTextView(usingTextLayoutManager: true)
        super.init()

        scrollView.hasVerticalScroller = true
        scrollView.autohidesScrollers = true
        scrollView.drawsBackground = true
        scrollView.backgroundColor = .textBackgroundColor
        scrollView.documentView = textView

        textView.autoresizingMask = [.width]
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.textContainer?.widthTracksTextView = false
        textView.textContainer?.lineFragmentPadding = 0
        // Rich so attachments and styling survive; pastes are made plain in DeskTextView.
        textView.isRichText = true
        textView.importsGraphics = false
        textView.allowsUndo = true
        textView.usesFontPanel = false
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isAutomaticSpellingCorrectionEnabled = false
        textView.isAutomaticTextReplacementEnabled = false
        textView.isContinuousSpellCheckingEnabled = false
        textView.drawsBackground = false
        textView.insertionPointColor = leaf
        textView.selectedTextAttributes = [.backgroundColor: leaf.withAlphaComponent(0.2)]
        textView.delegate = self
        textView.textStorage?.delegate = self

        scrollView.postsFrameChangedNotifications = true
        NotificationCenter.default.addObserver(self, selector: #selector(recenter), name: NSView.frameDidChangeNotification, object: scrollView)

        settings.$font.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.restyleAll() } }.store(in: &bag)
        settings.$textSize.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.restyleAll() } }.store(in: &bag)
        settings.$focus.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.render() } }.store(in: &bag)
        settings.$origins.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.render() } }.store(in: &bag)
        settings.$style.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.checkStyle(now: true) } }.store(in: &bag)
        settings.$typewriter.dropFirst().sink { [weak self] on in Task { @MainActor in if on { self?.centreCaret() } } }.store(in: &bag)
        model.$revision.dropFirst().sink { [weak self] _ in Task { @MainActor in self?.adoptIfResting() } }.store(in: &bag)
    }

    // ---- layout ----

    /// Keep the column centred however wide the window is; narrower windows wrap, never clip.
    @objc func recenter() {
        let width = scrollView.contentSize.width
        let column = min(Self.column, max(240, width - 64))
        textView.textContainer?.containerSize = NSSize(width: column, height: .greatestFiniteMagnitude)
        textView.textContainerInset = NSSize(width: max(32, (width - column) / 2), height: 72)
        if settings.typewriter { centreCaret() }
    }

    private var bodyFont: NSFont { Fonts.body(settings.font, size: settings.textSize) }

    private var bodyParagraph: NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        p.lineHeightMultiple = settings.font == .mono ? 1.55 : 1.6
        p.paragraphSpacing = 2
        return p
    }

    private var baseAttributes: [NSAttributedString.Key: Any] {
        [.font: bodyFont, .foregroundColor: ink, .paragraphStyle: bodyParagraph]
    }

    // ---- loading an essay ----

    /// The essay being opened. The text view keeps showing (and [essayId] keeps naming) the previous essay
    /// until the new one is laid out, and can't be typed into meanwhile, so nothing typed during the fetch
    /// is saved under the wrong essay.
    private var opening: String?

    func open(_ essayId: String) async {
        writeNow()
        opening = essayId
        textView.isEditable = false
        await model.open(essayId)
        // Another essay was picked while this one was fetched: that open wins.
        guard opening == essayId else { return }
        self.essayId = essayId
        await load(caret: nil)
        guard opening == essayId else { return }
        opening = nil
        textView.isEditable = true
        model.openDesk = self
        textView.window?.makeFirstResponder(textView)
    }

    /// Lays the page out from the store. [caret] keeps the cursor near where it was when adopting news.
    private func load(caret: Int?) async {
        guard let id = essayId else { return }
        let title = await model.store.essay(id)?.title ?? ""
        let blocks = await model.store.blocks(id)
        let spans = await model.store.origins(id)
        guard id == essayId else { return }

        let parts = DeskText.parts(blocks)
        shown = []
        runs = [[]]
        for part in parts {
            switch part {
            case .text(_, let b): runs[runs.count - 1] = b
            case .shown(let b): shown.append(b); runs.append([])
            }
        }

        // Build the text and its origins: title, then each slot's Markdown and the shown blocks.
        let doc = NSMutableAttributedString()
        var marks: [Origin] = []
        func add(_ s: String) {
            doc.append(NSAttributedString(string: s, attributes: baseAttributes))
            marks += Array(repeating: .typed, count: s.utf16.count)
        }
        add(title)
        writtenTitle = title
        writtenRuns = []
        for k in runs.indices {
            let rendered = DeskText.render(runs[k])
            writtenRuns.append(rendered.text)
            if !rendered.text.isEmpty {
                add("\n\n")
                let laid = DeskOrigins.lay(rendered.text.utf16.count, rendered.starts, runs[k].map { spans[$0.id] ?? [] })
                doc.append(NSAttributedString(string: rendered.text, attributes: baseAttributes))
                marks += laid
            }
            if k < shown.count {
                add("\n\n")
                let card = NSMutableAttributedString(attachment: attachment(for: shown[k]))
                card.addAttributes(baseAttributes, range: NSRange(location: 0, length: card.length))
                doc.append(card)
                marks.append(.typed)
            }
        }
        if runs.last?.isEmpty != false { add("\n\n") }

        loading = true
        textView.textStorage?.setAttributedString(doc)
        origins = marks
        styleMarkdown(in: NSRange(location: 0, length: doc.length))
        loading = false
        dirty = false
        textView.undoManager?.removeAllActions()
        textView.typingAttributes = baseAttributes

        let ns = textView.string as NSString
        let at = caret.map { min($0, ns.length) } ?? (title.isEmpty ? 0 : ns.length)
        textView.setSelectedRange(NSRange(location: at, length: 0))
        recenter()
        checkStyle(now: true)
        render()
        loadPictures()
    }

    /// News from Notion (or the phone) reaches the open window once the pen has rested and everything
    /// typed has been written.
    private func adoptIfResting() {
        guard let id = essayId, !dirty, Date().timeIntervalSince(model.lastEdit) > AppModel.restBeforeRefresh else { return }
        Task {
            let stored = await model.store.blocks(id)
            let title = await model.store.essay(id)?.title ?? ""
            let mine = slotsAsBlocks()
            // Where a block lives in Notion (its id, a freshly signed URL) is not news for the writer.
            func same(_ a: [Block], _ b: [Block]) -> Bool {
                a.count == b.count && zip(a, b).allSatisfy { $0.id == $1.id && $0.sameContent($1) }
            }
            guard id == essayId, !dirty, !same(stored, mine) || title != writtenTitle else { return }
            await load(caret: textView.selectedRange().location)
        }
    }

    private func slotsAsBlocks() -> [Block] {
        var out: [Block] = []
        for k in runs.indices {
            out += runs[k]
            if k < shown.count { out.append(shown[k]) }
        }
        return out
    }

    // ---- driving the desk (the screenshot run) ----

    /// Puts the caret just after the first [needle], or at the end.
    func placeCaret(after needle: String) {
        let ns = textView.string as NSString
        let r = ns.range(of: needle)
        let at = r.location == NSNotFound ? ns.length : NSMaxRange(r)
        textView.window?.makeFirstResponder(textView)
        textView.setSelectedRange(NSRange(location: at, length: 0))
    }

    /// Types or pastes [text] at the caret through the same path a keyboard or ⌘V takes.
    func insert(_ text: String, pasted: Bool) {
        textView.pasting = pasted
        textView.insertText(text, replacementRange: textView.selectedRange())
        textView.pasting = false
    }

    var focusRange: Range<Int>? {
        guard settings.focus != .off else { return nil }
        let caret = textView.selectedRange().location
        return settings.focus == .sentence ? DeskFocus.sentence(textView.string, caret) : DeskFocus.paragraph(textView.string, caret)
    }

    var styleHitCount: Int { hits.count }
    var pastedCount: Int { origins.filter { $0 == .pasted }.count }

    // ---- writing ----

    /// The text split back into the title and each slot's Markdown (UTF-16 offsets of each slot's start).
    private func split() -> (title: String, slots: [(text: String, start: Int)]) {
        let units = Array(textView.string.utf16)
        var end = 0
        while end < units.count, units[end] != 0x0A { end += 1 }
        let title = String(decoding: units[0..<end], as: UTF16.self).trimmingCharacters(in: .whitespaces)
        var slots: [(String, Int)] = []
        var start = end
        var i = end
        func close(_ upTo: Int) {
            var a = start
            var b = upTo
            while a < b, units[a] == 0x0A { a += 1 }
            while b > a, units[b - 1] == 0x0A { b -= 1 }
            slots.append((String(decoding: units[a..<b], as: UTF16.self), a))
        }
        while i < units.count {
            if units[i] == Self.attachmentChar {
                close(i)
                start = i + 1
            }
            i += 1
        }
        close(units.count)
        return (title, slots)
    }

    func writeNow() {
        writeTimer?.invalidate()
        writeTimer = nil
        guard dirty, let id = essayId else { return }
        dirty = false
        let (title, slots) = split()
        guard slots.count == runs.count else {
            // Should not happen (attachments can't be typed over); keep the text, reload from the store.
            Task { await load(caret: textView.selectedRange().location) }
            return
        }
        var gone = Set<String>()
        for k in slots.indices where slots[k].text != writtenRuns[k] {
            let (blocks, removed) = DeskText.update(runs[k], slots[k].text, newId: { UUID().uuidString.lowercased() })
            runs[k] = blocks
            writtenRuns[k] = slots[k].text
            gone.formUnion(removed)
        }
        writtenTitle = title
        // Marks follow the blocks: each block's words keep the origins of the characters they came from.
        var marks: [String: [OriginSpan]] = [:]
        for k in slots.indices {
            let lines = DeskText.parse(slots[k].text)
            for (line, block) in zip(lines, runs[k]) {
                marks[block.id] = DeskOrigins.spans(origins, slots[k].start + line.textStart, line.text.utf16.count)
            }
        }
        let blocks = slotsAsBlocks()
        Task { await model.wrote(id, title: title, blocks: blocks, gone: gone, origins: marks) }
    }

    // ---- NSTextViewDelegate ----

    func textView(_ textView: NSTextView, shouldChangeTextIn range: NSRange, replacementString: String?) -> Bool {
        // A shown block can't be typed over or deleted from the desk; it is changed in Notion or on the phone.
        let ns = textView.string as NSString
        if range.length > 0 {
            for i in range.location..<min(range.location + range.length, ns.length) where ns.character(at: i) == Self.attachmentChar {
                NSSound.beep()
                return false
            }
        }
        if let r = replacementString, r.utf16.contains(Self.attachmentChar) { return false }
        return true
    }

    func textDidChange(_ notification: Notification) {
        guard !loading else { return }
        dirty = true
        model.lastEdit = Date()
        writeTimer?.invalidate()
        writeTimer = Timer.scheduledTimer(withTimeInterval: Self.writeAfter, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.writeNow() }
        }
        checkStyle(now: false)
    }

    func textViewDidChangeSelection(_ notification: Notification) {
        guard !loading else { return }
        render()
        if settings.typewriter { centreCaret() }
    }

    // ---- NSTextStorageDelegate: origins and Markdown styling ----

    nonisolated func textStorage(_ textStorage: NSTextStorage, didProcessEditing editedMask: NSTextStorageEditActions, range editedRange: NSRange, changeInLength delta: Int) {
        guard editedMask.contains(.editedCharacters) else { return }
        MainActor.assumeIsolated {
            guard !loading else { return }
            // 来源: the inserted characters are pasted or typed; what they replaced goes.
            let oldLength = editedRange.length - delta
            let from = min(editedRange.location, origins.count)
            let to = min(from + max(0, oldLength), origins.count)
            let mark: Origin = textView.pasting ? .pasted : .typed
            origins.replaceSubrange(from..<to, with: Array(repeating: mark, count: editedRange.length))
            if origins.count != textStorage.length {
                origins = Array(origins.prefix(textStorage.length)) + Array(repeating: .typed, count: max(0, textStorage.length - origins.count))
            }
            styleMarkdown(in: editedRange)
        }
    }

    /// Heading sizes, quiet Markdown markers and the big title line, for the paragraphs touching [range].
    private func styleMarkdown(in range: NSRange) {
        guard let storage = textView.textStorage else { return }
        let ns = storage.string as NSString
        guard ns.length > 0 else { return }
        let whole = ns.paragraphRange(for: NSRange(location: min(range.location, ns.length), length: min(range.length, ns.length - min(range.location, ns.length))))
        let size = settings.textSize
        var location = whole.location
        while location < NSMaxRange(whole) {
            let para = ns.paragraphRange(for: NSRange(location: location, length: 0))
            let line = ns.substring(with: para)
            var font = bodyFont
            var marker = 0
            if para.location == 0 {
                font = Fonts.body(settings.font, size: size * 1.65, bold: true)
            } else if let level = Self.headingLevel(line) {
                font = Fonts.body(settings.font, size: size * [1.45, 1.25, 1.1][level - 1], bold: true)
                marker = level + 1
            } else if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") || line.hasPrefix("> ") {
                marker = 2
            } else if let n = Self.numberMarker(line) {
                marker = n
            }
            // Attachments keep their own attributes apart from the paragraph style.
            storage.addAttribute(.paragraphStyle, value: bodyParagraph, range: para)
            storage.enumerateAttribute(.attachment, in: para) { value, sub, _ in
                if value == nil {
                    storage.addAttributes([.font: font, .foregroundColor: ink], range: sub)
                }
            }
            if marker > 0, marker <= para.length {
                storage.addAttribute(.foregroundColor, value: quiet, range: NSRange(location: para.location, length: marker))
            }
            location = NSMaxRange(para)
            if para.length == 0 { break }
        }
    }

    static func headingLevel(_ line: String) -> Int? {
        for level in (1...3).reversed() where line.hasPrefix(String(repeating: "#", count: level) + " ") { return level }
        return nil
    }

    static func numberMarker(_ line: String) -> Int? {
        let digits = line.prefix(while: { $0.isASCII && $0.isNumber })
        guard (1...3).contains(digits.count) else { return nil }
        let rest = line.dropFirst(digits.count)
        guard rest.hasPrefix(". ") || rest.hasPrefix(") ") else { return nil }
        return digits.count + 2
    }

    private func restyleAll() {
        guard let storage = textView.textStorage else { return }
        loading = true
        storage.beginEditing()
        storage.addAttributes([.font: bodyFont, .paragraphStyle: bodyParagraph], range: NSRange(location: 0, length: storage.length))
        styleMarkdown(in: NSRange(location: 0, length: storage.length))
        storage.endEditing()
        loading = false
        textView.typingAttributes = baseAttributes
        render()
    }

    // ---- 文风 ----

    /// Style marks are worked out off the main thread, a beat after typing.
    private func checkStyle(now: Bool) {
        styleTask?.cancel()
        guard settings.style else {
            hits = []
            render()
            return
        }
        let text = textView.string
        styleTask = Task { [weak self] in
            if !now { try? await Task.sleep(nanoseconds: 150_000_000) }
            if Task.isCancelled { return }
            let found = await Task.detached(priority: .userInitiated) { DeskStyle.check(text, Set(StyleRule.allCases)) }.value
            guard let self, !Task.isCancelled, self.textView.string == text else { return }
            self.hits = found
            self.render()
        }
    }

    // ---- rendering attributes: 专注, 来源, 文风 ----

    /// Everything that only changes colour. Rendering attributes repaint the viewport and never touch the
    /// storage, so the cost doesn't grow with the essay.
    func render() {
        guard let layout = textView.textLayoutManager, let content = layout.textContentManager else { return }
        let whole = content.documentRange
        layout.removeRenderingAttribute(.foregroundColor, for: whole)
        layout.removeRenderingAttribute(.backgroundColor, for: whole)
        let length = (textView.string as NSString).length

        var focus: Range<Int>?
        if settings.focus != .off {
            let caret = textView.selectedRange().location
            let text = textView.string
            focus = settings.focus == .sentence ? DeskFocus.sentence(text, caret) : DeskFocus.paragraph(text, caret)
        }
        let dim: CGFloat = 0.28
        if focus != nil { layout.addRenderingAttribute(.foregroundColor, value: ink.withAlphaComponent(dim), for: whole) }

        // Pasted words keep their colour; outside the focus they fade like everything else.
        var pastedRuns: [Range<Int>] = []
        if settings.origins {
            var k = 0
            let n = min(origins.count, length)
            while k < n {
                guard origins[k] == .pasted else { k += 1; continue }
                var e = k
                while e < n, origins[e] == .pasted { e += 1 }
                pastedRuns.append(k..<e)
                k = e
            }
        }
        if let focus, !focus.isEmpty {
            add(layout, content, .foregroundColor, ink, focus)
            for run in pastedRuns {
                let inside = max(run.lowerBound, focus.lowerBound)..<max(max(run.lowerBound, focus.lowerBound), min(run.upperBound, focus.upperBound))
                if inside.isEmpty {
                    add(layout, content, .foregroundColor, pasted.withAlphaComponent(dim), run)
                } else {
                    if run.lowerBound < inside.lowerBound { add(layout, content, .foregroundColor, pasted.withAlphaComponent(dim), run.lowerBound..<inside.lowerBound) }
                    add(layout, content, .foregroundColor, pasted, inside)
                    if inside.upperBound < run.upperBound { add(layout, content, .foregroundColor, pasted.withAlphaComponent(dim), inside.upperBound..<run.upperBound) }
                }
            }
        } else {
            for run in pastedRuns { add(layout, content, .foregroundColor, pasted, run) }
        }
        if settings.style {
            for hit in hits where hit.end <= length { add(layout, content, .backgroundColor, styleMark, hit.start..<hit.end) }
        }
    }

    private func add(_ layout: NSTextLayoutManager, _ content: NSTextContentManager, _ key: NSAttributedString.Key, _ value: Any, _ r: Range<Int>) {
        guard let start = content.location(content.documentRange.location, offsetBy: r.lowerBound),
              let end = content.location(start, offsetBy: r.count),
              let range = NSTextRange(location: start, end: end) else { return }
        layout.addRenderingAttribute(key, value: value, for: range)
    }

    /// 打字机: the line being written stays a little above the middle of the window.
    func centreCaret() {
        guard let layout = textView.textLayoutManager, let content = layout.textContentManager else { return }
        let caret = textView.selectedRange().location
        guard let start = content.location(content.documentRange.location, offsetBy: caret) else { return }
        var lineRect: CGRect?
        layout.enumerateTextSegments(in: NSTextRange(location: start), type: .selection, options: []) { _, rect, _, _ in
            lineRect = rect
            return false
        }
        guard let rect = lineRect else { return }
        let visible = scrollView.contentView.bounds.height
        let y = rect.midY + textView.textContainerInset.height - visible * 0.45
        scrollView.contentView.scroll(to: NSPoint(x: 0, y: max(0, y)))
        scrollView.reflectScrolledClipView(scrollView.contentView)
    }

    // ---- shown blocks ----

    private func attachment(for block: Block) -> NSTextAttachment {
        let a = NSTextAttachment()
        a.image = ShownCard.image(for: block, width: min(Self.column, max(240, scrollView.contentSize.width - 64)))
        if let image = a.image { a.bounds = CGRect(origin: .zero, size: image.size) }
        return a
    }

    /// Photos are drawn once Notion's signed URL has been read (they expire after an hour, so a failure just
    /// leaves the card).
    private func loadPictures() {
        guard let id = essayId else { return }
        for block in shown where block.type == "image" {
            guard let s = block.url, let url = URL(string: s) else { continue }
            Task { [weak self] in
                guard let answer = try? await URLSession.shared.data(from: url), let image = NSImage(data: answer.0) else { return }
                guard let self, self.essayId == id else { return }
                self.place(image, for: block)
            }
        }
    }

    private func place(_ image: NSImage, for block: Block) {
        guard let k = shown.firstIndex(where: { $0.id == block.id }), let storage = textView.textStorage else { return }
        let ns = storage.string as NSString
        var seen = -1
        for i in 0..<ns.length where ns.character(at: i) == Self.attachmentChar {
            seen += 1
            guard seen == k else { continue }
            let a = NSTextAttachment()
            a.image = ShownCard.photo(image, caption: block.text, width: min(Self.column, max(240, scrollView.contentSize.width - 64)))
            if let img = a.image { a.bounds = CGRect(origin: .zero, size: img.size) }
            loading = true
            storage.addAttribute(.attachment, value: a, range: NSRange(location: i, length: 1))
            loading = false
            return
        }
    }
}

/// Cards drawn for the blocks the desk shows but doesn't edit.
enum ShownCard {
    static func label(for block: Block) -> (String, String) {
        switch block.type {
        case "image": return ("照片", block.text)
        case "file", "pdf", "video", "audio": return ("文件", block.text)
        case Block.divider: return ("", "")
        case "synced_block": return ("同步的涂鸦", "")
        case "table_of_contents": return ("目录", "")
        default:
            if NotionJSON.textTypes.contains(block.type) { return ("在 Notion 里改", block.text) }
            return ("Leaf 先不改它", block.type)
        }
    }

    static func image(for block: Block, width: CGFloat) -> NSImage {
        if block.type == Block.divider {
            return NSImage(size: NSSize(width: width, height: 24), flipped: false) { rect in
                NSColor.separatorColor.setFill()
                NSRect(x: rect.midX - 40, y: rect.midY, width: 80, height: 1).fill()
                return true
            }
        }
        let (kind, detail) = label(for: block)
        let height: CGFloat = detail.isEmpty ? 40 : 56
        return NSImage(size: NSSize(width: width, height: height), flipped: true) { rect in
            let card = NSBezierPath(roundedRect: rect.insetBy(dx: 0.5, dy: 4.5), xRadius: 10, yRadius: 10)
            NSColor.quaternaryLabelColor.withAlphaComponent(0.12).setFill()
            card.fill()
            let small: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 11, weight: .medium), .foregroundColor: NSColor.secondaryLabelColor]
            (kind as NSString).draw(at: NSPoint(x: 14, y: 11), withAttributes: small)
            if !detail.isEmpty {
                let p = NSMutableParagraphStyle()
                p.lineBreakMode = .byTruncatingTail
                let body: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13), .foregroundColor: NSColor.labelColor, .paragraphStyle: p]
                (detail.replacingOccurrences(of: "\n", with: " ") as NSString).draw(in: NSRect(x: 14, y: 28, width: rect.width - 28, height: 18), withAttributes: body)
            }
            return true
        }
    }

    static func photo(_ image: NSImage, caption: String, width: CGFloat) -> NSImage {
        let scale = min(1, width / max(1, image.size.width))
        var size = NSSize(width: image.size.width * scale, height: image.size.height * scale)
        if size.height > 420 { size = NSSize(width: size.width * 420 / size.height, height: 420) }
        let captionHeight: CGFloat = caption.isEmpty ? 0 : 26
        return NSImage(size: NSSize(width: width, height: size.height + captionHeight + 8), flipped: true) { rect in
            let frame = NSRect(x: (rect.width - size.width) / 2, y: 4, width: size.width, height: size.height)
            NSBezierPath(roundedRect: frame, xRadius: 8, yRadius: 8).addClip()
            image.draw(in: frame)
            NSGraphicsContext.current?.cgContext.resetClip()
            if !caption.isEmpty {
                let p = NSMutableParagraphStyle()
                p.alignment = .center
                (caption as NSString).draw(in: NSRect(x: 0, y: size.height + 10, width: rect.width, height: 18), withAttributes: [.font: NSFont.systemFont(ofSize: 12), .foregroundColor: NSColor.secondaryLabelColor, .paragraphStyle: p])
            }
            return true
        }
    }
}
