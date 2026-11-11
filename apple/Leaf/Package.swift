// swift-tools-version:5.9
// Leaf for Mac. LeafCore is the shared, UI-free part (Markdown <-> Notion blocks, focus, style check,
// origins, the Notion client, the local store and sync) so iPhone/iPad can reuse it later. LeafMac is the
// SwiftUI app with a TextKit 2 editor. No third-party dependencies.
import PackageDescription

let package = Package(
    name: "Leaf",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "LeafCore", targets: ["LeafCore"]),
        .executable(name: "LeafMac", targets: ["LeafMac"]),
    ],
    targets: [
        .target(name: "LeafCore", path: "Sources/LeafCore"),
        .executableTarget(
            name: "LeafMac",
            dependencies: ["LeafCore"],
            path: "Sources/LeafMac"
        ),
        .testTarget(name: "LeafCoreTests", dependencies: ["LeafCore"], path: "Tests/LeafCoreTests"),
    ]
)
