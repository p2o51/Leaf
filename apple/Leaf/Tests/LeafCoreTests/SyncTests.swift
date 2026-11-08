import XCTest
@testable import LeafCore

final class SyncTests: XCTestCase {
    private var dir: URL!
    private var fake: FakeNotion!
    private var store: Store!
    private var sync: SyncEngine!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("leaf-test-\(UUID().uuidString)")
        fake = FakeNotion()
        store = Store(dir: dir)
        let notion = LeafNotion(client: NotionClient(transport: fake, base: URL(string: "http://localhost/v1/")!, intervalMillis: 0))
        sync = SyncEngine(store: store, notion: notion, credentials: FixedCredentials())
        let wall = fake.addPage("涂鸦墙", type: "涂鸦墙")
        await store.connect(Connection(workspace: "ws", dataSourceId: "ds", databaseName: "随笔", titleProperty: "Name", wallPageId: wall))
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func test_should_createPageAndBlocksInOrder_when_aNewEssayIsWritten() async {
        let meta = await store.newEssay()
        let (blocks, _) = DeskText.update([], "第一段\n\n## 小标题\n\n第三段", newId: { UUID().uuidString })
        await store.updateEssay(meta.id, title: "雨天", blocks: blocks)
        let result = await sync.run(pull: false)
        XCTAssertEqual(.done, result)
        let pageId = await store.essay(meta.id)?.pageId
        XCTAssertNotNil(pageId)
        let page = fake.page(pageId!)
        XCTAssertEqual("雨天", page?.title)
        XCTAssertEqual(["第一段", "小标题", "第三段"], page?.blocks.map(\.text))
        XCTAssertEqual(["paragraph", "heading_2", "paragraph"], page?.blocks.map(\.type))
        let pendingAfter = await store.outbox
        XCTAssertTrue(pendingAfter.isEmpty)
    }

    func test_should_updateBlockInPlace_when_aParagraphIsEdited() async {
        let pageId = fake.addPage("旧文", blocks: [("paragraph", "早上很安静。"), ("paragraph", "下雨了。")])
        _ = await sync.run()
        guard let meta = await store.essays.first(where: { $0.pageId == pageId }) else { return XCTFail("pulled") }
        await sync.loadBody(meta.id)
        let before = await store.blocks(meta.id)
        XCTAssertEqual(2, before.count)
        let (blocks, gone) = DeskText.update(before, "早上很安静，也很冷。\n\n下雨了。", newId: { UUID().uuidString })
        XCTAssertTrue(gone.isEmpty)
        await store.updateEssay(meta.id, title: "旧文", blocks: blocks)
        _ = await sync.run(pull: false)
        XCTAssertEqual(["早上很安静，也很冷。", "下雨了。"], fake.page(pageId)?.blocks.map(\.text))
        XCTAssertFalse(fake.calls.contains { $0.hasPrefix("DELETE") })
    }

    func test_should_notBringBackADeletedBlock_when_refreshingBeforeTheDeleteWentOut() async {
        let pageId = fake.addPage("文", blocks: [("paragraph", "留下"), ("paragraph", "删掉")])
        _ = await sync.run()
        let meta = await store.essays.first { $0.pageId == pageId }!
        await sync.loadBody(meta.id)
        let blocks = await store.blocks(meta.id)
        await store.removeBlocks(meta.id, [blocks[1].id])
        await sync.refreshBody(meta.id)
        let left = await store.blocks(meta.id).map(\.text)
        XCTAssertEqual(["留下"], left)
    }

    func test_should_keepLocalEdits_when_theOpenEssayIsFetchedAgain() async {
        let pageId = fake.addPage("文", blocks: [("paragraph", "原文")])
        _ = await sync.run()
        let meta = await store.essays.first { $0.pageId == pageId }!
        await sync.loadBody(meta.id)
        var blocks = await store.blocks(meta.id)
        blocks[0].text = "本机改的"
        await store.updateEssay(meta.id, title: "文", blocks: blocks)
        await sync.refreshBody(meta.id)
        let kept = await store.blocks(meta.id).map(\.text)
        XCTAssertEqual(["本机改的"], kept)
    }

    func test_should_notPullBackAnEssay_when_itWasDeletedHere() async {
        let pageId = fake.addPage("要删的")
        _ = await sync.run()
        let meta = await store.essays.first { $0.pageId == pageId }!
        await store.deleteEssay(meta.id)
        // A pull before the trash goes out must not undo the delete; the run pushes first anyway.
        _ = await sync.run()
        XCTAssertNil(fake.page(pageId))
        let back = await store.essays.contains { $0.pageId == pageId }
        XCTAssertFalse(back)
    }

    func test_should_skipTheWallRow_when_pullingEssays() async {
        _ = fake.addPage("一篇")
        _ = await sync.run()
        let titles = await store.essays.map(\.title)
        XCTAssertEqual(["一篇"], titles)
    }
}
