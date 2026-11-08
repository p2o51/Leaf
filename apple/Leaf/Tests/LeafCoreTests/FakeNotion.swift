import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import LeafCore

/// A tiny in-memory Notion for sync tests: pages in one data source, each with top-level text blocks.
final class FakeNotion: NotionTransport, @unchecked Sendable {
    struct FakeBlock { var id: String; var type: String; var text: String }
    struct FakePage { var id: String; var title: String; var type: String; var edited: String; var blocks: [FakeBlock] }

    private let lock = NSLock()
    var pages: [FakePage] = []
    var calls: [String] = []
    private var counter = 0

    func page(_ id: String) -> FakePage? { lock.withLock { pages.first { $0.id == id } } }

    func addPage(_ title: String, type: String = "随笔", blocks: [(String, String)] = []) -> String {
        lock.withLock {
            counter += 1
            let id = "page-\(counter)"
            pages.append(FakePage(id: id, title: title, type: type, edited: "2026-11-08T10:00:0\(counter % 10).000Z", blocks: blocks.map { counter += 1; return FakeBlock(id: "blk-\(counter)", type: $0.0, text: $0.1) }))
            return id
        }
    }

    func send(_ request: URLRequest) async throws -> (Int, Int?, Data) {
        lock.lock()
        defer { lock.unlock() }
        let method = request.httpMethod ?? "GET"
        let path = request.url!.path.replacingOccurrences(of: "/v1/", with: "")
        calls.append("\(method) \(path)")
        let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSON } ?? [:]
        let parts = path.split(separator: "/").map(String.init)
        func ok(_ json: Any) -> (Int, Int?, Data) { (200, nil, try! JSONSerialization.data(withJSONObject: json)) }
        func blockJSON(_ b: FakeBlock) -> JSON {
            ["object": "block", "id": b.id, "type": b.type, "created_time": "2026-11-08T10:00:00.000Z", "has_children": false,
             b.type: ["rich_text": [["type": "text", "plain_text": b.text, "text": ["content": b.text]]], "color": "default"]]
        }
        func text(_ value: JSON?) -> String {
            ((value?["rich_text"] as? [JSON]) ?? []).map { ($0["text"] as? JSON)?["content"] as? String ?? "" }.joined()
        }
        switch (method, parts.first) {
        case ("POST", "pages"):
            counter += 1
            let props = body["properties"] as? JSON ?? [:]
            let title = ((props["Name"] as? JSON)?["title"] as? [JSON])?.compactMap { ($0["text"] as? JSON)?["content"] as? String }.joined() ?? ""
            let id = "page-\(counter)"
            pages.append(FakePage(id: id, title: title, type: "随笔", edited: "2026-11-08T11:00:00.000Z", blocks: []))
            return ok(["object": "page", "id": id])
        case ("PATCH", "pages"):
            if let i = pages.firstIndex(where: { $0.id == parts[1] }) {
                if body["in_trash"] as? Bool == true { pages.remove(at: i) }
                else if let t = ((body["properties"] as? JSON)?["Name"] as? JSON)?["title"] as? [JSON] {
                    pages[i].title = t.compactMap { ($0["text"] as? JSON)?["content"] as? String }.joined()
                }
            }
            return ok(["object": "page", "id": parts[1]])
        case ("POST", "data_sources"):
            let results: [JSON] = pages.map { p in
                ["object": "page", "id": p.id, "last_edited_time": p.edited, "created_time": "2026-11-01T00:00:00.000Z",
                 "properties": ["Name": ["type": "title", "title": [["plain_text": p.title]]],
                                "类型": ["type": "select", "select": ["name": p.type]]]]
            }
            return ok(["results": results, "has_more": false])
        case ("PATCH", "blocks") where parts.count == 3:
            guard let i = pages.firstIndex(where: { $0.id == parts[1] }) else { return (404, nil, Data("{}".utf8)) }
            var at = pages[i].blocks.count
            if let pos = body["position"] as? JSON {
                if pos["type"] as? String == "start" { at = 0 }
                if let after = (pos["after_block"] as? JSON)?["id"] as? String, let k = pages[i].blocks.firstIndex(where: { $0.id == after }) { at = k + 1 }
            }
            var created: [JSON] = []
            for child in body["children"] as? [JSON] ?? [] {
                counter += 1
                let type = child["type"] as! String
                let b = FakeBlock(id: "blk-\(counter)", type: type, text: text(child[type] as? JSON))
                pages[i].blocks.insert(b, at: at)
                at += 1
                created.append(blockJSON(b))
            }
            return ok(["results": created])
        case ("PATCH", "blocks"):
            for i in pages.indices {
                if let k = pages[i].blocks.firstIndex(where: { $0.id == parts[1] }) {
                    let type = pages[i].blocks[k].type
                    pages[i].blocks[k].text = text(body[type] as? JSON)
                    return ok(blockJSON(pages[i].blocks[k]))
                }
            }
            return (404, nil, Data("{}".utf8))
        case ("DELETE", "blocks"):
            for i in pages.indices { pages[i].blocks.removeAll { $0.id == parts[1] } }
            return ok(["object": "block", "id": parts[1]])
        case ("GET", "blocks"):
            guard let p = pages.first(where: { $0.id == parts[1] }) else { return (404, nil, Data("{}".utf8)) }
            return ok(["results": p.blocks.map(blockJSON), "has_more": false])
        default:
            return (400, nil, Data("{}".utf8))
        }
    }
}

struct FixedCredentials: Credentials {
    func token() async -> String? { "ntn_test" }
    func refreshed(stale: String) async -> String? { nil }
}
