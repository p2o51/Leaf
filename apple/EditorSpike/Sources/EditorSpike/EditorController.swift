import AppKit

enum FocusMode { case off, sentence, paragraph }

/// One TextKit 2 text view and the two writing aids that matter most for smoothness.
///
/// Focus mode never touches the text storage: it sets *rendering attributes* on the text layout manager,
/// which only repaints. Changing the storage instead would re-run layout for every dimmed range on each
/// caret move, which is exactly the kind of work that makes wrapped SwiftUI editors stutter.
final class EditorController: NSObject, NSTextViewDelegate {
    let scrollView: NSScrollView
    let textView: NSTextView
    private(set) var focus: FocusMode = .off
    private(set) var typewriter = false

    private let ink = NSColor.textColor
    private let dim = NSColor.textColor.withAlphaComponent(0.28)

    init(text: String) {
        // usingTextLayoutManager: true = TextKit 2 (NSTextLayoutManager + NSTextContentStorage).
        textView = NSTextView(usingTextLayoutManager: true)
        scrollView = NSScrollView()
        super.init()

        scrollView.hasVerticalScroller = true
        scrollView.drawsBackground = true
        scrollView.backgroundColor = .textBackgroundColor
        scrollView.documentView = textView

        textView.autoresizingMask = [.width]
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.textContainer?.widthTracksTextView = false
        textView.textContainer?.containerSize = NSSize(width: 640, height: CGFloat.greatestFiniteMagnitude)
        textView.textContainerInset = NSSize(width: 0, height: 80)
        textView.isRichText = false
        textView.allowsUndo = true
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isAutomaticSpellingCorrectionEnabled = false
        textView.delegate = self

        let paragraph = NSMutableParagraphStyle()
        paragraph.lineHeightMultiple = 1.5
        paragraph.paragraphSpacing = 10
        textView.defaultParagraphStyle = paragraph
        textView.font = Fonts.body(size: 17)
        textView.textColor = ink
        textView.typingAttributes = [.font: Fonts.body(size: 17), .foregroundColor: ink, .paragraphStyle: paragraph]
        textView.string = text
        textView.setSelectedRange(NSRange(location: (text as NSString).length / 2, length: 0))

        scrollView.postsFrameChangedNotifications = true
        NotificationCenter.default.addObserver(self, selector: #selector(recenter), name: NSView.frameDidChangeNotification, object: scrollView)
    }

    /// Keep the 640 pt text column centred however wide the window is.
    @objc func recenter() {
        let width = scrollView.contentSize.width
        textView.textContainerInset = NSSize(width: max(24, (width - 640) / 2), height: 80)
    }

    @objc func focusOff() { setFocus(.off) }
    @objc func focusSentence() { setFocus(.sentence) }
    @objc func focusParagraph() { setFocus(.paragraph) }
    @objc func toggleTypewriter() {
        typewriter.toggle()
        if typewriter { centreCaret() }
    }

    func setFocus(_ mode: FocusMode) {
        focus = mode
        applyFocus()
    }

    func textViewDidChangeSelection(_ notification: Notification) {
        applyFocus()
        if typewriter { centreCaret() }
    }

    /// Dim everything, then un-dim the sentence or paragraph under the caret. Only the viewport is drawn,
    /// so the cost does not grow with the document.
    func applyFocus() {
        guard let layout = textView.textLayoutManager, let content = layout.textContentManager else { return }
        let whole = content.documentRange
        layout.removeRenderingAttribute(.foregroundColor, for: whole)
        guard focus != .off else { return }
        layout.addRenderingAttribute(.foregroundColor, value: dim, for: whole)
        let caret = textView.selectedRange()
        let ns = textView.string as NSString
        let span = focus == .paragraph ? ns.paragraphRange(for: caret) : Sentences.range(in: ns, around: caret.location)
        if let range = textRange(span, in: content) {
            layout.addRenderingAttribute(.foregroundColor, value: ink, for: range)
        }
    }

    /// Typewriter scrolling: the caret line stays at the middle of the window.
    func centreCaret() {
        guard let layout = textView.textLayoutManager, let content = layout.textContentManager else { return }
        let caret = textView.selectedRange()
        guard let start = content.location(content.documentRange.location, offsetBy: caret.location) else { return }
        var lineRect: CGRect?
        layout.enumerateTextSegments(in: NSTextRange(location: start), type: .selection, options: []) { _, rect, _, _ in
            lineRect = rect
            return false
        }
        guard let rect = lineRect else { return }
        let visible = scrollView.contentView.bounds.height
        let y = rect.midY + textView.textContainerInset.height - visible / 2
        scrollView.contentView.scroll(to: NSPoint(x: 0, y: max(0, y)))
        scrollView.reflectScrolledClipView(scrollView.contentView)
    }

    private func textRange(_ range: NSRange, in content: NSTextContentManager) -> NSTextRange? {
        guard let start = content.location(content.documentRange.location, offsetBy: range.location),
              let end = content.location(start, offsetBy: range.length) else { return nil }
        return NSTextRange(location: start, end: end)
    }
}

enum Sentences {
    /// Sentence ends for mixed Chinese and English: 。！？!? and a full stop followed by a space, plus
    /// line breaks. Good enough for a spike; the real editor will use the editor thread's rules.
    static func range(in text: NSString, around location: Int) -> NSRange {
        let enders = CharacterSet(charactersIn: "。！？!?\n")
        var start = min(location, text.length)
        while start > 0 {
            let c = text.character(at: start - 1)
            if let scalar = UnicodeScalar(c), enders.contains(scalar) { break }
            if c == 0x2E, start < text.length, text.character(at: start) == 0x20 { break }
            start -= 1
        }
        var end = min(location, text.length)
        while end < text.length {
            let c = text.character(at: end)
            end += 1
            if let scalar = UnicodeScalar(c), enders.contains(scalar) { break }
            if c == 0x2E, end < text.length, text.character(at: end) == 0x20 { break }
        }
        return NSRange(location: start, length: end - start)
    }
}

enum Fonts {
    static func body(size: CGFloat) -> NSFont {
        for name in ["iAWriterDuoS-Regular", "iAWriterDuoV", "iAWriterMonoS-Regular"] {
            if let font = NSFont(name: name, size: size) { return font }
        }
        return NSFont.monospacedSystemFont(ofSize: size, weight: .regular)
    }
}
