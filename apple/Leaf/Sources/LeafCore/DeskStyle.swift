import Foundation

// Ported from Android's ui/essay/DeskStyle.kt (Leaf PR #15). Offsets are UTF-16, like NSRange.

/// 文风检查, after iA Writer's Style Check but for Chinese: marks words a careful writer looks at twice,
/// never rewrites them. Every rule is a word list or a fixed-size pattern, so a long essay stays well
/// under a frame.
public enum StyleRule: String, CaseIterable, Codable, Sendable {
    case filler, degree, manyDe, long, punct

    public var label: String {
        switch self {
        case .filler: return "填充词：进行、其实、基本上…"
        case .degree: return "程度副词：非常、十分、极其…"
        case .manyDe: return "一句里「的」超过 3 个"
        case .long: return "长句：一句超过 60 字"
        case .punct: return "中文里夹了半角标点"
        }
    }
}

public struct StyleHit: Equatable, Sendable {
    public var start: Int
    public var end: Int
    public var rule: StyleRule
}

public enum DeskStyle {
    public static let fillers = ["某种程度上", "总的来说", "一般来说", "事实上", "可以说", "基本上", "其实", "进行", "的话"]
    public static let degrees = ["非常", "十分", "极其", "特别", "相当", "超级"]
    static let longSentence = 60
    static let deLimit = 3
    static let halfPunct: Set<UInt16> = Set(",;:?!".utf16)
    static let de: UInt16 = "的".utf16.first!

    static func isHan(_ c: UInt16) -> Bool { (0x4E00...0x9FFF).contains(c) || (0x3400...0x4DBF).contains(c) }

    static func isWhitespace(_ c: UInt16) -> Bool { Ch.isBlank(c) || c == Ch.newline }

    public static func check(_ text: String, _ rules: Set<StyleRule>) -> [StyleHit] {
        let t = text.u16
        if t.isEmpty || rules.isEmpty { return [] }
        var hits: [StyleHit] = []
        func words(_ list: [String], _ rule: StyleRule) {
            for w in list {
                let needle = w.u16
                var i = indexOf(t, needle, from: 0)
                while let at = i {
                    hits.append(StyleHit(start: at, end: at + needle.count, rule: rule))
                    i = indexOf(t, needle, from: at + needle.count)
                }
            }
        }
        if rules.contains(.filler) { words(fillers, .filler) }
        if rules.contains(.degree) { words(degrees, .degree) }
        if rules.contains(.manyDe) || rules.contains(.long) {
            var i = 0
            while i < t.count {
                let s = DeskFocus.sentence(t, i)
                if s.isEmpty { i += 1; continue }
                let body = t[s]
                if rules.contains(.manyDe), body.filter({ $0 == de }).count > deLimit {
                    for (k, c) in body.enumerated() where c == de {
                        hits.append(StyleHit(start: s.lowerBound + k, end: s.lowerBound + k + 1, rule: .manyDe))
                    }
                }
                if rules.contains(.long), body.first != Ch.hash, body.filter({ !isWhitespace($0) }).count > longSentence {
                    hits.append(StyleHit(start: s.lowerBound, end: s.upperBound, rule: .long))
                }
                i = s.upperBound + 1
            }
        }
        if rules.contains(.punct) {
            for k in t.indices where halfPunct.contains(t[k]) {
                let before = k > 0 && isHan(t[k - 1])
                let after = k + 1 < t.count && isHan(t[k + 1])
                if before || after { hits.append(StyleHit(start: k, end: k + 1, rule: .punct)) }
            }
        }
        // Stable, like Kotlin's sortedBy.
        return hits.enumerated().sorted { a, b in a.element.start != b.element.start ? a.element.start < b.element.start : a.offset < b.offset }.map(\.element)
    }

    static func indexOf(_ t: U16, _ needle: U16, from: Int) -> Int? {
        guard !needle.isEmpty, needle.count <= t.count else { return nil }
        var i = from
        while i + needle.count <= t.count {
            if t[i] == needle[0], t[i..<(i + needle.count)].elementsEqual(needle) { return i }
            i += 1
        }
        return nil
    }
}

/// 来源 (after iA Writer's Authorship): which words were typed here and which came in pasted. Kept on
/// this Mac per essay, by block, and never written into Notion.
public enum Origin: UInt8, Codable, Sendable {
    case typed = 0, pasted = 1

    public var code: Character { self == .typed ? "T" : "P" }
    public static func of(_ code: Character) -> Origin { code == "P" ? .pasted : .typed }
}

public struct OriginSpan: Codable, Equatable, Sendable {
    public var start: Int
    public var end: Int
    public var origin: Origin

    public init(start: Int, end: Int, origin: Origin) {
        self.start = start
        self.end = end
        self.origin = origin
    }
}

public enum DeskOrigins {
    /// The origins after an edit turned [before] into [after]; the inserted characters are [inserted].
    public static func splice(_ origins: [Origin], _ before: String, _ after: String, _ inserted: Origin) -> [Origin] {
        let b = before.u16
        let a = after.u16
        let (p, s) = commonEnds(b, a)
        let added = a.count - p - s
        let keep = Array(origins.prefix(p)) + Array(repeating: inserted, count: max(0, added)) + Array(origins.dropFirst(b.count - s))
        if keep.count == a.count { return keep }
        return (0..<a.count).map { $0 < keep.count ? keep[$0] : .typed }
    }

    public static func insertedLength(_ before: String, _ after: String) -> Int {
        let b = before.u16
        let a = after.u16
        let (p, s) = commonEnds(b, a)
        return a.count - p - s
    }

    static func commonEnds(_ b: U16, _ a: U16) -> (Int, Int) {
        var p = 0
        while p < b.count, p < a.count, b[p] == a[p] { p += 1 }
        var s = 0
        while s < b.count - p, s < a.count - p, b[b.count - 1 - s] == a[a.count - 1 - s] { s += 1 }
        return (p, s)
    }

    /// Spans for one block whose words sit at [start] in a run whose characters have [origins].
    public static func spans(_ origins: [Origin], _ start: Int, _ length: Int) -> [OriginSpan] {
        func at(_ k: Int) -> Origin { let i = start + k; return i >= 0 && i < origins.count ? origins[i] : .typed }
        var out: [OriginSpan] = []
        var k = 0
        while k < length {
            let o = at(k)
            if o == .typed { k += 1; continue }
            var e = k
            while e < length, at(e) == o { e += 1 }
            out.append(OriginSpan(start: k, end: e, origin: o))
            k = e
        }
        return out
    }

    /// Origins for a run's text, from each block's stored spans laid at its start.
    public static func lay(_ length: Int, _ starts: [Int], _ spans: [[OriginSpan]]) -> [Origin] {
        var out = [Origin](repeating: .typed, count: length)
        for (i, start) in starts.enumerated() {
            guard i < spans.count else { continue }
            for span in spans[i] {
                for k in span.start..<max(span.start, span.end) where start + k >= 0 && start + k < length {
                    out[start + k] = span.origin
                }
            }
        }
        return out
    }

    /// One line per block: id, a tab, then start-end-code triples. Same text format as Android.
    public static func encode(_ byBlock: [String: [OriginSpan]]) -> String {
        byBlock.filter { !$0.value.isEmpty }.sorted { $0.key < $1.key }.map { id, spans in
            id + "\t" + spans.map { "\($0.start)-\($0.end)-\($0.origin.code)" }.joined(separator: " ")
        }.joined(separator: "\n")
    }

    public static func decode(_ text: String) -> [String: [OriginSpan]] {
        var out: [String: [OriginSpan]] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let tab = line.firstIndex(of: "\t"), tab != line.startIndex else { continue }
            let spans = line[line.index(after: tab)...].split(separator: " ").compactMap { part -> OriginSpan? in
                let bits = part.split(separator: "-", omittingEmptySubsequences: false)
                guard bits.count == 3, let a = Int(bits[0]), let b = Int(bits[1]) else { return nil }
                return OriginSpan(start: a, end: b, origin: Origin.of(bits[2].first ?? "T"))
            }
            out[String(line[..<tab])] = spans
        }
        return out
    }
}
