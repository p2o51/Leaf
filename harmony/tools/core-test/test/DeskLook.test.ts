import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DeskLook, Ink } from '../src/core/DeskLook';
import { DeskFocus } from '../src/core/DeskText';
import { DeskStyle, Origin, StyleRule } from '../src/core/DeskStyle';

const show = (text: string, segs: ReturnType<typeof DeskLook.segments>): string[] =>
  segs.map((s) => `${text.substring(s.start, s.end)}|${s.ink}${s.faded ? ',faded' : ''}${s.bold ? ',bold' : ''}${s.shaded ? ',shaded' : ''}`);

test('should_fadeOutsideSentence_when_focusIsOn', () => {
  const t = '早上很安静。窗外下雨了！';
  const segs = DeskLook.segments(t, null, [], DeskFocus.sentence(t, 8));
  assert.deepEqual(show(t, segs), ['早上很安静。|text,faded', '窗外下雨了！|text']);
});

test('should_dimMarksAndBoldHeading_when_runHasHeading', () => {
  const t = '## 标题\n\n正文';
  assert.deepEqual(show(t, DeskLook.segments(t, null, [], null)), ['## |mark', '标题|text,bold', '\n\n正文|text']);
});

test('should_colourPastedAndStrikeHits_when_bothAreOn', () => {
  const t = '我其实想写';
  const origins = [Origin.Typed, Origin.Typed, Origin.Typed, Origin.Pasted, Origin.Pasted];
  const hits = DeskStyle.check(t, new Set([StyleRule.Filler]));
  assert.deepEqual(show(t, DeskLook.segments(t, origins, hits, null)), ['我|text', '其实|struck', '想写|pasted']);
});

test('should_coverWholeText_when_segmentsAreJoined', () => {
  const t = '- 一\n- 二\n\n> 引用，其实很长。还有一句！';
  const segs = DeskLook.segments(t, null, DeskStyle.check(t, new Set([StyleRule.Filler, StyleRule.Long])), DeskFocus.paragraph(t, 3));
  assert.equal(segs[0].start, 0);
  assert.equal(segs[segs.length - 1].end, t.length);
  for (let i = 1; i < segs.length; i++) assert.equal(segs[i].start, segs[i - 1].end);
  assert.ok(segs.some((s) => s.ink === Ink.Struck));
});
