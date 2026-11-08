// swift-tools-version:5.9
// Leaf for Mac, step 0: an editor-only spike to measure whether a TextKit 2 NSTextView stays smooth on a
// long document with focus mode on, before the real app is built around it.
import PackageDescription

let package = Package(
    name: "EditorSpike",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "EditorSpike", path: "Sources/EditorSpike"),
    ]
)
