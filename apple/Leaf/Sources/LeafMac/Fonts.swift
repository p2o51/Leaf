import AppKit
import CoreText

/// The 写作台's three faces: IBM Plex Serif, Sans and Mono (OFL), bundled with the app. Not iA Writer's
/// fonts: their README asks text editors not to ship them or use them as the default. Chinese falls back
/// to the system's CJK face (PingFang SC), which the system cascade picks per character.
enum DeskFont: String, CaseIterable, Identifiable {
    case serif, sans, mono
    var id: String { rawValue }

    var label: String {
        switch self {
        case .serif: return "衬线"
        case .sans: return "无衬线"
        case .mono: return "等宽"
        }
    }

    var file: String {
        switch self {
        case .serif: return "ibm_plex_serif"
        case .sans: return "ibm_plex_sans"
        case .mono: return "ibm_plex_mono"
        }
    }
}

enum Fonts {
    private static var faces: [String: NSFont] = [:]
    private static var registered = false

    /// Where the bundled fonts are: inside Leaf.app, or, when run with `swift run`, next to the package.
    static func directory() -> URL? {
        let fm = FileManager.default
        if let res = Bundle.main.resourceURL?.appendingPathComponent("Fonts"), fm.fileExists(atPath: res.path) { return res }
        var dir = Bundle.main.executableURL?.deletingLastPathComponent()
        for _ in 0..<6 {
            guard let d = dir else { break }
            let candidate = d.appendingPathComponent("Resources/Fonts")
            if fm.fileExists(atPath: candidate.path) { return candidate }
            dir = d.deletingLastPathComponent()
        }
        return nil
    }

    static func registerAll() {
        guard !registered else { return }
        registered = true
        guard let dir = directory(), let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for url in files where url.pathExtension == "ttf" {
            // Descriptors straight from the file, so nothing depends on guessing PostScript names.
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            if let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let d = descriptors.first {
                faces[url.deletingPathExtension().lastPathComponent] = CTFontCreateWithFontDescriptor(d, 17, nil) as NSFont
            }
        }
    }

    static func body(_ face: DeskFont, size: CGFloat, bold: Bool = false) -> NSFont {
        registerAll()
        let key = face.file + (bold ? "_600" : "_400")
        if let base = faces[key], let font = NSFont(descriptor: base.fontDescriptor, size: size) { return font }
        switch face {
        case .mono: return NSFont.monospacedSystemFont(ofSize: size, weight: bold ? .semibold : .regular)
        case .serif:
            let d = NSFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular).fontDescriptor.withDesign(.serif)
            return d.flatMap { NSFont(descriptor: $0, size: size) } ?? NSFont.systemFont(ofSize: size)
        case .sans: return NSFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
        }
    }
}
