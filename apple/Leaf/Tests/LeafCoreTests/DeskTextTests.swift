import XCTest
@testable import LeafCore

/// The same 21 cases as Android's DeskTextTest (Leaf PR #15): the cross-platform acceptance set for the
/// 写作台's Markdown, focus, style check and origins. Offsets are UTF-16 on both sides.
final class DeskTextTests: XCTestCase {
    private var n = 0
    private func newId() -> String { n += 1; return "n\(n)" }
    private func p(_ id: String, _ text: String, _ type: String = "paragraph") -> Block { Block(id: id, type: type, text: text) }
    private func sub(_ t: String, _ r: Range<Int>) -> String { string(Array(t.utf16)[r]) }

    func test_should_renderMarkdown_when_blocksHaveTypes() {
        let r = DeskText.render([p("a", "标题", "heading_2"), p("b", "正文"), p("c", "一", "bulleted_list_item"), p("d", "二", "bulleted_list_item"), p("e", "x", "numbered_list_item"), p("f", "y", "numbered_list_item")])
        XCTAssertEqual("## 标题\n\n正文\n\n- 一\n- 二\n1. x\n2. y", r.text)
        XCTAssertEqual([3, 7, 13, 17, 22, 27], r.starts)
    }

    func test_should_roundTrip_when_textIsUnchanged() {
        let blocks = [p("a", "标题", "heading_1"), p("b", "第一段\n软换行"), p("c", "引用", "quote"), p("d", "一", "bulleted_list_item")]
        let (out, gone) = DeskText.update(blocks, DeskText.render(blocks).text, newId: newId)
        XCTAssertEqual(blocks, out)
        XCTAssertTrue(gone.isEmpty)
    }

    func test_should_keepBlockId_when_aParagraphIsEdited() {
        let blocks = [p("a", "早上很安静。"), p("b", "下雨了。")]
        let (out, gone) = DeskText.update(blocks, "早上很安静，也很冷。\n\n下雨了。", newId: newId)
        XCTAssertEqual(["a", "b"], out.map(\.id))
        XCTAssertEqual("早上很安静，也很冷。", out[0].text)
        XCTAssertTrue(gone.isEmpty)
    }

    func test_should_addNewBlock_when_aBlankLineSplitsAParagraph() {
        let (out, _) = DeskText.update([p("a", "一二三")], "一二三\n\n四五", newId: newId)
        XCTAssertEqual(["a", "n1"], out.map(\.id))
        XCTAssertEqual("四五", out[1].text)
    }

    func test_should_reportGoneBlock_when_aParagraphIsDeleted() {
        let (out, gone) = DeskText.update([p("a", "一"), p("b", "二"), p("c", "三")], "一\n\n三", newId: newId)
        XCTAssertEqual(["a", "c"], out.map(\.id))
        XCTAssertEqual(["b"], gone)
    }

    func test_should_changeType_when_aMarkerIsTyped() {
        let (out, _) = DeskText.update([p("a", "标题")], "## 标题", newId: newId)
        XCTAssertEqual("heading_2", out.first?.type)
        XCTAssertEqual("a", out.first?.id)
    }

    func test_should_keepHashtagAsText_when_noSpaceFollowsHash() {
        XCTAssertEqual(["paragraph"], DeskText.parse("#东京 下雨").map(\.type))
    }

    func test_should_splitListItems_when_onConsecutiveLines() {
        XCTAssertEqual(["一", "二\n继续二"], DeskText.parse("- 一\n- 二\n继续二").map(\.text))
    }

    func test_should_keepColour_when_aColouredParagraphIsEdited() {
        let (out, _) = DeskText.update([Block(id: "a", text: "涂鸦", color: .yellow)], "涂鸦改了", newId: newId)
        XCTAssertEqual(.yellow, out.first?.color)
    }

    func test_should_showPhotoBetweenRuns_when_pageHasAPhoto() {
        let photo = Block(id: "ph", type: "image", url: "https://example.com/a.jpg")
        let parts = DeskText.parts([p("a", "一"), photo, p("b", "二")])
        XCTAssertEqual(3, parts.count)
        if case .shown = parts[1] {} else { XCTFail("photo is shown") }
        XCTAssertEqual(["a", "ph", "b"], DeskText.blocks(parts).map(\.id))
    }

    func test_should_endWithAnEmptyRun_when_pageEndsWithAPhoto() {
        let parts = DeskText.parts([Block(id: "ph", type: "image")])
        XCTAssertEqual(2, parts.count)
        if case .text(_, let blocks) = parts.last! { XCTAssertTrue(blocks.isEmpty) } else { XCTFail("ends with a run") }
    }

    func test_should_showFixedParagraph_when_notionHoldsMore() {
        let parts = DeskText.parts([Block(id: "a", text: "粗体", fixed: true)])
        if case .shown = parts.first! {} else { XCTFail("fixed paragraph is shown") }
    }

    func test_should_showParagraphAsIs_when_itsWordsLookLikeMarkdown() {
        let parts = DeskText.parts([p("a", "# 不是标题"), p("b", "正文"), p("c", "引用\n> 还是引用", "quote")])
        if case .shown = parts[0] {} else { XCTFail() }
        if case .text(_, let blocks) = parts[1] { XCTAssertEqual(["b"], blocks.map(\.id)) } else { XCTFail() }
        if case .shown = parts[2] {} else { XCTFail() }
    }

    func test_should_findChineseSentence_when_caretIsInside() {
        let t = "早上很安静。窗外下雨了！"
        XCTAssertEqual("窗外下雨了！", sub(t, DeskFocus.sentence(t, 8)))
        XCTAssertEqual("早上很安静。", sub(t, DeskFocus.sentence(t, 2)))
    }

    func test_should_notEndSentence_when_dotIsInsideANumber() {
        let t = "圆周率是 3.14 左右。"
        XCTAssertEqual(t, sub(t, DeskFocus.sentence(t, 2)))
    }

    func test_should_findParagraph_when_caretIsInSecondBlock() {
        let t = "第一段。\n\n第二段，还有。"
        XCTAssertEqual("第二段，还有。", sub(t, DeskFocus.paragraph(t, 8)))
    }

    func test_should_markFillersAndHalfPunct_when_styleCheckIsOn() {
        let hits = DeskStyle.check("我其实想写,但是非常忙。", Set(StyleRule.allCases))
        XCTAssertEqual([.filler, .punct, .degree], hits.map(\.rule))
    }

    func test_should_markEveryDe_when_aSentenceHasTooMany() {
        XCTAssertEqual(4, DeskStyle.check("我的书的封面的颜色的样子。", [.manyDe]).count)
    }

    func test_should_markNothing_when_noRuleIsOn() {
        XCTAssertTrue(DeskStyle.check("其实非常", []).isEmpty)
    }

    func test_should_markPastedCharacters_when_textIsPastedInTheMiddle() {
        let before = "开头结尾"
        let after = "开头引用结尾"
        let origins = DeskOrigins.splice(Array(repeating: .typed, count: before.utf16.count), before, after, .pasted)
        XCTAssertEqual([OriginSpan(start: 2, end: 4, origin: .pasted)], DeskOrigins.spans(origins, 0, after.utf16.count))
    }

    func test_should_roundTripSpans_when_encoded() {
        let map: [String: [OriginSpan]] = ["a": [OriginSpan(start: 1, end: 3, origin: .pasted)], "b": []]
        XCTAssertEqual(["a": [OriginSpan(start: 1, end: 3, origin: .pasted)]], DeskOrigins.decode(DeskOrigins.encode(map)))
    }

    func test_should_layStoredSpans_when_runIsRendered() {
        let laid = DeskOrigins.lay(6, [0, 4], [[], [OriginSpan(start: 0, end: 2, origin: .pasted)]])
        XCTAssertEqual([.typed, .typed, .typed, .typed, .pasted, .pasted], laid)
    }
}
