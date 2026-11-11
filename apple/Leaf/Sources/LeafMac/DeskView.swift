import AppKit
import LeafCore
import SwiftUI

/// The 写作台 for one essay: the TextKit 2 editor and a quiet toolbar.
struct DeskView: View {
    let essayId: String
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var desk: DeskSettings
    @State private var controller: DeskController?

    var body: some View {
        DeskHost(essayId: essayId, controller: $controller)
            .ignoresSafeArea(.container, edges: .bottom)
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Picker("专注", selection: $desk.focus) {
                        ForEach(DeskSettings.Focus.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .help("专注：全文 / 句 / 段（⌘D）")
                    Toggle(isOn: $desk.typewriter) { Label("打字机", systemImage: "text.aligncenter") }
                        .help("打字机：正在写的一行留在窗口中间（⌘T）")
                    Picker("字体", selection: $desk.font) {
                        ForEach(DeskFont.allCases) { Text($0.label).tag($0) }
                    }
                    .help("IBM Plex 衬线 / 无衬线 / 等宽（⌘1 ⌘2 ⌘3）")
                    Toggle(isOn: $desk.style) { Label("文风", systemImage: "text.badge.checkmark") }
                        .help("文风：标出填充词、程度副词、的字太多、长句、半角标点；只标不改（⇧⌘L）")
                    Toggle(isOn: $desk.origins) { Label("来源", systemImage: "doc.on.clipboard") }
                        .help("来源：粘贴进来的字换个颜色，只存在这台 Mac 上（⇧⌘O）")
                }
            }
    }
}

struct DeskHost: NSViewRepresentable {
    let essayId: String
    @Binding var controller: DeskController?
    @EnvironmentObject var model: AppModel

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var controller: DeskController?
        var essayId: String?
    }

    func makeNSView(context: Context) -> NSScrollView {
        let c = DeskController(model: model)
        context.coordinator.controller = c
        DispatchQueue.main.async { controller = c }
        return c.scrollView
    }

    func updateNSView(_ view: NSScrollView, context: Context) {
        guard let c = context.coordinator.controller, context.coordinator.essayId != essayId else { return }
        context.coordinator.essayId = essayId
        Task { @MainActor in await c.open(essayId) }
    }

    static func dismantleNSView(_ view: NSScrollView, coordinator: Coordinator) {
        coordinator.controller?.writeNow()
    }
}
