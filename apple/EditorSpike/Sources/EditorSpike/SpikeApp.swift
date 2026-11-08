import AppKit

final class SpikeAppDelegate: NSObject, NSApplicationDelegate {
    private let bench: Bool
    private var window: NSWindow!
    private var editor: EditorController!

    init(bench: Bool) {
        self.bench = bench
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        editor = EditorController(text: SampleText.make(characters: 50_000))
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 900, height: 760),
            styleMask: [.titled, .closable, .resizable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.titlebarAppearsTransparent = true
        window.title = "Leaf · editor spike"
        window.contentView = editor.scrollView
        window.center()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(editor.textView)
        editor.recenter()
        NSApp.mainMenu = makeMenu()
        NSApp.activate(ignoringOtherApps: true)

        if bench {
            // Let the first layout pass and the window server settle before measuring.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [editor] in
                guard let editor else { return }
                print(Bench.run(on: editor))
                NSApp.terminate(nil)
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    private func makeMenu() -> NSMenu {
        let main = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)

        let viewItem = NSMenuItem()
        let view = NSMenu(title: "View")
        func add(_ title: String, _ key: String, _ action: Selector) {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            item.target = editor
            view.addItem(item)
        }
        add("Focus: Off", "0", #selector(EditorController.focusOff))
        add("Focus: Sentence", "1", #selector(EditorController.focusSentence))
        add("Focus: Paragraph", "2", #selector(EditorController.focusParagraph))
        add("Typewriter Scrolling", "t", #selector(EditorController.toggleTypewriter))
        viewItem.submenu = view
        main.addItem(viewItem)
        return main
    }
}
