import XCTest
@testable import LeafCore

final class NotionJSONTests: XCTestCase {
    func test_should_markBlockFixed_when_richTextHasFormatting() {
        let block: JSON = ["id": "b", "type": "paragraph", "paragraph": ["rich_text": [["type": "text", "plain_text": "粗", "annotations": ["bold": true]]]]]
        XCTAssertEqual(true, NotionJSON.bodyBlock(block)?.fixed)
    }

    func test_should_readTagsFromTagsField_when_anotherMultiSelectComesFirst() {
        let props: JSON = [
            "作者": ["type": "multi_select", "multi_select": [["name": "某人"]]],
            "Tags": ["type": "multi_select", "multi_select": [["name": "东京"], ["name": "雨"]]],
        ]
        XCTAssertEqual(["东京", "雨"], NotionJSON.tags(props))
    }

    func test_should_splitRichText_when_longerThan2000() {
        XCTAssertEqual(2, NotionJSON.richText(String(repeating: "字", count: 2001)).count)
    }

    func test_should_extractDashedId_when_givenANotionURL() {
        XCTAssertEqual("412d1115-e921-811e-b1a3-dd04b39981f8", NotionClient.extractId("https://app.notion.com/p/Leaf-412d1115e921811eb1a3dd04b39981f8?pvs=4"))
    }

    func test_should_roundTripCredential_when_oauth() {
        let c = NotionCredential.oauth(access: "a", refresh: "r")
        XCTAssertEqual(c, NotionCredential.decode(c.encode()))
        XCTAssertEqual(.integration("ntn_x"), NotionCredential.decode("ntn_x"))
    }

    func test_should_refuseCallback_when_stateDiffers() {
        let url = URL(string: "leaf://notion-oauth?code=c&state=bad")!
        if case .success = NotionOAuthRelay.code(from: url, expectedState: "good") { XCTFail("must refuse") }
        if case .success(let code) = NotionOAuthRelay.code(from: URL(string: "leaf://notion-oauth?code=c&state=good")!, expectedState: "good") {
            XCTAssertEqual("c", code)
        } else { XCTFail("must accept") }
    }
}
