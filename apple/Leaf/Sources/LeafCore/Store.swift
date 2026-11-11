import Foundation

/// Leaf's local library on this Mac: every essay's card, each essay's body, the wall, and the queue of
/// changes waiting for Notion. Local first: the editor writes here and never waits on the network.
///
/// Plain JSON files under one folder (index.json, bodies/<id>.json, origins/<id>.txt), written
/// atomically. Leaf's library is hundreds of essays, not millions, and files keep the store free of
/// dependencies and readable when something goes wrong. An actor, so every read-check-write (like
/// [replaceBodyIfUnchanged]) is one step that typing can't slip into.
public actor Store {
    struct Index: Codable {
        var essays: [EssayMeta] = []
        var scribbles: [Scribble] = []
        var outbox: [OutboxOp] = []
        var nextSeq: Int64 = 1
        var connection: Connection?
    }

    let dir: URL
    private var index = Index()
    private var bodies: [String: [Block]] = [:]
    private let clock: @Sendable () -> Int64
    private let newId: @Sendable () -> String
    private var listeners: [UUID: @Sendable () -> Void] = [:]

    public init(dir: URL, clock: @escaping @Sendable () -> Int64 = { Date().millis }, newId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }) {
        self.dir = dir
        self.clock = clock
        self.newId = newId
        let fm = FileManager.default
        try? fm.createDirectory(at: dir.appendingPathComponent("bodies"), withIntermediateDirectories: true)
        try? fm.createDirectory(at: dir.appendingPathComponent("origins"), withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: dir.appendingPathComponent("index.json")),
           let loaded = try? JSONDecoder().decode(Index.self, from: data) {
            index = loaded
        }
    }

    /// Called after every change, off the actor; the app reloads what it shows.
    @discardableResult
    public func observe(_ listener: @escaping @Sendable () -> Void) -> UUID {
        let id = UUID()
        listeners[id] = listener
        return id
    }

    private func changed() {
        saveIndex()
        for l in listeners.values { l() }
    }

    private func saveIndex() {
        guard let data = try? JSONEncoder().encode(index) else { return }
        try? data.write(to: dir.appendingPathComponent("index.json"), options: .atomic)
    }

    private func bodyURL(_ id: String) -> URL {
        dir.appendingPathComponent("bodies").appendingPathComponent(Self.safe(id) + ".json")
    }

    static func safe(_ id: String) -> String {
        String(id.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "_" })
    }

    // ---- reads ----

    public var connection: Connection? { index.connection }
    public var essays: [EssayMeta] { index.essays }
    public var scribbles: [Scribble] { index.scribbles }
    public var outbox: [OutboxOp] { index.outbox }

    public func essay(_ id: String) -> EssayMeta? { index.essays.first { $0.id == id } }

    public func blocks(_ essayId: String) -> [Block] {
        if let cached = bodies[essayId] { return cached }
        let loaded = (try? Data(contentsOf: bodyURL(essayId))).flatMap { try? JSONDecoder().decode([Block].self, from: $0) } ?? []
        bodies[essayId] = loaded
        return loaded
    }

    private func setBlocks(_ essayId: String, _ blocks: [Block]) {
        bodies[essayId] = blocks
        if let data = try? JSONEncoder().encode(blocks) { try? data.write(to: bodyURL(essayId), options: .atomic) }
    }

    private func dropBody(_ essayId: String) {
        bodies[essayId] = nil
        try? FileManager.default.removeItem(at: bodyURL(essayId))
        try? FileManager.default.removeItem(at: originsURL(essayId))
    }

    /// Every target with a change still waiting: local ids and, for deletes, Notion ids.
    public func pendingTargets() -> Set<String> { Set(index.outbox.map(\.target)) }

    // ---- writes from the app ----

    public func newEssay() -> EssayMeta {
        let now = clock()
        let meta = EssayMeta(id: newId(), editedAt: now, createdAt: now, bodyLoaded: true)
        index.essays.insert(meta, at: 0)
        setBlocks(meta.id, [])
        queue(.saveEssay, meta.id)
        changed()
        return meta
    }

    /// Writes the essay's card fields and body. Removed blocks are not read off a missing id here (an editor
    /// holding an older list must never delete what it hasn't seen); they go through [removeBlocks].
    public func updateEssay(_ essayId: String, title: String, color: NotionColor? = nil, pinned: Bool? = nil, blocks: [Block]? = nil) {
        guard var meta = essay(essayId) else { return }
        let now = clock()
        var touched = false
        if meta.title != title || (color != nil && meta.color != color) || (pinned != nil && meta.pinned != pinned) {
            meta.title = title
            if let color { meta.color = color }
            if let pinned { meta.pinned = pinned }
            meta.editedAt = now
            touched = true
            queue(.saveEssay, essayId)
        }
        if let blocks {
            let old = Dictionary(self.blocks(essayId).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            let rows = blocks.map { b -> Block in
                var row = b
                // The stored block is the authority on where it lives in Notion, even when that is nowhere
                // yet: a restyle cleared its id, and the editor's older copy must not bring it back.
                if let before = old[b.id] { row.notionId = before.notionId }
                // Notion can't change a block's type in place: a restyled block goes out as a new block in
                // the same spot, and the old one is deleted.
                if let before = old[b.id], before.notionId != nil, before.type != row.type { row.notionId = nil }
                return row
            }
            let changedRows = rows.filter { row in old[row.id].map { !$0.sameContent(row) } ?? true }
            if !changedRows.isEmpty {
                setBlocks(essayId, mergeRemoved(essayId, rows))
                meta.editedAt = now
                meta.preview = Self.preview(rows)
                touched = true
                for row in changedRows {
                    if let before = old[row.id], before.type != row.type, let gone = before.notionId { queue(.deleteBlock, gone) }
                    queue(.saveBlock, row.id)
                }
            }
        }
        if touched {
            replaceMeta(meta)
            changed()
        }
    }

    /// Blocks the editor did not send (it never saw them, e.g. fetched meanwhile) stay where they were.
    private func mergeRemoved(_ essayId: String, _ rows: [Block]) -> [Block] {
        let sent = Set(rows.map(\.id))
        let current = blocks(essayId)
        let missing = current.enumerated().filter { !sent.contains($0.element.id) }
        if missing.isEmpty { return rows }
        var out = rows
        for (i, block) in missing {
            // Put it back after the block that preceded it before, or at its old index.
            if i > 0, let at = out.firstIndex(where: { $0.id == current[i - 1].id }) {
                out.insert(block, at: at + 1)
            } else {
                out.insert(block, at: min(i, out.count))
            }
        }
        return out
    }

    public func removeBlocks(_ essayId: String, _ ids: Set<String>) {
        let current = blocks(essayId)
        let gone = current.filter { ids.contains($0.id) }
        if gone.isEmpty { return }
        setBlocks(essayId, current.filter { !ids.contains($0.id) })
        if var meta = essay(essayId) {
            meta.editedAt = clock()
            meta.preview = Self.preview(blocks(essayId))
            replaceMeta(meta)
        }
        for b in gone { if let n = b.notionId { queue(.deleteBlock, n) } }
        changed()
    }

    public func deleteEssay(_ essayId: String) {
        guard let meta = essay(essayId) else { return }
        index.essays.removeAll { $0.id == essayId }
        index.outbox.removeAll { $0.target == essayId && $0.kind == .saveEssay }
        let blockIds = Set(blocks(essayId).map(\.id))
        index.outbox.removeAll { $0.kind == .saveBlock && blockIds.contains($0.target) }
        dropBody(essayId)
        if let page = meta.pageId { queue(.deleteEssay, page) }
        changed()
    }

    /// Puts an essay into the library as it is, without queueing anything: for sample data (the screenshot
    /// run) only. Real essays come from [newEssay] or from Notion.
    public func seed(_ meta: EssayMeta, blocks: [Block]) {
        replaceMeta(meta)
        setBlocks(meta.id, blocks)
        changed()
    }

    public func addScribble(_ text: String, color: NotionColor) -> Scribble {
        let s = Scribble(id: newId(), text: text, color: color, createdAt: clock())
        index.scribbles.insert(s, at: 0)
        queue(.saveScribble, s.id)
        changed()
        return s
    }

    // ---- 来源 marks (this Mac only) ----

    private func originsURL(_ id: String) -> URL {
        dir.appendingPathComponent("origins").appendingPathComponent(Self.safe(id) + ".txt")
    }

    public func origins(_ essayId: String) -> [String: [OriginSpan]] {
        (try? String(contentsOf: originsURL(essayId), encoding: .utf8)).map(DeskOrigins.decode) ?? [:]
    }

    public func saveOrigins(_ essayId: String, _ spans: [String: [OriginSpan]]) {
        try? DeskOrigins.encode(spans).write(to: originsURL(essayId), atomically: true, encoding: .utf8)
    }

    // ---- outbox ----

    private func queue(_ kind: OutboxOp.Kind, _ target: String) {
        guard index.connection != nil else { return }
        if kind != .deleteBlock, index.outbox.contains(where: { $0.kind == kind && $0.target == target }) { return }
        index.outbox.append(OutboxOp(seq: index.nextSeq, kind: kind, target: target))
        index.nextSeq += 1
    }

    func nextOp() -> OutboxOp? { index.outbox.first }

    func done(_ seq: Int64) {
        index.outbox.removeAll { $0.seq == seq }
        changed()
    }

    func failedOnce(_ seq: Int64) {
        if let i = index.outbox.firstIndex(where: { $0.seq == seq }) {
            index.outbox[i].attempts += 1
            saveIndex()
        }
    }

    // ---- writes from sync ----

    func replaceMeta(_ meta: EssayMeta) {
        if let i = index.essays.firstIndex(where: { $0.id == meta.id }) {
            index.essays[i] = meta
        } else {
            index.essays.append(meta)
        }
    }

    func upsertMeta(_ metas: [EssayMeta]) {
        if metas.isEmpty { return }
        metas.forEach(replaceMeta)
        changed()
    }

    func setNotionId(essayId: String, pageId: String) {
        guard var meta = essay(essayId) else { return }
        meta.pageId = pageId
        replaceMeta(meta)
        changed()
    }

    func setBlockNotionId(essayId: String, blockId: String, notionId: String) {
        var list = blocks(essayId)
        guard let i = list.firstIndex(where: { $0.id == blockId }) else { return }
        list[i].notionId = notionId
        setBlocks(essayId, list)
    }

    func essayHolding(block blockId: String) -> String? {
        for meta in index.essays where blocks(meta.id).contains(where: { $0.id == blockId }) { return meta.id }
        return nil
    }

    func removeEssays(_ ids: [String]) {
        if ids.isEmpty { return }
        let set = Set(ids)
        index.essays.removeAll { set.contains($0.id) }
        ids.forEach(dropBody)
        changed()
    }

    func scribble(_ id: String) -> Scribble? { index.scribbles.first { $0.id == id } }

    func setScribbles(_ list: [Scribble]) {
        index.scribbles = list
        changed()
    }

    /// Replaces the body with what Notion has, unless it changed since [expected] was read or something of
    /// it waits to go out: typing during the fetch wins over the fetched body.
    @discardableResult
    func replaceBodyIfUnchanged(_ essayId: String, expected: [Block], with fresh: [Block], preview: String) -> Bool {
        let waiting = pendingTargets()
        let current = blocks(essayId)
        if current != expected || waiting.contains(essayId) || current.contains(where: { waiting.contains($0.id) }) { return false }
        setBlocks(essayId, fresh)
        if var meta = essay(essayId) {
            meta.bodyLoaded = true
            meta.preview = preview
            replaceMeta(meta)
        }
        changed()
        return true
    }

    /// Points the library at a database. Rows from an earlier connection go; essays written here before
    /// any connection stay and are queued, so nothing written offline is lost.
    public func connect(_ connection: Connection) {
        let mine = index.essays.filter { $0.pageId == nil }
        let notes = index.scribbles.filter { $0.notionId == nil }
        for meta in index.essays where meta.pageId != nil { dropBody(meta.id) }
        index = Index(connection: connection)
        index.essays = mine
        index.scribbles = notes
        for note in notes { queue(.saveScribble, note.id) }
        for meta in mine {
            queue(.saveEssay, meta.id)
            for block in blocks(meta.id) {
                var list = blocks(meta.id)
                if let i = list.firstIndex(where: { $0.id == block.id }) { list[i].notionId = nil; setBlocks(meta.id, list) }
                queue(.saveBlock, block.id)
            }
        }
        changed()
    }

    /// Forgets the connection and every local copy; Notion keeps everything.
    public func disconnect() {
        for meta in index.essays { dropBody(meta.id) }
        index = Index()
        changed()
    }

    static func preview(_ blocks: [Block]) -> String {
        String(blocks.filter { DeskText.editableTypes.contains($0.type) }.map(\.text).joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines).prefix(400))
    }
}
