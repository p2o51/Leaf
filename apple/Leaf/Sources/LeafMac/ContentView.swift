import LeafCore
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var columns = NavigationSplitViewVisibility.all
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            Sidebar()
                .navigationSplitViewColumnWidth(min: 170, ideal: 200)
        } content: {
            Group {
                if model.shelf == .wall {
                    WallView()
                } else {
                    EssayList()
                }
            }
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
        } detail: {
            if let id = model.selected, model.shelf != .wall {
                DeskView(essayId: id)
            } else {
                Text(model.connection == nil ? "在设置里连接 Notion，或先写一篇（⌘N）" : "选一篇随笔，或写一篇新的（⌘N）")
                    .foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .searchable(text: $model.search, placement: .sidebar, prompt: "搜索随笔")
        .task {
            if let dir = Shots.dir { await Shots.run(model: model, into: dir, openSettings: { openSettings() }) }
        }
    }
}

struct Sidebar: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List(selection: Binding<Shelf?>(get: { model.shelf }, set: { model.shelf = $0 ?? .all })) {
            Label("全部随笔", systemImage: "doc.text").tag(Optional(Shelf.all))
            Label("涂鸦墙", systemImage: "scribble.variable").tag(Optional(Shelf.wall))
            if !model.colors.isEmpty {
                Section("颜色") {
                    ForEach(model.colors, id: \.self) { c in
                        Label { Text(c.label) } icon: { Circle().fill(c.swatch).frame(width: 10, height: 10) }
                            .tag(Optional(Shelf.color(c)))
                    }
                }
            }
            if !model.tags.isEmpty {
                Section("标签") {
                    ForEach(model.tags, id: \.self) { t in
                        Label(t, systemImage: "number").tag(Optional(Shelf.tag(t)))
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) { SyncLine().padding(10) }
    }
}

/// One quiet line: says something only while syncing or when something needs the person.
struct SyncLine: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Group {
            switch model.status {
            case .running: Text("同步中…")
            case .waiting(let m): Text(m)
            case .needsAttention(let m): Text(m).foregroundStyle(.orange)
            case .idle: if model.connection == nil { SettingsLink { Text("连接 Notion…") } }
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct EssayList: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List(selection: $model.selected) {
            ForEach(model.shown) { e in
                EssayRow(essay: e)
                    .tag(Optional(e.id))
                    .contextMenu {
                        Button(e.pinned ? "取消置顶" : "置顶") { model.togglePinned(e.id) }
                        Menu("卡片颜色") {
                            ForEach(NotionColor.allCases, id: \.self) { c in
                                Button(c.label) { model.setColor(e.id, c) }
                            }
                        }
                        Divider()
                        Button("删除（Notion 里进回收站）", role: .destructive) { model.delete(e.id) }
                    }
            }
        }
        .toolbar {
            ToolbarItem {
                Button { model.newEssay() } label: { Label("新随笔", systemImage: "square.and.pencil") }
            }
        }
    }
}

struct EssayRow: View {
    let essay: EssayMeta

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(essay.color == .default ? Color.clear : essay.color.swatch)
                .frame(width: 4)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) {
                    if essay.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.secondary) }
                    Text(essay.title.isEmpty ? "无题" : essay.title).font(.headline).lineLimit(1)
                }
                if !essay.preview.isEmpty {
                    Text(essay.preview).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                }
                HStack(spacing: 6) {
                    Text(Date(timeIntervalSince1970: Double(essay.createdAt ?? essay.editedAt) / 1000), format: .dateTime.month().day())
                    ForEach(essay.tags.prefix(3), id: \.self) { Text("#\($0)") }
                }
                .font(.caption)
                .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
    }
}

extension NotionColor {
    /// Notion's light-mode highlight colours, for small swatches.
    var swatch: Color {
        switch self {
        case .default: return .secondary
        case .gray: return Color(red: 0.47, green: 0.46, blue: 0.44)
        case .brown: return Color(red: 0.62, green: 0.42, blue: 0.33)
        case .orange: return Color(red: 0.85, green: 0.45, blue: 0.05)
        case .yellow: return Color(red: 0.80, green: 0.57, blue: 0.13)
        case .green: return Color(red: 0.27, green: 0.51, blue: 0.40)
        case .blue: return Color(red: 0.20, green: 0.49, blue: 0.67)
        case .purple: return Color(red: 0.56, green: 0.40, blue: 0.67)
        case .pink: return Color(red: 0.76, green: 0.33, blue: 0.54)
        case .red: return Color(red: 0.83, green: 0.30, blue: 0.26)
        }
    }
}
