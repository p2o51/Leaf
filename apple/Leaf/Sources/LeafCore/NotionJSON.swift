import Foundation

// Ported from Android's data/notion/NotionJson.kt and LeafSchema: the same database contract, so the Mac
// and the phone read and write the same rows the same way.

public typealias JSON = [String: Any]

public struct PropertyInfo: Equatable, Sendable {
    public var id: String
    public var name: String
    public var type: String
}

public struct DataSourceInfo: Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var properties: [PropertyInfo]

    public var titleProperty: String? { properties.first { $0.type == "title" }?.name }
    func has(_ name: String, _ type: String) -> Bool { properties.contains { $0.name == name && $0.type == type } }
}

public struct RemoteRow: Equatable, Sendable {
    public var pageId: String
    public var title: String
    public var isWall: Bool
    public var color: NotionColor
    public var pinned: Bool
    public var coverUrl: String?
    public var editedAt: Int64
    public var tags: [String]
    public var createdAt: Int64?
}

public struct RemoteBlock: Equatable, Sendable {
    public var blockId: String
    public var type: String
    public var text: String
    public var color: NotionColor
    public var url: String?
    public var fixed: Bool
}

public struct RemoteScribble: Equatable, Sendable {
    public var blockId: String
    public var text: String
    public var color: NotionColor
    public var createdAt: Int64
    public var imageUrl: String?
}

public enum LeafSchema {
    public static let type = "类型"
    public static let typeEssay = "随笔"
    public static let typeWall = "涂鸦墙"
    public static let cardColor = "卡片颜色"
    public static let pinned = "置顶"
    public static let tags = "标签"
    public static let wallTitle = "涂鸦墙"

    /// The properties PATCH body for whatever is missing, or nil when the schema is complete.
    static func missingProperties(_ source: DataSourceInfo) -> JSON? {
        var add: JSON = [:]
        if !source.has(type, "select") {
            add[type] = ["select": ["options": [["name": typeEssay, "color": "default"], ["name": typeWall, "color": "yellow"]]]]
        }
        if !source.has(cardColor, "select") {
            add[cardColor] = ["select": ["options": NotionColor.allCases.map { ["name": $0.label, "color": $0.apiName] }]]
        }
        if !source.has(pinned, "checkbox") { add[pinned] = ["checkbox": JSON()] }
        return add.isEmpty ? nil : add
    }
}

public enum NotionJSON {
    static let tagFieldNames = [LeafSchema.tags, "Tags", "Tag", "Labels", "Label"]
    public static let fileTypes: Set<String> = ["file", "pdf", "video", "audio"]
    public static let textTypes: Set<String> = ["paragraph", "heading_1", "heading_2", "heading_3", "quote", "bulleted_list_item", "numbered_list_item", "to_do", "callout", "toggle"]

    static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    static let isoPlain = ISO8601DateFormatter()

    static func millis(_ value: Any?) -> Int64? {
        guard let s = value as? String, !s.isEmpty else { return nil }
        return (iso.date(from: s) ?? isoPlain.date(from: s))?.millis
    }

    public static func dataSource(_ json: JSON) -> DataSourceInfo {
        let props = json["properties"] as? JSON ?? [:]
        let list = props.compactMap { key, value -> PropertyInfo? in
            guard let p = value as? JSON else { return nil }
            return PropertyInfo(id: p["id"] as? String ?? "", name: p["name"] as? String ?? key, type: p["type"] as? String ?? "")
        }.sorted { $0.name < $1.name }
        let name = plain(json["title"] as? [Any])
        return DataSourceInfo(id: json["id"] as? String ?? "", name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "未命名数据库" : name, properties: list)
    }

    public static func row(_ json: JSON, titleProperty: String?) -> RemoteRow {
        let props = json["properties"] as? JSON ?? [:]
        var title = ""
        if let t = titleProperty, let p = props[t] as? JSON, let arr = p["title"] as? [Any] {
            title = plain(arr)
        } else if let p = props.values.compactMap({ $0 as? JSON }).first(where: { $0["type"] as? String == "title" }) {
            title = plain(p["title"] as? [Any])
        }
        let type = ((props[LeafSchema.type] as? JSON)?["select"] as? JSON)?["name"] as? String
        let colorOption = (props[LeafSchema.cardColor] as? JSON)?["select"] as? JSON
        return RemoteRow(
            pageId: json["id"] as? String ?? "",
            title: title,
            isWall: type == LeafSchema.typeWall || ((type ?? "").isEmpty && title.trimmingCharacters(in: .whitespaces) == LeafSchema.wallTitle),
            color: colorOption.map(optionColor) ?? .default,
            pinned: (props[LeafSchema.pinned] as? JSON)?["checkbox"] as? Bool == true,
            coverUrl: fileUrl(json["cover"] as? JSON),
            editedAt: millis(json["last_edited_time"]) ?? 0,
            tags: tags(props),
            createdAt: millis(json["created_time"])
        )
    }

    /// Tags are a multi-select named like tags (标签, then Tags / Tag / Labels in any case), else the row's
    /// first multi-select. Read only; options are edited in Notion.
    public static func tags(_ props: JSON) -> [String] {
        let multi = props.compactMap { key, value -> (String, JSON)? in
            guard let p = value as? JSON, p["type"] as? String == "multi_select" else { return nil }
            return (key, p)
        }.sorted { $0.0 < $1.0 }
        let field = tagFieldNames.lazy.compactMap { name in
            multi.first { $0.0.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(name) == .orderedSame }?.1
        }.first ?? multi.first?.1
        guard let options = field?["multi_select"] as? [Any] else { return [] }
        return options.compactMap { ($0 as? JSON)?["name"] as? String }.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    static func optionColor(_ option: JSON) -> NotionColor {
        let byColor = NotionColor.fromApi(option["color"] as? String)
        if byColor != .default { return byColor }
        return NotionColor.allCases.first { $0.label == option["name"] as? String } ?? .default
    }

    public static func wallScribble(_ block: JSON) -> RemoteScribble? {
        if block["in_trash"] as? Bool == true { return nil }
        guard let type = block["type"] as? String, let value = block[type] as? JSON, let id = block["id"] as? String else { return nil }
        let created = millis(block["created_time"]) ?? 0
        switch type {
        case "paragraph":
            let text = plain(value["rich_text"] as? [Any]).trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : RemoteScribble(blockId: id, text: text, color: .fromApi(value["color"] as? String), createdAt: created, imageUrl: nil)
        case "image":
            return RemoteScribble(blockId: id, text: plain(value["caption"] as? [Any]).trimmingCharacters(in: .whitespacesAndNewlines), color: .default, createdAt: created, imageUrl: fileUrl(value))
        default:
            return nil
        }
    }

    public static func bodyBlock(_ block: JSON) -> RemoteBlock? {
        if block["in_trash"] as? Bool == true { return nil }
        guard let type = block["type"] as? String, let id = block["id"] as? String else { return nil }
        let value = block[type] as? JSON ?? [:]
        let text: String
        if textTypes.contains(type) {
            text = plain(value["rich_text"] as? [Any])
        } else if type == "image" {
            text = plain(value["caption"] as? [Any])
        } else if fileTypes.contains(type) {
            text = fileName(value)
        } else {
            text = ""
        }
        let url = (type == "image" || fileTypes.contains(type)) ? fileUrl(value) : nil
        let fixed = textTypes.contains(type) && (block["has_children"] as? Bool == true || type == "callout" || type == "toggle" ||
            value["is_toggleable"] as? Bool == true || value["checked"] as? Bool == true || !isPlain(value["rich_text"] as? [Any]))
        return RemoteBlock(blockId: id, type: type, text: text, color: .fromApi(value["color"] as? String), url: url, fixed: fixed)
    }

    /// Whether rich text is only unformatted words, the one thing [textBlock] writes back exactly.
    static func isPlain(_ array: [Any]?) -> Bool {
        guard let array else { return true }
        return array.allSatisfy { item in
            guard let part = item as? JSON else { return true }
            let a = part["annotations"] as? JSON
            let link = (part["text"] as? JSON)?["link"]
            let linkless = link == nil || link is NSNull
            let href = part["href"]
            let hrefless = href == nil || href is NSNull
            let styled = ["bold", "italic", "strikethrough", "underline", "code"].contains { a?[$0] as? Bool == true }
            return (part["type"] as? String ?? "text") == "text" && linkless && hrefless && !styled && (a?["color"] as? String ?? "default") == "default"
        }
    }

    public static func plain(_ array: [Any]?) -> String {
        guard let array else { return "" }
        return array.map { item -> String in
            guard let part = item as? JSON else { return "" }
            if let p = part["plain_text"] as? String, !p.isEmpty { return p }
            return (part["text"] as? JSON)?["content"] as? String ?? ""
        }.joined()
    }

    static func fileName(_ value: JSON) -> String {
        let name = (value["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        if !name.isEmpty { return name }
        let caption = plain(value["caption"] as? [Any]).trimmingCharacters(in: .whitespaces)
        if !caption.isEmpty { return caption }
        guard let url = fileUrl(value) else { return "" }
        let last = url.split(separator: "?").first.map(String.init)?.split(separator: "/").last.map(String.init) ?? ""
        return last.removingPercentEncoding ?? last
    }

    static func fileUrl(_ file: JSON?) -> String? {
        guard let file, let type = file["type"] as? String else { return nil }
        let url: String?
        switch type {
        case "external": url = (file["external"] as? JSON)?["url"] as? String
        case "file": url = (file["file"] as? JSON)?["url"] as? String
        default: url = nil
        }
        guard let url, !url.isEmpty else { return nil }
        return url
    }

    // ---- request bodies ----

    /// Notion caps one text object at 2000 characters, so long text becomes several.
    public static func richText(_ text: String) -> [JSON] {
        // Chunked by UTF-16 units like Android, without splitting a surrogate pair.
        var out: [JSON] = []
        var chunk = ""
        var count = 0
        for ch in text {
            let n = ch.utf16.count
            if count + n > 2000 {
                out.append(["type": "text", "text": ["content": chunk]])
                chunk = ""
                count = 0
            }
            chunk.append(ch)
            count += n
        }
        if !chunk.isEmpty { out.append(["type": "text", "text": ["content": chunk]]) }
        return out
    }

    public static func textBlock(_ type: String, _ text: String, _ color: NotionColor) -> JSON {
        var value: JSON = ["rich_text": richText(text), "color": color.backgroundApiName]
        if type == "to_do" { value["checked"] = false }
        return ["object": "block", "type": type, type: value]
    }

    public static func divider() -> JSON { ["object": "block", "type": "divider", "divider": JSON()] }

    public static func textUpdate(_ type: String, _ text: String, _ color: NotionColor?) -> JSON {
        var value: JSON = ["rich_text": richText(text)]
        if let color { value["color"] = color.backgroundApiName }
        return [type: value]
    }

    public static func captionUpdate(_ caption: String) -> JSON { ["image": ["caption": richText(caption)]] }

    public static func children(_ blocks: [JSON], after: String? = nil, atStart: Bool = false) -> JSON {
        var body: JSON = ["children": blocks]
        // 2026-03-11 replaced the top-level "after" with a position object; omitted means the end.
        if let after {
            body["position"] = ["type": "after_block", "after_block": ["id": after]]
        } else if atStart {
            body["position"] = ["type": "start"]
        }
        return body
    }

    public static func newPage(dataSourceId: String, titleProperty: String, title: String, type: String) -> JSON {
        [
            "parent": ["type": "data_source_id", "data_source_id": dataSourceId],
            "properties": [
                titleProperty: ["title": richText(title)],
                LeafSchema.type: ["select": ["name": type]],
            ] as JSON,
        ]
    }

    public static func essayProperties(titleProperty: String, title: String, color: NotionColor, pinned: Bool) -> JSON {
        let select: Any = color == .default ? NSNull() : ["name": color.label]
        return ["properties": [
            titleProperty: ["title": richText(title)],
            LeafSchema.cardColor: ["select": select],
            LeafSchema.pinned: ["checkbox": pinned],
        ] as JSON]
    }
}
