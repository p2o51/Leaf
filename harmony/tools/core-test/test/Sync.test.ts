import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EssayBlock, Essay, FilterKind, HomeFilter, NotionColor } from '../src/core/Models';
import { LeafSchema, NotionBlock, NotionJson, NotionPage, DataSourceInfo, PropertyInfo, RemoteBlock } from '../src/core/NotionJson';
import { OutboxOp, SyncRules } from '../src/core/SyncRules';
import { TagRules } from '../src/core/Tags';

const synced = (id: string, blockId: string, text: string): EssayBlock => {
  const b = EssayBlock.paragraph(id, text);
  b.blockId = blockId;
  return b;
};
const remote = (blockId: string, text: string): RemoteBlock => new RemoteBlock(blockId, 'paragraph', text, NotionColor.Default, '', false);

test('should_dropPendingDeletes_when_openEssayIsFetchedAgain', () => {
  let n = 0;
  const before = [synced('a', 'A', '一'), synced('b', 'B', '二')];
  const body = SyncRules.fetchedBody(before, [remote('A', '一改'), remote('X', '删了还没发'), remote('C', '新')], new Set(['X']), () => `n${++n}`);
  assert.deepEqual(body.map((b) => b.id), ['a', 'n1']);
  assert.deepEqual(body.map((b) => b.blockId), ['A', 'C']);
  assert.equal(body[0].text, '一改');
});

test('should_notRefresh_when_aBlockOfTheEssayWaits', () => {
  const blocks = [synced('a', 'A', '一')];
  assert.equal(SyncRules.canRefresh('e', blocks, new Set(['a'])), false);
  assert.equal(SyncRules.canRefresh('e', blocks, new Set(['e'])), false);
  assert.equal(SyncRules.canRefresh('e', blocks, new Set(['other'])), true);
});

test('should_queueSaveAndDelete_when_deskEditMergesParagraphs', () => {
  const before = [synced('a', 'A', '一'), synced('b', 'B', '二'), synced('c', 'C', '三')];
  const after = [before[0].withText('一二', 'paragraph'), before[2], EssayBlock.paragraph('n1', '四')];
  const ops = SyncRules.opsFor(before, after);
  assert.deepEqual(ops.map((o) => `${o.op}:${o.targetId}`), ['delete_block:B', 'save_block:a', 'save_block:n1']);
});

test('should_moveOnlyTheDraggedBlock_when_tabletReorders', () => {
  const before = [synced('a', 'A', '一'), synced('b', 'B', '二'), synced('c', 'C', '三'), synced('d', 'D', '四')];
  const after = [before[0], before[3], before[1], before[2]];
  const ops = SyncRules.opsFor(before, after);
  assert.deepEqual(ops.map((o) => `${o.op}:${o.targetId}`), [`${OutboxOp.MoveBlock}:d`]);
});

test('should_rewriteSyncedBlock_when_itsTypeChanges', () => {
  const before = [synced('a', 'A', '标题'), EssayBlock.paragraph('n1', '还没发')];
  const after = [before[0].withText('标题', 'heading_2'), before[1].withText('还没发', 'quote')];
  assert.deepEqual(SyncRules.opsFor(before, after).map((o) => `${o.op}:${o.targetId}`), ['move_block:a', 'save_block:n1']);
});

test('should_notDeleteUnsentBlock_when_itIsRemoved', () => {
  const before = [synced('a', 'A', '一'), EssayBlock.paragraph('n1', '还没发')];
  assert.equal(SyncRules.opsFor(before, [before[0]]).length, 0);
});

test('should_anchorAfterNearestSyncedBlock_when_appending', () => {
  const body = [synced('a', 'A', '一'), EssayBlock.paragraph('n1', '新'), EssayBlock.paragraph('n2', '又新')];
  const anchor = SyncRules.anchorFor(body, 'n2');
  assert.equal(anchor.afterBlockId, 'A');
  const first = SyncRules.anchorFor([EssayBlock.paragraph('n0', '开头'), synced('a', 'A', '一')], 'n0');
  assert.equal(first.atStart, true);
});

test('should_readRow_when_notionPageHasLeafFields', () => {
  const page = {
    id: 'p1', last_edited_time: '2026-11-08T10:00:00.000Z', created_time: '2026-11-01T10:00:00.000Z',
    properties: {
      名字: { type: 'title', title: [{ plain_text: '雨天' }] },
      作者: { type: 'multi_select', multi_select: [{ name: '我' }] },
      tags: { type: 'multi_select', multi_select: [{ name: '东京' }, { name: ' 散步 ' }] },
      卡片颜色: { type: 'select', select: { name: '随便改的名字', color: 'blue' } },
      置顶: { type: 'checkbox', checkbox: true },
    },
  } as NotionPage;
  const row = NotionJson.row(page, '名字');
  assert.equal(row.title, '雨天');
  assert.deepEqual(row.tags, ['东京', '散步']);
  assert.equal(row.color, NotionColor.Blue);
  assert.equal(row.pinned, true);
  assert.equal(row.isWall, false);
  assert.equal(row.editedAt, Date.parse('2026-11-08T10:00:00.000Z'));
});

test('should_treatUntypedRowNamedWall_when_typeIsMissing', () => {
  const page = { id: 'w', properties: { Name: { type: 'title', title: [{ plain_text: '涂鸦墙' }] } } } as NotionPage;
  assert.equal(NotionJson.row(page, 'Name').isWall, true);
});

test('should_markFormattedTextFixed_when_bodyBlockHasBold', () => {
  const bold = { id: 'b', type: 'paragraph', paragraph: { rich_text: [{ type: 'text', plain_text: '粗', annotations: { bold: true } }] } } as NotionBlock;
  const plain = { id: 'c', type: 'quote', quote: { rich_text: [{ type: 'text', plain_text: '引', annotations: { color: 'default' } }], color: 'yellow_background' } } as NotionBlock;
  assert.equal(NotionJson.bodyBlock(bold)?.fixed, true);
  const q = NotionJson.bodyBlock(plain);
  assert.equal(q?.fixed, false);
  assert.equal(q?.color, NotionColor.Yellow);
  assert.equal(q?.text, '引');
});

test('should_splitRichText_when_longerThanNotionLimit', () => {
  const block = NotionJson.textBlock('paragraph', 'x'.repeat(4500), NotionColor.Default);
  const value = block['paragraph'] as Record<string, unknown>;
  assert.equal((value['rich_text'] as unknown[]).length, 3);
});

test('should_addOnlyMissingFields_when_schemaIsPartial', () => {
  const source = new DataSourceInfo('d', 'DB', [new PropertyInfo('t', 'Name', 'title'), new PropertyInfo('x', LeafSchema.Pinned, 'checkbox')]);
  const add = LeafSchema.missingProperties(source);
  assert.ok(add !== null);
  assert.deepEqual(Object.keys(add!), [LeafSchema.Type, LeafSchema.CardColor]);
  const full = new DataSourceInfo('d', 'DB', [new PropertyInfo('t', 'Name', 'title'), new PropertyInfo('a', LeafSchema.Type, 'select'),
    new PropertyInfo('b', LeafSchema.CardColor, 'select'), new PropertyInfo('c', LeafSchema.Pinned, 'checkbox')]);
  assert.equal(LeafSchema.missingProperties(full), null);
});

test('should_extractDashedId_when_givenNotionUrl', () => {
  assert.equal(NotionJson.extractId('https://www.notion.so/me/Leaf-0123456789abcdef0123456789ABCDEF?v=fedcba9876543210fedcba9876543210'),
    '01234567-89ab-cdef-0123-456789abcdef');
  assert.equal(NotionJson.extractId('nothing here'), '');
});

test('should_filterAndOrderEssays_when_tagPicked', () => {
  const a = new Essay('a', '甲'); a.tags = ['东京']; a.editedAt = 1;
  const b = new Essay('b', '乙'); b.tags = ['北京', '东京']; b.editedAt = 2;
  const c = new Essay('c', '丙'); c.pinned = true; c.color = NotionColor.Green;
  assert.deepEqual(TagRules.all([a, b, c]), ['北京', '东京']);
  assert.deepEqual(TagRules.ordered([a, b, c]).map((e) => e.id), ['c', 'b', 'a']);
  const tokyo = new HomeFilter(FilterKind.Tag, '东京');
  assert.deepEqual([a, b, c].filter((e) => TagRules.matches(e, tokyo)).map((e) => e.id), ['a', 'b']);
  assert.deepEqual(TagRules.colorsInUse([a, b, c]), [NotionColor.Green]);
});
