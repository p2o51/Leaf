import AppKit
import Combine
import LeafCore
import SwiftUI

/// What the sidebar picked.
enum Shelf: Hashable {
    case all
    case wall
    case color(NotionColor)
    case tag(String)
}

/// The writing desk's settings, kept between launches.
final class DeskSettings: ObservableObject {
    enum Focus: String, CaseIterable, Identifiable {
        case off, sentence, paragraph
        var id: String { rawValue }
        var label: String { self == .off ? "全文" : self == .sentence ? "句" : "段" }
        var next: Focus { self == .off ? .sentence : self == .sentence ? .paragraph : .off }
    }

    private let defaults: UserDefaults

    @Published var focus: Focus { didSet { defaults.set(focus.rawValue, forKey: "desk.focus") } }
    @Published var typewriter: Bool { didSet { defaults.set(typewriter, forKey: "desk.typewriter") } }
    @Published var font: DeskFont { didSet { defaults.set(font.rawValue, forKey: "desk.font") } }
    @Published var style: Bool { didSet { defaults.set(style, forKey: "desk.style") } }
    @Published var origins: Bool { didSet { defaults.set(origins, forKey: "desk.origins") } }
    @Published var textSize: Double { didSet { defaults.set(textSize, forKey: "desk.size") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        focus = Focus(rawValue: defaults.string(forKey: "desk.focus") ?? "") ?? .off
        typewriter = defaults.bool(forKey: "desk.typewriter")
        font = DeskFont(rawValue: defaults.string(forKey: "desk.font") ?? "") ?? .serif
        style = defaults.bool(forKey: "desk.style")
        origins = defaults.object(forKey: "desk.origins") as? Bool ?? true
        let size = defaults.double(forKey: "desk.size")
        textSize = size >= 12 && size <= 28 ? size : 17
    }
}

/// The app's state: the local library (always), and Notion when connected. Local first: everything the
/// person sees comes from [Store]; sync runs beside it and never blocks typing.
@MainActor
final class AppModel: ObservableObject {
    static let backgroundEvery: TimeInterval = 300
    /// While an essay is open and the window in front, that one page is fetched again this often.
    static let openRefreshEvery: TimeInterval = 30
    /// Pushing waits for the pen to rest this long after a write.
    nonisolated static let pushAfter: TimeInterval = 1.5
    static let restBeforeRefresh: TimeInterval = 1.5

    let store: Store
    let sync: SyncEngine
    let notion = LeafNotion()
    let desk: DeskSettings

    @Published private(set) var essays: [EssayMeta] = []
    @Published private(set) var scribbles: [Scribble] = []
    @Published private(set) var connection: Connection?
    @Published private(set) var status: SyncStatus = .idle
    @Published var shelf: Shelf = .all
    @Published var selected: String?
    @Published var search = ""
    /// Bumps whenever the store changes, so an open desk can look for news from Notion.
    @Published private(set) var revision = 0

    /// The desk currently open, so menu commands (⌘S) reach it.
    weak var openDesk: DeskController?
    var lastEdit = Date.distantPast

    private var pushTask: Task<Void, Never>?
    private var timers: [Timer] = []

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Leaf", isDirectory: true)
        if Shots.dir != nil {
            // The screenshot run uses a throwaway library and settings, never the real ones.
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent("leaf-shots-\(UUID().uuidString)")
            store = Store(dir: temp)
            let suite = UserDefaults(suiteName: "dev.chenwuyi.leaf.shots") ?? .standard
            suite.removePersistentDomain(forName: "dev.chenwuyi.leaf.shots")
            desk = DeskSettings(defaults: suite)
        } else {
            store = Store(dir: support)
            desk = DeskSettings()
        }
        sync = SyncEngine(store: store, notion: notion, credentials: KeychainCredentials())
        Task {
            await store.observe { [weak self] in Task { @MainActor in await self?.reload() } }
            await sync.observe { [weak self] s in Task { @MainActor in self?.status = s } }
            await reload()
            await runSync()
        }
        timers.append(Timer.scheduledTimer(withTimeInterval: Self.backgroundEvery, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.runSync() }
        })
        timers.append(Timer.scheduledTimer(withTimeInterval: Self.openRefreshEvery, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.refreshOpen() }
        })
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            // Back at the window: the open essay is fetched first, the rest follows.
            Task { @MainActor in
                await self?.refreshOpen(force: true)
                await self?.runSync()
            }
        }
    }

    func reload() async {
        let list = await store.essays
        essays = list.sorted { a, b in a.pinned != b.pinned ? a.pinned : a.editedAt > b.editedAt }
        scribbles = await store.scribbles
        connection = await store.connection
        revision += 1
    }

    var tags: [String] {
        Array(Set(essays.flatMap(\.tags))).sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    var colors: [NotionColor] {
        NotionColor.allCases.filter { c in c != .default && essays.contains { $0.color == c } }
    }

    var shown: [EssayMeta] {
        let q = search.trimmingCharacters(in: .whitespaces)
        return essays.filter { e in
            switch shelf {
            case .all, .wall: break
            case .color(let c): if e.color != c { return false }
            case .tag(let t): if !e.tags.contains(t) { return false }
            }
            return q.isEmpty || e.title.localizedCaseInsensitiveContains(q) || e.preview.localizedCaseInsensitiveContains(q)
        }
    }

    // ---- writing ----

    func newEssay() {
        Task {
            let meta = await store.newEssay()
            shelf = .all
            selected = meta.id
        }
    }

    func open(_ essayId: String) async {
        await sync.loadBody(essayId)
    }

    /// The desk wrote: blocks to the store (and its outbox) now, Notion once the pen rests.
    func wrote(_ essayId: String, title: String, blocks: [Block], gone: Set<String>, origins: [String: [OriginSpan]]) async {
        await store.updateEssay(essayId, title: title, blocks: blocks)
        if !gone.isEmpty { await store.removeBlocks(essayId, gone) }
        await store.saveOrigins(essayId, origins)
        schedulePush()
    }

    func schedulePush(after delay: TimeInterval = AppModel.pushAfter) {
        pushTask?.cancel()
        pushTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            if Task.isCancelled { return }
            await sync.run(pull: false)
        }
    }

    /// ⌘S: write what the desk holds now and send it.
    func saveNow() {
        openDesk?.writeNow()
        schedulePush(after: 0.2)
    }

    func runSync() async {
        guard connection != nil else { return }
        await sync.run()
    }

    /// 资源集中在当前这篇: only the open essay is fetched again, and only while this window is in front
    /// and the writer has paused.
    func refreshOpen(force: Bool = false) async {
        guard connection != nil, let id = selected, NSApp.isActive else { return }
        if !force && Date().timeIntervalSince(lastEdit) < Self.restBeforeRefresh { return }
        await sync.refreshBody(id)
    }

    func setColor(_ essayId: String, _ color: NotionColor) {
        Task {
            guard let meta = await store.essay(essayId) else { return }
            await store.updateEssay(essayId, title: meta.title, color: color)
            schedulePush()
        }
    }

    func togglePinned(_ essayId: String) {
        Task {
            guard let meta = await store.essay(essayId) else { return }
            await store.updateEssay(essayId, title: meta.title, pinned: !meta.pinned)
            schedulePush()
        }
    }

    func delete(_ essayId: String) {
        Task {
            if selected == essayId { selected = nil }
            await store.deleteEssay(essayId)
            schedulePush()
        }
    }

    func addScribble(_ text: String, color: NotionColor) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        Task {
            _ = await store.addScribble(clean, color: color)
            schedulePush()
        }
    }

    // ---- connecting ----

    func connect(credential: NotionCredential, workspace: String, picked: DataSourceInfo) async throws {
        let c = try await SyncEngine.connection(notion, token: credential.accessToken, workspace: workspace, picked: picked)
        guard Keychain.write(credential) else { throw NotionFailure.unauthorized }
        selected = nil
        await store.connect(c)
        await runSync()
    }

    func disconnect() {
        Task {
            Keychain.clear()
            selected = nil
            await store.disconnect()
        }
    }
}
