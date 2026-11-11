import Foundation

/// Where the sync engine gets its token, and a fresh one after Notion answers 401 (OAuth refresh).
/// The Mac app backs it with the Keychain; nothing in LeafCore keeps a secret.
public protocol Credentials: Sendable {
    func token() async -> String?
    /// A token to retry with after [stale] was turned down, or nil (pasted tokens can't be refreshed).
    func refreshed(stale: String) async -> String?
}

public enum SyncStatus: Equatable, Sendable {
    case idle
    case running
    /// Will retry by itself (offline, rate limited).
    case waiting(String)
    /// Needs the person: the token stopped working or the database is no longer shared.
    case needsAttention(String)
}

public enum RunResult: Sendable { case done, retryLater, stopped }

/// Moves changes between the local [Store] and Notion, after Android's SyncEngine. Push drains the outbox
/// in order; pull mirrors the wall and the essay rows and never overwrites anything still waiting to go
/// out. Bodies are fetched one essay at a time: the open one, when it opens and while it stays open.
public actor SyncEngine {
    let store: Store
    let notion: LeafNotion
    let credentials: Credentials
    let newId: @Sendable () -> String
    public private(set) var status: SyncStatus = .idle
    private var onStatus: (@Sendable (SyncStatus) -> Void)?
    private var busy = false

    public init(store: Store, notion: LeafNotion, credentials: Credentials, newId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }) {
        self.store = store
        self.notion = notion
        self.credentials = credentials
        self.newId = newId
    }

    public func observe(_ f: @escaping @Sendable (SyncStatus) -> Void) { onStatus = f }

    private func set(_ s: SyncStatus) {
        status = s
        onStatus?(s)
    }

    /// One run at a time; a run asked for while one is going waits for it.
    private func exclusive<T>(_ body: () async throws -> T) async rethrows -> T {
        while busy { try? await Task.sleep(nanoseconds: 50_000_000) }
        busy = true
        defer { busy = false }
        return try await body()
    }

    private func withToken<T>(_ body: (String) async throws -> T) async throws -> T {
        guard let token = await credentials.token() else { throw NotionFailure.unauthorized }
        do {
            return try await body(token)
        } catch NotionFailure.unauthorized {
            guard let fresh = await credentials.refreshed(stale: token) else { throw NotionFailure.unauthorized }
            return try await body(fresh)
        }
    }

    /// Push, then (unless [pull] is false) pull the wall and the rows.
    @discardableResult
    public func run(pull: Bool = true) async -> RunResult {
        await exclusive {
            guard let c = await store.connection else { return .done }
            set(.running)
            do {
                try await withToken { token in
                    try await push(token, c)
                    if pull { try await self.pull(token, c) }
                }
                set(.idle)
                return .done
            } catch let failure as NotionFailure {
                switch failure {
                case .rateLimited, .unavailable:
                    set(.waiting(failure.message))
                    return .retryLater
                default:
                    set(.needsAttention(failure.message))
                    return .stopped
                }
            } catch {
                set(.waiting(NotionFailure.unavailable.message))
                return .retryLater
            }
        }
    }

    // ---- push ----

    func push(_ token: String, _ c: Connection) async throws {
        while let op = await store.nextOp() {
            let finished: Bool
            do {
                finished = try await apply(token, c, op)
            } catch let failure as NotionFailure where failure == .invalidInput || failure == .notFound {
                // A change Notion rejects for good must not block the queue forever.
                if op.attempts >= 2 {
                    finished = true
                } else {
                    await store.failedOnce(op.seq)
                    throw failure
                }
            }
            if finished { await store.done(op.seq) }
        }
    }

    /// False when the local copy changed while it was being pushed, so the op runs again.
    private func apply(_ token: String, _ c: Connection, _ op: OutboxOp) async throws -> Bool {
        switch op.kind {
        case .deleteBlock:
            try await notion.deleteBlock(token, op.target)
            return true
        case .deleteEssay:
            try await notion.trashPage(token, op.target)
            return true
        case .saveEssay:
            guard let meta = await store.essay(op.target) else { return true }
            var pageId = meta.pageId
            if pageId == nil {
                let id = try await notion.createPage(token, dataSourceId: c.dataSourceId, titleProperty: c.titleProperty, title: meta.title, type: LeafSchema.typeEssay)
                await store.setNotionId(essayId: meta.id, pageId: id)
                pageId = id
            }
            try await notion.updateEssay(token, titleProperty: c.titleProperty, pageId: pageId!, title: meta.title, color: meta.color, pinned: meta.pinned)
            guard let now = await store.essay(meta.id) else { return true }
            return now.title == meta.title && now.color == meta.color && now.pinned == meta.pinned
        case .saveBlock:
            guard let essayId = await store.essayHolding(block: op.target) else { return true }
            guard let row = await store.blocks(essayId).first(where: { $0.id == op.target }) else { return true }
            try await pushBlock(token, essayId, row)
            guard let now = await store.blocks(essayId).first(where: { $0.id == op.target }) else { return true }
            return now.sameContent(row)
        case .saveScribble:
            guard let row = await store.scribble(op.target) else { return true }
            if let blockId = row.notionId {
                try await notion.updateBlock(token, blockId, NotionJSON.textUpdate("paragraph", row.text, row.color))
            } else {
                guard let created = try await notion.append(token, c.wallPageId, [NotionJSON.textBlock("paragraph", row.text, row.color)]).first else { return true }
                var list = await store.scribbles
                if let i = list.firstIndex(where: { $0.id == row.id }) {
                    list[i].notionId = created
                    await store.setScribbles(list)
                } else {
                    try await notion.deleteBlock(token, created)
                }
            }
            return true
        }
    }

    private func pushBlock(_ token: String, _ essayId: String, _ row: Block) async throws {
        // The essay's own saveEssay op is always earlier in the queue, so it has a page by now.
        guard let pageId = await store.essay(essayId)?.pageId else { throw NotionFailure.invalidInput }
        let isDivider = row.type == Block.divider
        guard NotionJSON.textTypes.contains(row.type) || isDivider else { return }
        if let blockId = row.notionId {
            if !isDivider, !row.fixed {
                try await notion.updateBlock(token, blockId, NotionJSON.textUpdate(row.type, row.text, row.color))
            }
            return
        }
        let json = isDivider ? NotionJSON.divider() : NotionJSON.textBlock(row.type, row.text, row.color)
        let all = await store.blocks(essayId)
        guard let position = all.firstIndex(where: { $0.id == row.id }) else { return }
        let after = all[..<position].last(where: { $0.notionId != nil })?.notionId
        // Nothing synced before it but something after: it is the new first block.
        let atStart = after == nil && all[(position + 1)...].contains { $0.notionId != nil }
        guard let created = try await notion.append(token, pageId, [json], after: after, atStart: atStart).first else { return }
        // Removed, or restyled into another type, meanwhile: this block has no use, so it goes again.
        let now = await store.blocks(essayId).first { $0.id == row.id }
        if let now, now.type == row.type {
            await store.setBlockNotionId(essayId: essayId, blockId: row.id, notionId: created)
        } else {
            try await notion.deleteBlock(token, created)
        }
    }

    // ---- pull ----

    func pull(_ token: String, _ c: Connection) async throws {
        try await pullWall(token, c)
        try await pullEssays(token, c)
    }

    private func pullWall(_ token: String, _ c: Connection) async throws {
        let remote = try await notion.wallScribbles(token, c.wallPageId)
        let waiting = await store.pendingTargets()
        let local = await store.scribbles
        let byBlock = Dictionary(local.compactMap { s in s.notionId.map { ($0, s) } }, uniquingKeysWith: { a, _ in a })
        var out: [Scribble] = remote.map { r in
            if let mine = byBlock[r.blockId] {
                if waiting.contains(mine.id) { return mine }
                var s = mine
                s.text = r.text
                s.color = r.color
                s.imageUrl = r.imageUrl ?? mine.imageUrl
                return s
            }
            return Scribble(id: newId(), notionId: r.blockId, text: r.text, color: r.color, createdAt: r.createdAt, imageUrl: r.imageUrl)
        }
        // Written here and not yet in Notion: keep them.
        out += local.filter { $0.notionId == nil }
        out.sort { $0.createdAt > $1.createdAt }
        if out != local { await store.setScribbles(out) }
    }

    private func pullEssays(_ token: String, _ c: Connection) async throws {
        let rows = try await notion.rows(token, dataSourceId: c.dataSourceId, titleProperty: c.titleProperty)
            .filter { !$0.isWall && $0.pageId != c.wallPageId }
        let waiting = await store.pendingTargets()
        let local = await store.essays
        let byPage = Dictionary(local.compactMap { e in e.pageId.map { ($0, e) } }, uniquingKeysWith: { a, _ in a })
        var metas: [EssayMeta] = []
        var needPreview: [EssayMeta] = []
        for r in rows {
            // Deleted here and not yet trashed in Notion: pulling it back would undo the delete.
            if waiting.contains(r.pageId) { continue }
            let mine = byPage[r.pageId]
            if let mine, waiting.contains(mine.id) { continue }
            let changed = mine == nil || mine!.editedAt != r.editedAt
            let meta = EssayMeta(
                id: mine?.id ?? newId(), pageId: r.pageId, title: r.title, color: r.color, pinned: r.pinned,
                editedAt: r.editedAt, createdAt: r.createdAt ?? mine?.createdAt, tags: r.tags,
                preview: mine?.preview ?? "", bodyLoaded: mine?.bodyLoaded == true && !changed, coverUrl: r.coverUrl
            )
            metas.append(meta)
            if changed || meta.preview.isEmpty { needPreview.append(meta) }
        }
        // Every row shows at once; previews follow, newest first, three at a time.
        await store.upsertMeta(metas)
        let remoteIds = Set(rows.map(\.pageId))
        let gone = local.filter { $0.pageId != nil && !remoteIds.contains($0.pageId!) && !waiting.contains($0.id) }.map(\.id)
        await store.removeEssays(gone)

        for batch in stride(from: 0, to: needPreview.count, by: 3).map({ Array(needPreview[$0..<min($0 + 3, needPreview.count)]) }) {
            let previews = try await withThrowingTaskGroup(of: (String, String).self) { group in
                for meta in batch {
                    group.addTask { [notion] in
                        let blocks = try await notion.children(token, meta.pageId!, limit: 8).compactMap(NotionJSON.bodyBlock)
                        let text = blocks.filter { NotionJSON.textTypes.contains($0.type) }.map(\.text).joined(separator: " ")
                        return (meta.id, String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(400)))
                    }
                }
                var out: [String: String] = [:]
                for try await (id, text) in group { out[id] = text }
                return out
            }
            var updated: [EssayMeta] = []
            for (id, text) in previews {
                guard var meta = await store.essay(id), !(await store.pendingTargets().contains(id)) else { continue }
                if meta.bodyLoaded { continue }
                meta.preview = text
                updated.append(meta)
            }
            await store.upsertMeta(updated)
        }
    }

    /// Loads one essay's body unless it is loaded already or has edits still waiting to go out.
    public func loadBody(_ essayId: String) async { await fetchBody(essayId, again: false) }

    /// Fetches the open essay's body again, so edits made elsewhere (Notion, the phone) reach this window.
    /// Only that one page, and only while nothing of it waits to go out.
    public func refreshBody(_ essayId: String) async { await fetchBody(essayId, again: true) }

    private func fetchBody(_ essayId: String, again: Bool) async {
        await exclusive {
            guard await store.connection != nil, let meta = await store.essay(essayId), let pageId = meta.pageId else { return }
            if meta.bodyLoaded && !again { return }
            let waiting = await store.pendingTargets()
            let before = await store.blocks(essayId)
            if waiting.contains(essayId) || before.contains(where: { waiting.contains($0.id) }) { return }
            guard let remote = try? await withToken({ try await notion.essayBody($0, pageId) }) else { return }
            // Blocks already known keep their local id, so an edit made from the old list still lands on them.
            let known = Dictionary(before.compactMap { b in b.notionId.map { ($0, b.id) } }, uniquingKeysWith: { a, _ in a })
            // A block deleted here whose deleteBlock hasn't gone out is still in Notion; its op targets the
            // Notion id, so drop it from the fetched body rather than bring it back.
            let fresh = remote.filter { !waiting.contains($0.blockId) }.map { r in
                Block(id: known[r.blockId] ?? newId(), notionId: r.blockId, type: r.type, text: r.text, color: r.color, fixed: r.fixed, url: r.url)
            }
            await store.replaceBodyIfUnchanged(essayId, expected: before, with: fresh, preview: Store.preview(fresh))
        }
    }

    // ---- connecting ----

    /// Step 1: check the token and list the databases shared with it.
    public static func discover(_ notion: LeafNotion, token: String) async throws -> (String, [DataSourceInfo]) {
        let workspace = try await notion.workspaceName(token)
        return (workspace, try await notion.dataSources(token))
    }

    /// Step 2: add missing fields, find the 涂鸦墙 row (or create it). The caller saves the credential,
    /// then hands the result to [Store.connect] and runs a sync.
    public static func connection(_ notion: LeafNotion, token: String, workspace: String, picked: DataSourceInfo) async throws -> Connection {
        let source = try await notion.ensureSchema(token, try await notion.dataSource(token, picked.id))
        guard let title = source.titleProperty else { throw NotionFailure.invalidResponse }
        let wall: String
        if let found = try await notion.rows(token, dataSourceId: source.id, titleProperty: title).filter(\.isWall).min(by: { $0.editedAt < $1.editedAt }) {
            wall = found.pageId
        } else {
            wall = try await notion.createPage(token, dataSourceId: source.id, titleProperty: title, title: LeafSchema.wallTitle, type: LeafSchema.typeWall)
        }
        return Connection(workspace: workspace, dataSourceId: source.id, databaseName: source.name, titleProperty: title, wallPageId: wall)
    }
}
