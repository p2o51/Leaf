import Foundation

/// The nine Notion highlight colours plus "none". Scribbles keep it as a block's background colour
/// (`yellow_background`), essays as the 卡片颜色 select option (`yellow`); both names map here.
/// Same table as Android's `NotionColor`.
public enum NotionColor: String, Codable, CaseIterable, Sendable {
    case `default`, gray, brown, orange, yellow, green, blue, purple, pink, red

    public var apiName: String { rawValue }
    public var backgroundApiName: String { self == .default ? rawValue : "\(rawValue)_background" }

    public var label: String {
        switch self {
        case .default: return "默认"
        case .gray: return "灰"
        case .brown: return "棕"
        case .orange: return "橙"
        case .yellow: return "黄"
        case .green: return "绿"
        case .blue: return "蓝"
        case .purple: return "紫"
        case .pink: return "粉"
        case .red: return "红"
        }
    }

    public static func fromApi(_ name: String?) -> NotionColor {
        guard let name else { return .default }
        return allCases.first { $0.apiName == name || $0.backgroundApiName == name } ?? .default
    }
}

/// One top-level block of an essay, as Leaf keeps it on this Mac. Mirrors Android's `EssayBlockEntity`:
/// [id] is Leaf's own and never changes; [notionId] is filled once Notion has the block.
public struct Block: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var notionId: String?
    /// Notion's block type (paragraph, heading_2, image, divider, …).
    public var type: String
    /// The words of a text block, an image's caption, a file's name.
    public var text: String
    public var color: NotionColor
    /// Holds more in Notion than Leaf can write back (children, links, formatting), so it is only shown.
    public var fixed: Bool
    /// An image's or file's URL as Notion last signed it (expires after an hour).
    public var url: String?

    public init(id: String, notionId: String? = nil, type: String = "paragraph", text: String = "", color: NotionColor = .default, fixed: Bool = false, url: String? = nil) {
        self.id = id
        self.notionId = notionId
        self.type = type
        self.text = text
        self.color = color
        self.fixed = fixed
        self.url = url
    }

    public static let divider = "divider"

    /// Same content, ignoring where it lives in Notion: what decides whether a write is needed.
    public func sameContent(_ other: Block) -> Bool {
        type == other.type && text == other.text && color == other.color && fixed == other.fixed
    }
}

/// Every database row other than the wall: the page title, its 卡片颜色 / 置顶 / 标签, and the body.
public struct EssayMeta: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var pageId: String?
    public var title: String
    public var color: NotionColor
    public var pinned: Bool
    /// Notion's last_edited_time for pulled rows, the local time of the last edit for ours (ms).
    public var editedAt: Int64
    public var createdAt: Int64?
    public var tags: [String]
    /// The card's words before the body is loaded.
    public var preview: String
    /// Whether [Store] holds this essay's body as Notion last had it.
    public var bodyLoaded: Bool
    public var coverUrl: String?

    public init(id: String, pageId: String? = nil, title: String = "", color: NotionColor = .default, pinned: Bool = false, editedAt: Int64, createdAt: Int64? = nil, tags: [String] = [], preview: String = "", bodyLoaded: Bool = false, coverUrl: String? = nil) {
        self.id = id
        self.pageId = pageId
        self.title = title
        self.color = color
        self.pinned = pinned
        self.editedAt = editedAt
        self.createdAt = createdAt
        self.tags = tags
        self.preview = preview
        self.bodyLoaded = bodyLoaded
        self.coverUrl = coverUrl
    }
}

/// One line on the 涂鸦墙: a coloured paragraph block on the wall page, or an image with a caption.
public struct Scribble: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var notionId: String?
    public var text: String
    public var color: NotionColor
    public var createdAt: Int64
    public var imageUrl: String?

    public init(id: String, notionId: String? = nil, text: String, color: NotionColor = .default, createdAt: Int64, imageUrl: String? = nil) {
        self.id = id
        self.notionId = notionId
        self.text = text
        self.color = color
        self.createdAt = createdAt
        self.imageUrl = imageUrl
    }
}

/// Which database Leaf writes to. Not secret; the token lives in the Keychain.
public struct Connection: Codable, Equatable, Sendable {
    public var workspace: String
    public var dataSourceId: String
    public var databaseName: String
    public var titleProperty: String
    public var wallPageId: String

    public init(workspace: String, dataSourceId: String, databaseName: String, titleProperty: String, wallPageId: String) {
        self.workspace = workspace
        self.dataSourceId = dataSourceId
        self.databaseName = databaseName
        self.titleProperty = titleProperty
        self.wallPageId = wallPageId
    }
}

/// A change waiting to reach Notion. Same op names as Android's outbox.
public struct OutboxOp: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case saveEssay, saveBlock, deleteBlock, deleteEssay, saveScribble
    }

    public var seq: Int64
    public var kind: Kind
    /// A local essay / block / scribble id, or for deletes the Notion id.
    public var target: String
    public var attempts: Int

    public init(seq: Int64, kind: Kind, target: String, attempts: Int = 0) {
        self.seq = seq
        self.kind = kind
        self.target = target
        self.attempts = attempts
    }
}

public extension Date {
    var millis: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}
