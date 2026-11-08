import AppKit
import LeafCore
import SwiftUI

@main
struct LeafMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup("Leaf") {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.desk)
                .frame(minWidth: 760, minHeight: 480)
        }
        .windowToolbarStyle(.unifiedCompact)
        .commands { LeafCommands(model: model, desk: model.desk) }

        Settings {
            SettingsView()
                .environmentObject(model)
                .environmentObject(model.desk)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // `swift run` starts a bare process: make it a regular app with a Dock icon and menus.
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        Fonts.registerAll()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

/// Menus and shortcuts. Same keys as the Android desk with ⌘ for Ctrl: ⌘D cycles focus, ⌘T typewriter,
/// ⌘1/2/3 font, ⇧⌘L style check, ⇧⌘O origins, ⌘S save now.
struct LeafCommands: Commands {
    let model: AppModel
    @ObservedObject var desk: DeskSettings

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("新随笔") { model.newEssay() }.keyboardShortcut("n")
        }
        CommandGroup(replacing: .saveItem) {
            Button("立即保存") { model.saveNow() }.keyboardShortcut("s")
            Button("同步") { Task { await model.runSync() } }.keyboardShortcut("r")
        }
        CommandMenu("写作台") {
            Picker("专注", selection: $desk.focus) {
                ForEach(DeskSettings.Focus.allCases) { Text($0.label).tag($0) }
            }
            Button("切换专注") { desk.focus = desk.focus.next }.keyboardShortcut("d")
            Toggle("打字机", isOn: $desk.typewriter).keyboardShortcut("t")
            Divider()
            Button("衬线") { desk.font = .serif }.keyboardShortcut("1")
            Button("无衬线") { desk.font = .sans }.keyboardShortcut("2")
            Button("等宽") { desk.font = .mono }.keyboardShortcut("3")
            Button("放大") { desk.textSize = min(28, desk.textSize + 1) }.keyboardShortcut("=")
            Button("缩小") { desk.textSize = max(12, desk.textSize - 1) }.keyboardShortcut("-")
            Divider()
            Toggle("文风", isOn: $desk.style).keyboardShortcut("l", modifiers: [.command, .shift])
            Toggle("来源", isOn: $desk.origins).keyboardShortcut("o", modifiers: [.command, .shift])
        }
    }
}
