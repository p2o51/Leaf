import AppKit
import CoreGraphics
import LeafCore
import SwiftUI

/// `LeafMac --shots <dir>`: fills a throwaway library with sample essays, drives the window through the
/// main screens and desk modes, saves one PNG per step into <dir>, writes checks.txt, and quits. Used to
/// review the Mac app without Notion and without touching the real library.
@MainActor
enum Shots {
    static let dir: URL? = {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--shots"), i + 1 < args.count else { return nil }
        return URL(fileURLWithPath: (args[i + 1] as NSString).expandingTildeInPath, isDirectory: true)
    }()

    private static var checks: [String] = []

    private static func check(_ ok: Bool, _ what: String) {
        let line = "\(ok ? "PASS" : "FAIL") \(what)"
        checks.append(line)
        print("CHECK \(line)")
    }

    private static func wait(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    static func run(model: AppModel, into dir: URL, openSettings: @escaping () -> Void) async {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        await wait(1.0)
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil && !($0 is NSPanel) }) else {
            print("CHECK FAIL no window"); NSApp.terminate(nil); return
        }
        NSApp.appearance = NSAppearance(named: .aqua)
        window.setFrame(NSRect(x: 80, y: 80, width: 1280, height: 820), display: true)
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)

        let ids = await seed(model.store, picture: dir.appendingPathComponent(".sample-photo.png"))
        await wait(0.8)
        check(model.essays.count == ids.count, "样例随笔都进了列表（\(model.essays.count) 篇）")

        // 1. List + desk
        model.shelf = .all
        model.selected = ids[0]
        await wait(1.6)
        guard let desk = model.openDesk else {
            check(false, "写作台打开了"); finish(dir); return
        }
        check(desk.textView.string.hasPrefix("在雨里走回家"), "打开第一篇，第一行是标题")
        await shot(window, dir, "01-列表与写作台")

        // 2. Focus: sentence, then paragraph
        model.desk.focus = .sentence
        desk.placeCaret(after: "伞骨在风里")
        await wait(0.6)
        check(desk.focusRange.map { $0.count > 0 && $0.count < 80 } ?? false, "句专注只亮一句（\(desk.focusRange?.count ?? 0) 字符）")
        await shot(window, dir, "02-专注-句")
        model.desk.focus = .paragraph
        await wait(0.6)
        check((desk.focusRange?.count ?? 0) > 80, "段专注亮整段（\(desk.focusRange?.count ?? 0) 字符）")
        await shot(window, dir, "03-专注-段")
        model.desk.focus = .off

        // 3. 文风
        model.desk.style = true
        await wait(1.2)
        check(desk.styleHitCount >= 4, "文风标出了 \(desk.styleHitCount) 处")
        await shot(window, dir, "04-文风")
        model.desk.style = false

        // 4. Typing and pasting, 来源, write after the pause
        desk.placeCaret(after: "我没有回头。")
        desk.insert("\n\n这一段是刚才打进去的字，", pasted: false)
        desk.insert("而这一句是从别处粘贴来的。", pasted: true)
        model.desk.origins = true
        await wait(1.4)
        check(desk.pastedCount > 0, "粘贴的字记成「粘贴」来源（\(desk.pastedCount) 字符）")
        let saved = await model.store.blocks(ids[0])
        check(saved.contains { $0.text.contains("刚才打进去的字") }, "停笔 0.6 秒后写进本地库")
        let origins = await model.store.origins(ids[0])
        check(!origins.isEmpty, "来源记录存到本机")
        await shot(window, dir, "05-来源-粘贴")

        // 5. Fonts
        model.desk.font = .sans
        await wait(0.8)
        await shot(window, dir, "06-字体-无衬线")
        model.desk.font = .mono
        await wait(0.8)
        await shot(window, dir, "07-字体-等宽")
        model.desk.font = .serif

        // 6. Typewriter
        model.desk.typewriter = true
        model.desk.focus = .sentence
        desk.placeCaret(after: "路灯一盏一盏地亮起来")
        await wait(1.0)
        await shot(window, dir, "08-打字机加句专注")
        model.desk.typewriter = false
        model.desk.focus = .off

        // 7. Blocks the desk shows but doesn't edit, lists, quotes
        model.selected = ids[1]
        await wait(2.0)
        check(model.openDesk?.textView.string.hasPrefix("读《") ?? false, "切换到第二篇")
        await shot(window, dir, "09-标题列表引用与照片")

        // 8. Tag and colour filters
        model.shelf = .tag("读书")
        await wait(0.8)
        check(model.shown.count == 2, "标签「读书」筛出 \(model.shown.count) 篇")
        await shot(window, dir, "10-标签筛选")
        model.shelf = .color(.blue)
        await wait(0.8)
        await shot(window, dir, "11-颜色筛选")

        // 9. Wall
        model.shelf = .wall
        await wait(1.0)
        check(model.scribbles.count >= 4, "涂鸦墙有 \(model.scribbles.count) 条")
        await shot(window, dir, "12-涂鸦墙")

        // 10. Search
        model.shelf = .all
        model.search = "咖啡"
        await wait(0.8)
        check(model.shown.count == 1, "搜「咖啡」找到 \(model.shown.count) 篇")
        await shot(window, dir, "13-搜索")
        model.search = ""

        // 11. Dark
        NSApp.appearance = NSAppearance(named: .darkAqua)
        model.selected = ids[0]
        await wait(1.6)
        await shot(window, dir, "14-深色-写作台")
        model.desk.focus = .sentence
        model.openDesk?.placeCaret(after: "伞骨在风里")
        model.desk.style = true
        await wait(1.2)
        await shot(window, dir, "15-深色-句专注加文风")
        model.desk.focus = .off
        model.desk.style = false
        model.shelf = .wall
        await wait(1.0)
        await shot(window, dir, "16-深色-涂鸦墙")
        NSApp.appearance = NSAppearance(named: .aqua)

        // 12. New essay (⌘N)
        model.shelf = .all
        let before = model.essays.count
        model.newEssay()
        await wait(1.6)
        if let fresh = model.openDesk {
            // A new essay opens with the caret on the (empty) title line, as for the writer.
            fresh.textView.window?.makeFirstResponder(fresh.textView)
            fresh.insert("新的一篇\n\n从这里开始写。", pasted: false)
        }
        await wait(1.4)
        check(model.essays.count == before + 1 && model.essays.contains { $0.title == "新的一篇" }, "⌘N 新建并写入标题")
        await shot(window, dir, "17-新建随笔")

        // 13. Settings
        openSettings()
        await wait(1.4)
        if let settings = NSApp.windows.first(where: { $0 !== window && $0.isVisible && $0.contentView != nil && $0.frame.width > 200 }) {
            await shot(settings, dir, "18-设置")
        } else {
            check(false, "设置窗口打开了")
        }
        finish(dir)
    }

    private static func finish(_ dir: URL) {
        try? (checks.joined(separator: "\n") + "\n").write(to: dir.appendingPathComponent("checks.txt"), atomically: true, encoding: .utf8)
        print("SHOTS DONE \(dir.path)")
        NSApp.terminate(nil)
    }

    /// The real on-screen pixels when the process may record the screen, otherwise AppKit's own drawing
    /// of the window (close, but materials and vibrancy can look flatter).
    private static func shot(_ window: NSWindow, _ dir: URL, _ name: String) async {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.displayIfNeeded()
        await wait(0.3)
        let url = dir.appendingPathComponent(name + ".png")
        if CGPreflightScreenCaptureAccess() {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
            p.arguments = ["-x", "-o", "-l", String(window.windowNumber), url.path]
            try? p.run()
            p.waitUntilExit()
            if FileManager.default.fileExists(atPath: url.path) { print("SHOT \(name) screen"); return }
        }
        guard let view = window.contentView?.superview ?? window.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
        print("SHOT \(name) cached")
    }

    // ---- sample library ----

    private static func seed(_ store: Store, picture: URL) async -> [String] {
        drawPhoto(to: picture)
        let now = Date().millis
        let minute: Int64 = 60_000
        var n = 0
        func b(_ type: String, _ text: String, fixed: Bool = false, url: String? = nil) -> Block {
            n += 1
            return Block(id: "b\(n)", notionId: nil, type: type, text: text, fixed: fixed, url: url)
        }
        let essays: [(EssayMeta, [Block])] = [
            (EssayMeta(id: "e1", title: "在雨里走回家", color: .blue, pinned: true, editedAt: now - 2 * minute, tags: ["随笔", "城市"],
                       preview: "下班的时候雨下得很大", bodyLoaded: true),
             [b("paragraph", "下班的时候雨下得很大，我其实可以等一等，但还是撑开伞走进去了。伞骨在风里弯成一个奇怪的角度，像一只想飞又飞不起来的鸟。路灯一盏一盏地亮起来，照着积水里破碎的的倒影。"),
              b("paragraph", "我想起小时候住的那条巷子。那时候的雨好像更大一些，也更慢一些，我们会非常认真地在屋檐下看水从瓦片上流下来,一看就是一个下午。基本上没有人催我们回家，大人们也在看雨。"),
              b("heading_2", "后来"),
              b("paragraph", "后来巷子拆了，变成了一条很宽的马路。我十分确定自己再也找不到那块青石板了。走到楼下的时候，我回头看了一眼来时的路。我没有回头。"),
             ]),
            (EssayMeta(id: "e2", title: "读《看不见的城市》", color: .green, editedAt: now - 3 * 60 * minute, tags: ["读书"],
                       preview: "卡尔维诺写了五十五座城市", bodyLoaded: true),
             [b("paragraph", "卡尔维诺写了五十五座城市，其实每一座都是威尼斯。"),
              b("quote", "城市就像梦境，是由愿望和恐惧建造的。"),
              b("heading_3", "记下来的几座"),
              b("bulleted_list_item", "伊西多拉：梦里的城市，到了的时候已经老了"),
              b("bulleted_list_item", "芝诺比亚：建在高脚桩上"),
              b("bulleted_list_item", "莱奥尼亚：每天扔掉昨天的一切"),
              b("image", "在旧书店拍的封面", url: picture.absoluteString),
              b(Block.divider, ""),
              b("callout", "这一块在 Notion 里有图标和颜色，Leaf 只展示", fixed: true),
              b("numbered_list_item", "先读完第一部分"),
              b("numbered_list_item", "再回头看目录的结构"),
             ]),
            (EssayMeta(id: "e3", title: "关于慢", color: .blue, editedAt: now - 26 * 60 * minute, tags: ["读书", "随笔"],
                       preview: "米兰·昆德拉说，慢的乐趣怎么失传了", bodyLoaded: true),
             [b("paragraph", "米兰·昆德拉说，慢的乐趣怎么失传了。我想答案大概是：我们把慢当成了一种需要被修好的故障。")]),
            (EssayMeta(id: "e4", title: "周六的咖啡馆", color: .yellow, editedAt: now - 3 * 24 * 60 * minute, tags: ["城市"],
                       preview: "靠窗的位置总是被一个画画的人占着", bodyLoaded: true),
             [b("paragraph", "靠窗的位置总是被一个画画的人占着。他每次只点一杯美式，坐到傍晚。")]),
            (EssayMeta(id: "e5", title: "", editedAt: now - 9 * 24 * 60 * minute, preview: "还没想好标题的一段", bodyLoaded: true),
             [b("paragraph", "还没想好标题的一段。")]),
        ]
        for (meta, blocks) in essays { await store.seed(meta, blocks: blocks) }
        for (text, color) in [("今天的云像被揉皱的纸", NotionColor.blue), ("想写一篇关于地铁末班车的", .yellow),
                              ("「慢」这个字的右边是一个「曼」", .green), ("记得买伞", .default)] {
            _ = await store.addScribble(text, color: color)
        }
        return essays.map(\.0.id)
    }

    private static func drawPhoto(to url: URL) {
        let size = NSSize(width: 900, height: 560)
        let image = NSImage(size: size, flipped: false) { rect in
            NSGradient(colors: [NSColor(calibratedRed: 0.20, green: 0.36, blue: 0.45, alpha: 1),
                                NSColor(calibratedRed: 0.85, green: 0.72, blue: 0.55, alpha: 1)])?.draw(in: rect, angle: 70)
            NSColor(white: 1, alpha: 0.85).setFill()
            NSBezierPath(roundedRect: NSRect(x: 330, y: 120, width: 240, height: 320), xRadius: 6, yRadius: 6).fill()
            ("看不见的城市" as NSString).draw(at: NSPoint(x: 372, y: 300), withAttributes: [.font: NSFont.systemFont(ofSize: 26, weight: .semibold), .foregroundColor: NSColor.darkGray])
            return true
        }
        guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return }
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}
