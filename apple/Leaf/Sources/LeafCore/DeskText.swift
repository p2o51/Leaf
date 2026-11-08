import Foundation

// Ported from Android's ui/essay/DeskText.kt (Leaf PR #15), which is the reference: same rules, same
// tests (DeskTextTests). Every offset here is in UTF-16 code units, like Kotlin's String and NSTextView's
// NSRange, so a position means the same thing on both platforms and in the text view.

typealias U16 = [UInt16]

extension String {
    var u16: U16 { Array(utf16) }
}

func string<S: Sequence>(_ units: S) -> String where S.Element == UInt16 {
    String(decoding: Array(units), as: UTF16.self)
}

enum Ch {
    static let newline: UInt16 = 0x0A
    static let space: UInt16 = 0x20
    static let hash: UInt16 = 0x23
    static let dash: UInt16 = 0x2D
    static let star: UInt16 = 0x2A
    static let plus: UInt16 = 0x2B
    static let dot: UInt16 = 0x2E
    static let paren: UInt16 = 0x29
    static let gt: UInt16 = 0x3E
    static let tab: UInt16 = 0x09
    static let cr: UInt16 = 0x0D

    static func isDigit(_ c: UInt16) -> Bool { c >= 0x30 && c <= 0x39 }
    static func isBlank(_ c: UInt16) -> Bool { c == space || c == tab || c == cr || c == 0x3000 || c == 0x0B || c == 0x0C }
}

/// One block parsed out of a run's text, and where its words sit in that text (for 来源 marks).
public struct DeskLine: Equatable, Sendable {
    public var type: String
    public var text: String
    public var textStart: Int
}

/// The run's text and, for each block in it, where that block's words start.
public struct DeskRendered: Equatable, Sendable {
    public var text: String
    public var starts: [Int]
}

/// A piece of the 写作台 page: a run of text blocks edited together as Markdown, or a block only shown.
public enum DeskPart: Equatable, Sendable {
    case text(key: String, blocks: [Block])
    case shown(Block)

    public var key: String {
        switch self {
        case .text(let key, _): return key
        case .shown(let block): return block.id
        }
    }
}

/// Markdown in, Notion blocks out. A blank line ends a block; a single line break stays inside it (Notion
/// keeps it as a soft break). List items and headings start their own block even without a blank line.
/// `#tag` without a space stays text: Leaf's scribble tags are written that way.
public enum DeskText {
    public static let editableTypes: Set<String> = ["paragraph", "heading_1", "heading_2", "heading_3", "quote", "bulleted_list_item", "numbered_list_item"]
    static let listTypes: Set<String> = ["bulleted_list_item", "numbered_list_item"]

    public static func editable(_ block: Block) -> Bool {
        !block.fixed && editableTypes.contains(block.type) && roundTrips(block)
    }

    /// Whether the block reads back as itself from its Markdown. A paragraph starting `# ` would become a
    /// heading the first time its run is edited, so the 写作台 shows it, unedited, instead.
    static func roundTrips(_ block: Block) -> Bool {
        if block.text.isEmpty { return true }
        let lines = parse(prefix(block.type, 1) + block.text)
        return lines.count == 1 && lines[0].type == block.type && lines[0].text == block.text
    }

    /// The page as runs and shown blocks. A run always follows the last shown block, so there is
    /// somewhere to keep writing.
    public static func parts(_ blocks: [Block]) -> [DeskPart] {
        var out: [DeskPart] = []
        var run: [Block] = []
        var key = "run:start"
        for b in blocks {
            if editable(b) {
                run.append(b)
            } else {
                out.append(.text(key: key, blocks: run))
                out.append(.shown(b))
                run = []
                key = "run:after:\(b.id)"
            }
        }
        out.append(.text(key: key, blocks: run))
        let last = out.count - 1
        return out.enumerated().filter { i, p in
            if case .text(_, let blocks) = p { return !blocks.isEmpty || i == last }
            return true
        }.map(\.element)
    }

    public static func blocks(_ parts: [DeskPart]) -> [Block] {
        parts.flatMap { part -> [Block] in
            switch part {
            case .text(_, let blocks): return blocks
            case .shown(let block): return [block]
            }
        }
    }

    public static func prefix(_ type: String, _ number: Int) -> String {
        switch type {
        case "heading_1": return "# "
        case "heading_2": return "## "
        case "heading_3": return "### "
        case "quote": return "> "
        case "bulleted_list_item": return "- "
        case "numbered_list_item": return "\(number). "
        default: return ""
        }
    }

    public static func render(_ blocks: [Block]) -> DeskRendered {
        var out = U16()
        var starts: [Int] = []
        var number = 0
        for (i, b) in blocks.enumerated() {
            if i > 0 {
                let tight = listTypes.contains(b.type) && listTypes.contains(blocks[i - 1].type)
                out.append(contentsOf: (tight ? "\n" : "\n\n").u16)
            }
            number = b.type == "numbered_list_item" ? number + 1 : 0
            out.append(contentsOf: prefix(b.type, number).u16)
            starts.append(out.count)
            out.append(contentsOf: b.text.u16)
        }
        return DeskRendered(text: string(out), starts: starts)
    }

    // Markers, matched by hand rather than with regular expressions so every offset stays in UTF-16.

    static func heading(_ line: ArraySlice<UInt16>) -> Int? {
        var n = 0
        var i = line.startIndex
        while i < line.endIndex, line[i] == Ch.hash, n < 4 { n += 1; i += 1 }
        guard (1...3).contains(n), i < line.endIndex, line[i] == Ch.space else { return nil }
        return n
    }

    static func bullet(_ line: ArraySlice<UInt16>) -> Bool {
        guard line.count >= 2 else { return false }
        let c = line[line.startIndex]
        return (c == Ch.dash || c == Ch.star || c == Ch.plus) && line[line.startIndex + 1] == Ch.space
    }

    /// Length of a `12. ` / `3) ` marker, or nil.
    static func numbered(_ line: ArraySlice<UInt16>) -> Int? {
        var i = line.startIndex
        var digits = 0
        while i < line.endIndex, Ch.isDigit(line[i]), digits < 4 { digits += 1; i += 1 }
        guard (1...3).contains(digits) else { return nil }
        guard i < line.endIndex, line[i] == Ch.dot || line[i] == Ch.paren else { return nil }
        guard i + 1 < line.endIndex, line[i + 1] == Ch.space else { return nil }
        return digits + 2
    }

    /// Length of a `> ` or `>` quote marker, or nil.
    static func quote(_ line: ArraySlice<UInt16>) -> Int? {
        guard let first = line.first, first == Ch.gt else { return nil }
        if line.count > 1, line[line.startIndex + 1] == Ch.space { return 2 }
        return 1
    }

    static func startsBlock(_ line: ArraySlice<UInt16>) -> Bool {
        heading(line) != nil || bullet(line) || numbered(line) != nil
    }

    static func isBlankLine(_ line: ArraySlice<UInt16>) -> Bool {
        line.allSatisfy(Ch.isBlank)
    }

    /// The blocks a run's text holds, in order, with where each one's words start.
    public static func parse(_ text: String) -> [DeskLine] {
        parse(text.u16)
    }

    static func parse(_ units: U16) -> [DeskLine] {
        var out: [DeskLine] = []
        var type = ""
        var start = -1
        var words = U16()
        func flush() {
            if start >= 0 { out.append(DeskLine(type: type, text: string(words), textStart: start)) }
            start = -1
            words.removeAll(keepingCapacity: true)
        }
        var lineStart = 0
        while lineStart <= units.count {
            var end = lineStart
            while end < units.count, units[end] != Ch.newline { end += 1 }
            let line = units[lineStart..<end]
            defer { lineStart = end + 1 }
            if isBlankLine(line) {
                flush()
                continue
            }
            if start >= 0, !startsBlock(line) {
                // A soft break inside the block; a quote's later lines may repeat the marker.
                let marker = type == "quote" ? (quote(line) ?? 0) : 0
                words.append(Ch.newline)
                words.append(contentsOf: line.dropFirst(marker))
                continue
            }
            flush()
            let t: String
            let marker: Int
            if let level = heading(line) {
                t = "heading_\(level)"; marker = level + 1
            } else if bullet(line) {
                t = "bulleted_list_item"; marker = 2
            } else if let n = numbered(line) {
                t = "numbered_list_item"; marker = n
            } else if let q = quote(line) {
                t = "quote"; marker = q
            } else {
                t = "paragraph"; marker = 0
            }
            type = t
            start = lineStart + marker
            words.append(contentsOf: line.dropFirst(marker))
        }
        flush()
        return out
    }

    /// The run's blocks after its text became [text]: unchanged and edited blocks keep their ids (so
    /// Notion updates them in place), new ones get [newId]. Returns the blocks and the ids that are gone.
    public static func update(_ old: [Block], _ text: String, newId: () -> String) -> (blocks: [Block], gone: Set<String>) {
        let lines = parse(text)
        let pairs = match(old.map { Pair(type: $0.type, text: $0.text) }, lines.map { Pair(type: $0.type, text: $0.text) })
        var used = Set<Int>()
        let blocks = lines.enumerated().map { j, line -> Block in
            if let i = pairs[j] {
                used.insert(i)
                var b = old[i]
                b.text = line.text
                b.type = line.type
                return b
            }
            return Block(id: newId(), type: line.type, text: line.text)
        }
        let gone = Set(old.indices.filter { !used.contains($0) }.map { old[$0].id })
        return (blocks, gone)
    }

    struct Pair: Equatable {
        var type: String
        var text: String
    }

    /// For each new item, the old one it continues, or nil for a new one. Equal items are matched first
    /// (longest common subsequence); in each gap the leftovers pair up in order, so an edited paragraph
    /// keeps its block rather than becoming a delete plus a create.
    public static func match<T: Equatable>(_ old: [T], _ new: [T]) -> [Int?] {
        let n = old.count
        let m = new.count
        var lcs = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        if n > 0, m > 0 {
            for i in stride(from: n - 1, through: 0, by: -1) {
                for j in stride(from: m - 1, through: 0, by: -1) {
                    lcs[i][j] = old[i] == new[j] ? lcs[i + 1][j + 1] + 1 : max(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }
        var result = [Int?](repeating: nil, count: m)
        var anchors: [(Int, Int)] = []
        var i = 0
        var j = 0
        while i < n, j < m {
            if old[i] == new[j] {
                anchors.append((i, j)); i += 1; j += 1
            } else if lcs[i + 1][j] >= lcs[i][j + 1] {
                i += 1
            } else {
                j += 1
            }
        }
        for (a, b) in anchors { result[b] = a }
        var prevOld = -1
        var prevNew = -1
        for (a, b) in anchors + [(n, m)] {
            var x = prevOld + 1
            var y = prevNew + 1
            while x < a, y < b { result[y] = x; x += 1; y += 1 }
            prevOld = a
            prevNew = b
        }
        return result
    }
}

/// Sentence and paragraph spans for 专注, over a run's Markdown text. Ranges are half-open UTF-16 offsets.
public enum DeskFocus {
    static let enders: Set<UInt16> = Set("。！？!?…\n".utf16)
    static let closers: Set<UInt16> = Set("”」』）)\"'".utf16)

    static func endsSentence(_ t: U16, _ i: Int) -> Bool {
        let c = t[i]
        if enders.contains(c) { return true }
        // A Latin full stop ends a sentence only before a space or the end, not inside 3.14 or e.g.
        return c == Ch.dot && (i + 1 >= t.count || t[i + 1] == Ch.space || t[i + 1] == Ch.newline)
    }

    public static func sentence(_ text: String, _ offset: Int) -> Range<Int> {
        sentence(text.u16, offset)
    }

    static func sentence(_ t: U16, _ offset: Int) -> Range<Int> {
        if t.isEmpty { return 0..<0 }
        let at = min(max(offset, 0), t.count)
        var a = at
        while a > 0, !endsSentence(t, a - 1) { a -= 1 }
        while a < t.count, t[a] == Ch.space { a += 1 }
        var b = at
        while b < t.count, !endsSentence(t, b) { b += 1 }
        if b < t.count, t[b] != Ch.newline { b += 1 }
        while b < t.count, closers.contains(t[b]) { b += 1 }
        return a..<max(a, b)
    }

    /// The block around [offset]: between blank lines, a list item being its own line.
    public static func paragraph(_ text: String, _ offset: Int) -> Range<Int> {
        let t = text.u16
        if t.isEmpty { return 0..<0 }
        let at = min(max(offset, 0), t.count)
        guard let line = DeskText.parse(t).last(where: { $0.textStart <= at }) else { return 0..<0 }
        var head = line.textStart
        while head > 0, t[head - 1] != Ch.newline { head -= 1 }
        return head..<(line.textStart + line.text.utf16.count)
    }
}
