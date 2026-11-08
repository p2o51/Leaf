import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum NotionFailure: String, Error, Sendable {
    case unauthorized, forbidden, notFound, invalidInput, rateLimited, unavailable, invalidResponse

    /// What to tell the person. Never includes the token or a request body.
    public var message: String {
        switch self {
        case .unauthorized: return "Notion 不再认这个连接，请重新连接"
        case .forbidden, .notFound: return "Leaf 看不到这个页面：在 Notion 里把数据库分享给你的集成"
        case .invalidInput: return "Notion 拒绝了这次修改"
        case .rateLimited, .unavailable: return "Notion 暂时连不上，稍后会自动重试"
        case .invalidResponse: return "Notion 返回了看不懂的内容"
        }
    }

    static func from(status: Int) -> NotionFailure {
        switch status {
        case 400: return .invalidInput
        case 401: return .unauthorized
        case 403: return .forbidden
        case 404: return .notFound
        case 429, 529: return .rateLimited
        default: return .unavailable
        }
    }
}

/// The raw HTTP step, so tests can stand in for Notion.
public protocol NotionTransport: Sendable {
    /// Returns the status code, the Retry-After header (seconds) if any, and the body.
    func send(_ request: URLRequest) async throws -> (Int, Int?, Data)
}

public struct URLSessionTransport: NotionTransport {
    let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func send(_ request: URLRequest) async throws -> (Int, Int?, Data) {
        let (data, response) = try await session.data(for: request)
        let http = response as? HTTPURLResponse
        let retry = (http?.value(forHTTPHeaderField: "Retry-After")).flatMap { Int($0) }
        return (http?.statusCode ?? 0, retry, data)
    }
}

/// Thin HTTP layer for the Notion API, like Android's NotionClient: request starts are kept about
/// 350 ms apart (Notion allows about three a second) and 429 / 5xx are retried with Retry-After or backoff.
public actor NotionClient {
    public static let apiVersion = "2026-03-11"
    static let maxRetries = 4

    let transport: NotionTransport
    let base: URL
    let interval: UInt64
    private var lastStart: UInt64 = 0

    public init(transport: NotionTransport = URLSessionTransport(), base: URL = URL(string: "https://api.notion.com/v1/")!, intervalMillis: UInt64 = 350) {
        // The token only ever goes to api.notion.com, or to a loopback server in tests.
        precondition((base.scheme == "https" && base.host == "api.notion.com") || ["localhost", "127.0.0.1", "::1"].contains(base.host ?? ""))
        self.transport = transport
        self.base = base
        self.interval = intervalMillis
    }

    public func get(_ token: String, _ path: String, query: [String: String?] = [:]) async throws -> JSON {
        try await call(token, "GET", path, nil, query)
    }

    public func post(_ token: String, _ path: String, _ body: JSON) async throws -> JSON {
        try await call(token, "POST", path, body, [:])
    }

    public func patch(_ token: String, _ path: String, _ body: JSON) async throws -> JSON {
        try await call(token, "PATCH", path, body, [:])
    }

    public func delete(_ token: String, _ path: String) async throws -> JSON {
        try await call(token, "DELETE", path, nil, [:])
    }

    private func now() -> UInt64 { DispatchTime.now().uptimeNanoseconds / 1_000_000 }

    /// Waits for this request's slot. The slot is taken before awaiting the network, so concurrent calls
    /// overlap their round trips but never start closer than [interval] apart.
    private func takeSlot() async throws {
        let next = max(now(), lastStart + interval)
        lastStart = next
        let wait = next > now() ? next - now() : 0
        if wait > 0 { try await Task.sleep(nanoseconds: wait * 1_000_000) }
    }

    private func call(_ token: String, _ method: String, _ path: String, _ body: JSON?, _ query: [String: String?]) async throws -> JSON {
        let clean = token.trimmingCharacters(in: .whitespaces)
        if clean.isEmpty || clean.contains("\n") || clean.contains("\r") { throw NotionFailure.unauthorized }
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        let items = query.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }.sorted { $0.name < $1.name }
        if !items.isEmpty { components.queryItems = items }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("Bearer \(clean)", forHTTPHeaderField: "Authorization")
        request.setValue(Self.apiVersion, forHTTPHeaderField: "Notion-Version")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        } else if method == "POST" || method == "PATCH" {
            request.httpBody = Data()
        }
        for attempt in 0...Self.maxRetries {
            try await takeSlot()
            let answer: (Int, Int?, Data)
            do {
                answer = try await transport.send(request)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if attempt == Self.maxRetries { throw NotionFailure.unavailable }
                try await Task.sleep(nanoseconds: backoff(attempt) * 1_000_000)
                continue
            }
            let (status, retryAfter, data) = answer
            if (200..<300).contains(status) {
                guard let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON else { throw NotionFailure.invalidResponse }
                return json
            }
            let retryable = [429, 500, 502, 503, 504, 529].contains(status)
            if !retryable || attempt == Self.maxRetries { throw NotionFailure.from(status: status) }
            let wait = retryAfter.flatMap { (0...600).contains($0) ? UInt64($0) * 1000 : nil } ?? backoff(attempt)
            try await Task.sleep(nanoseconds: wait * 1_000_000)
        }
        throw NotionFailure.unavailable
    }

    private func backoff(_ attempt: Int) -> UInt64 {
        min(1000 << UInt64(attempt), 30_000) + UInt64.random(in: 0...250)
    }

    /// Accepts a Notion URL or a bare ID; returns the dashed form.
    public static func extractId(_ value: String) -> String? {
        let head = value.split(separator: "?").first.map(String.init) ?? value
        let hex = Set("0123456789abcdefABCDEF")
        var best: String?
        var run = ""
        for ch in head + " " {
            if hex.contains(ch) || ch == "-" { run.append(ch); continue }
            let bare = run.replacingOccurrences(of: "-", with: "")
            if bare.count >= 32 { best = String(bare.suffix(32)) }
            run = ""
        }
        guard let h = best?.lowercased() else { return nil }
        let c = Array(h)
        return "\(String(c[0..<8]))-\(String(c[8..<12]))-\(String(c[12..<16]))-\(String(c[16..<20]))-\(String(c[20..<32]))"
    }
}

/// The Notion calls Leaf makes, in Leaf's terms. Every call takes the token, so nothing here holds a secret.
public struct LeafNotion: Sendable {
    let client: NotionClient

    public init(client: NotionClient = NotionClient()) { self.client = client }

    public func workspaceName(_ token: String) async throws -> String {
        let me = try await client.get(token, "users/me")
        if let ws = (me["bot"] as? JSON)?["workspace_name"] as? String, !ws.isEmpty { return ws }
        return me["name"] as? String ?? "Notion"
    }

    public func dataSources(_ token: String) async throws -> [DataSourceInfo] {
        let results = try await paginate { cursor in
            var body: JSON = ["filter": ["property": "object", "value": "data_source"], "page_size": 100]
            if let cursor { body["start_cursor"] = cursor }
            return try await client.post(token, "search", body)
        }
        var seen = Set<String>()
        return results.filter { $0["object"] as? String == "data_source" }.map(NotionJSON.dataSource).filter { seen.insert($0.id).inserted }
    }

    public func dataSource(_ token: String, _ id: String) async throws -> DataSourceInfo {
        NotionJSON.dataSource(try await client.get(token, "data_sources/\(id)"))
    }

    /// Adds 类型 / 卡片颜色 / 置顶 if missing.
    public func ensureSchema(_ token: String, _ source: DataSourceInfo) async throws -> DataSourceInfo {
        guard let missing = LeafSchema.missingProperties(source) else { return source }
        return NotionJSON.dataSource(try await client.patch(token, "data_sources/\(source.id)", ["properties": missing]))
    }

    public func rows(_ token: String, dataSourceId: String, titleProperty: String?) async throws -> [RemoteRow] {
        let results = try await paginate { cursor in
            var body: JSON = ["page_size": 100, "sorts": [["timestamp": "last_edited_time", "direction": "descending"]]]
            if let cursor { body["start_cursor"] = cursor }
            return try await client.post(token, "data_sources/\(dataSourceId)/query", body)
        }
        return results.filter { $0["object"] as? String == "page" && $0["in_trash"] as? Bool != true }.map { NotionJSON.row($0, titleProperty: titleProperty) }
    }

    public func createPage(_ token: String, dataSourceId: String, titleProperty: String, title: String, type: String) async throws -> String {
        let page = try await client.post(token, "pages", NotionJSON.newPage(dataSourceId: dataSourceId, titleProperty: titleProperty, title: title, type: type))
        guard let id = page["id"] as? String else { throw NotionFailure.invalidResponse }
        return id
    }

    public func updateEssay(_ token: String, titleProperty: String, pageId: String, title: String, color: NotionColor, pinned: Bool) async throws {
        _ = try await client.patch(token, "pages/\(pageId)", NotionJSON.essayProperties(titleProperty: titleProperty, title: title, color: color, pinned: pinned))
    }

    public func trashPage(_ token: String, _ pageId: String) async throws {
        do {
            _ = try await client.patch(token, "pages/\(pageId)", ["in_trash": true])
        } catch NotionFailure.notFound {}
    }

    public func children(_ token: String, _ blockId: String, limit: Int? = nil) async throws -> [JSON] {
        if let limit {
            let page = try await client.get(token, "blocks/\(blockId)/children", query: ["page_size": String(min(max(limit, 1), 100))])
            return page["results"] as? [JSON] ?? []
        }
        return try await paginate { cursor in
            try await client.get(token, "blocks/\(blockId)/children", query: ["page_size": "100", "start_cursor": cursor])
        }
    }

    public func wallScribbles(_ token: String, _ wallPageId: String) async throws -> [RemoteScribble] {
        try await children(token, wallPageId).compactMap(NotionJSON.wallScribble)
    }

    public func essayBody(_ token: String, _ pageId: String) async throws -> [RemoteBlock] {
        try await children(token, pageId).compactMap(NotionJSON.bodyBlock)
    }

    /// Appends blocks; returns the created block ids in order.
    public func append(_ token: String, _ parentId: String, _ blocks: [JSON], after: String? = nil, atStart: Bool = false) async throws -> [String] {
        let response = try await client.patch(token, "blocks/\(parentId)/children", NotionJSON.children(blocks, after: after, atStart: atStart))
        return (response["results"] as? [JSON] ?? []).compactMap { $0["id"] as? String }
    }

    public func block(_ token: String, _ blockId: String) async throws -> RemoteBlock? {
        NotionJSON.bodyBlock(try await client.get(token, "blocks/\(blockId)"))
    }

    public func updateBlock(_ token: String, _ blockId: String, _ body: JSON) async throws {
        _ = try await client.patch(token, "blocks/\(blockId)", body)
    }

    public func deleteBlock(_ token: String, _ blockId: String) async throws {
        do {
            _ = try await client.delete(token, "blocks/\(blockId)")
        } catch NotionFailure.notFound {
            // Already gone in Notion: the goal is reached.
        }
    }

    private func paginate(_ fetch: (String?) async throws -> JSON) async throws -> [JSON] {
        var out: [JSON] = []
        var seen = Set<String>()
        var cursor: String?
        repeat {
            let page = try await fetch(cursor)
            out += page["results"] as? [JSON] ?? []
            if page["has_more"] as? Bool == true, let next = page["next_cursor"] as? String, !next.isEmpty, seen.insert(next).inserted {
                cursor = next
            } else {
                cursor = nil
            }
        } while cursor != nil
        return out
    }
}
