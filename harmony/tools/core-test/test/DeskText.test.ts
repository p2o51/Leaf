// The Android DeskTextTest.kt (Leaf PR #15), case for case, so both apps read and write Markdown alike.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BlockKind, EssayBlock, NotionColor } from '../src/core/Models';
import { DeskFocus, DeskPartKind, DeskText, Span } from '../src/core/DeskText';
import { DeskOrigins, DeskStyle, Origin, OriginSpan, STYLE_RULES, StyleRule } from '../src/core/DeskStyle';

let n = 0;
const newId = (): string => `n${++n}`;
const p = (id: string, text: string, type = 'paragraph'): EssayBlock => EssayBlock.paragraph(id, text, type);
const sub = (t: string, s: Span): string => t.substring(s.start, s.end);
const photo = (): EssayBlock => new EssayBlock('ph', BlockKind.Photo, 'image', '', NotionColor.Default, false, 'file://moss.jpg');

test('should_renderMarkdown_when_blocksHaveTypes', () => {
  const r = DeskText.render([p('a', '标题', 'heading_2'), p('b', '正文'), p('c', '一', 'bulleted_list_item'), p('d', '二', 'bulleted_list_item'), p('e', 'x', 'numbered_list_item'), p('f', 'y', 'numbered_list_item')]);
  assert.equal(r.text, '## 标题\n\n正文\n\n- 一\n- 二\n1. x\n2. y');
  assert.deepEqual(r.starts, [3, 7, 13, 17, 22, 27]);
});

test('should_roundTrip_when_textIsUnchanged', () => {
  const blocks = [p('a', '标题', 'heading_1'), p('b', '第一段\n软换行'), p('c', '引用', 'quote'), p('d', '一', 'bulleted_list_item')];
  const u = DeskText.update(blocks, DeskText.render(blocks).text, newId);
  assert.equal(u.blocks.length, blocks.length);
  u.blocks.forEach((b, i) => assert.ok(b.sameAs(blocks[i]), `block ${i}`));
  assert.equal(u.gone.size, 0);
});

test('should_keepBlockId_when_aParagraphIsEdited', () => {
  const u = DeskText.update([p('a', '早上很安静。'), p('b', '下雨了。')], '早上很安静，也很冷。\n\n下雨了。', newId);
  assert.deepEqual(u.blocks.map((b) => b.id), ['a', 'b']);
  assert.equal(u.blocks[0].text, '早上很安静，也很冷。');
  assert.equal(u.gone.size, 0);
});

test('should_addNewBlock_when_aBlankLineSplitsAParagraph', () => {
  n = 0;
  const u = DeskText.update([p('a', '一二三')], '一二三\n\n四五', newId);
  assert.deepEqual(u.blocks.map((b) => b.id), ['a', 'n1']);
  assert.equal(u.blocks[1].text, '四五');
});

test('should_reportGoneBlock_when_aParagraphIsDeleted', () => {
  const u = DeskText.update([p('a', '一'), p('b', '二'), p('c', '三')], '一\n\n三', newId);
  assert.deepEqual(u.blocks.map((b) => b.id), ['a', 'c']);
  assert.deepEqual([...u.gone], ['b']);
});

test('should_changeType_when_aMarkerIsTyped', () => {
  const u = DeskText.update([p('a', '标题')], '## 标题', newId);
  assert.equal(u.blocks.length, 1);
  assert.equal(u.blocks[0].type, 'heading_2');
  assert.equal(u.blocks[0].id, 'a');
});

test('should_keepHashtagAsText_when_noSpaceFollowsHash', () => {
  const lines = DeskText.parse('#东京 下雨');
  assert.equal(lines.length, 1);
  assert.equal(lines[0].type, 'paragraph');
});

test('should_splitListItems_when_onConsecutiveLines', () => {
  assert.deepEqual(DeskText.parse('- 一\n- 二\n继续二').map((l) => l.text), ['一', '二\n继续二']);
});

test('should_keepColour_when_aColouredParagraphIsEdited', () => {
  const u = DeskText.update([EssayBlock.paragraph('a', '涂鸦', 'paragraph', NotionColor.Yellow)], '涂鸦改了', newId);
  assert.equal(u.blocks[0].color, NotionColor.Yellow);
});

test('should_showPhotoBetweenRuns_when_pageHasAPhoto', () => {
  const parts = DeskText.parts([p('a', '一'), photo(), p('b', '二')]);
  assert.equal(parts.length, 3);
  assert.equal(parts[1].kind, DeskPartKind.Shown);
  assert.deepEqual(DeskText.blocks(parts).map((b) => b.id), ['a', 'ph', 'b']);
});

test('should_endWithAnEmptyRun_when_pageEndsWithAPhoto', () => {
  const parts = DeskText.parts([photo()]);
  assert.equal(parts.length, 2);
  assert.equal(parts[1].kind, DeskPartKind.Text);
  assert.equal(parts[1].blocks.length, 0);
});

test('should_showFixedParagraph_when_notionHoldsMore', () => {
  const parts = DeskText.parts([EssayBlock.paragraph('a', '粗体', 'paragraph', NotionColor.Default, true)]);
  assert.equal(parts[0].kind, DeskPartKind.Shown);
});

test('should_showParagraphAsIs_when_itsWordsLookLikeMarkdown', () => {
  const parts = DeskText.parts([p('a', '# 不是标题'), p('b', '正文'), p('c', '引用\n> 还是引用', 'quote')]);
  assert.equal(parts[0].kind, DeskPartKind.Shown);
  assert.deepEqual(parts[1].blocks.map((b) => b.id), ['b']);
  assert.equal(parts[2].kind, DeskPartKind.Shown);
});

test('should_findChineseSentence_when_caretIsInside', () => {
  const t = '早上很安静。窗外下雨了！';
  assert.equal(sub(t, DeskFocus.sentence(t, 8)), '窗外下雨了！');
  assert.equal(sub(t, DeskFocus.sentence(t, 2)), '早上很安静。');
});

test('should_notEndSentence_when_dotIsInsideANumber', () => {
  const t = '圆周率是 3.14 左右。';
  assert.equal(sub(t, DeskFocus.sentence(t, 2)), t);
});

test('should_findParagraph_when_caretIsInSecondBlock', () => {
  const t = '第一段。\n\n第二段，还有。';
  assert.equal(sub(t, DeskFocus.paragraph(t, 8)), '第二段，还有。');
});

test('should_markFillersAndHalfPunct_when_styleCheckIsOn', () => {
  const hits = DeskStyle.check('我其实想写,但是非常忙。', new Set(STYLE_RULES));
  assert.deepEqual(hits.map((h) => h.rule), [StyleRule.Filler, StyleRule.Punct, StyleRule.Degree]);
});

test('should_markEveryDe_when_aSentenceHasTooMany', () => {
  assert.equal(DeskStyle.check('我的书的封面的颜色的样子。', new Set([StyleRule.ManyDe])).length, 4);
});

test('should_markNothing_when_noRuleIsOn', () => {
  assert.equal(DeskStyle.check('其实非常', new Set()).length, 0);
});

test('should_markPastedCharacters_when_textIsPastedInTheMiddle', () => {
  const before = '开头结尾';
  const after = '开头引用结尾';
  const origins = DeskOrigins.splice(Array.from(before, () => Origin.Typed), before, after, Origin.Pasted);
  assert.deepEqual(DeskOrigins.spans(origins, 0, after.length), [new OriginSpan(2, 4, Origin.Pasted)]);
});

test('should_roundTripSpans_when_encoded', () => {
  const map = new Map<string, OriginSpan[]>([['a', [new OriginSpan(1, 3, Origin.Pasted)]], ['b', []]]);
  const back = DeskOrigins.decode(DeskOrigins.encode(map));
  assert.deepEqual([...back.keys()], ['a']);
  assert.deepEqual(back.get('a'), [new OriginSpan(1, 3, Origin.Pasted)]);
});

test('should_layStoredSpans_when_runIsRendered', () => {
  const laid = DeskOrigins.lay(6, [0, 4], [[], [new OriginSpan(0, 2, Origin.Pasted)]]);
  assert.deepEqual(laid, [Origin.Typed, Origin.Typed, Origin.Typed, Origin.Typed, Origin.Pasted, Origin.Pasted]);
});

test('should_readAndroidOriginsFile_when_sameFormat', () => {
  // The Android app writes exactly this text (DeskOrigins.encode), so a file copied across reads the same.
  const back = DeskOrigins.decode('blk-1\t0-4-P 9-12-P\nblk-2\t3-5-P');
  assert.deepEqual(back.get('blk-1'), [new OriginSpan(0, 4, Origin.Pasted), new OriginSpan(9, 12, Origin.Pasted)]);
  assert.equal(back.get('blk-2')?.length, 1);
});
