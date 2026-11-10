import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NotionColor } from '../src/core/Models';
import {
  LocalMeta, FolderAction, Side, Synced, conflictName, contentHash, decide, decode, encode, fileNameFor, isEssayFile,
  isoTime, timeOf, titleOf,
} from '../src/core/LocalFiles';

const none = new Set<string>();
const side = (hash: string): Side => ({ hash });
const synced = (hash: string): Synced => ({ hash });
const meta = (fill: (m: LocalMeta) => void): LocalMeta => {
  const m = new LocalMeta();
  fill(m);
  return m;
};
const roundTrips = (m: LocalMeta, body: string): void => {
  const text = encode(m, body);
  const doc = decode(text);
  assert.deepEqual(doc.meta, m);
  assert.deepEqual(Array.from(doc.meta.extra.keys()), Array.from(m.extra.keys()));
  assert.equal(doc.body, body);
  assert.equal(encode(doc.meta, doc.body), text);
};

// File names

test('should_replaceIllegalCharacters_when_titleHasThem', () => {
  assert.equal(fileNameFor('a/b\\c:d*e?f"g<h>i|j', none), 'a b c d e f g h i j.md');
  assert.equal(fileNameFor('春\t天\n来了\u0007', none), '春 天 来了.md');
  assert.equal(fileNameFor('一 / 二', none), '一 二.md');
});

test('should_trimAndDropLeadingDots_when_titleWouldBeHidden', () => {
  assert.equal(fileNameFor('  ..隐藏  的   文件 ', none), '隐藏 的 文件.md');
  assert.equal(fileNameFor('. . 春天', none), '春天.md');
  assert.equal(fileNameFor('v1.2 笔记.', none), 'v1.2 笔记..md');
});

test('should_cutByCodePoint_when_titleIsLong', () => {
  const head = '字'.repeat(79);
  assert.equal(fileNameFor(`${head}😀尾巴`, none), `${head}😀.md`);
  assert.equal(fileNameFor(`${head}字😀`, none), `${head}字.md`);
  assert.equal(fileNameFor(`${head} x`, none), `${head}.md`);
  assert.equal(fileNameFor('字'.repeat(80), none), `${'字'.repeat(80)}.md`);
});

test('should_neverWriteLoneSurrogate_when_titleIsMalformed', () => {
  assert.equal(fileNameFor('春\uD800天\uDE00', none), '春 天.md');
});

test('should_nameUntitled_when_nothingIsLeft', () => {
  assert.equal(fileNameFor('', none), '无标题.md');
  assert.equal(fileNameFor('   ', none), '无标题.md');
  assert.equal(fileNameFor('///', none), '无标题.md');
  assert.equal(fileNameFor('...', none), '无标题.md');
});

test('should_numberName_when_itIsTaken', () => {
  assert.equal(fileNameFor('春天', new Set(['春天.md'])), '春天 2.md');
  assert.equal(fileNameFor('春天', new Set(['春天.md', '春天 2.md'])), '春天 3.md');
  assert.equal(fileNameFor('春天', new Set(['春天.txt'])), '春天.md');
  assert.equal(fileNameFor('', new Set(['无标题.md'])), '无标题 2.md');
});

test('should_numberName_when_takenDiffersOnlyInCase', () => {
  assert.equal(fileNameFor('readme', new Set(['README.MD'])), 'readme 2.md');
  assert.equal(fileNameFor('Spring', new Set(['spring.md', 'SPRING 2.md'])), 'Spring 3.md');
  assert.equal(fileNameFor('Café', new Set(['cafe\u0301.md'])), 'Café 2.md');
});

test('should_readTitle_when_fileAppearsFromOutside', () => {
  assert.equal(titleOf('春天.md'), '春天');
  assert.equal(titleOf('春天.markdown'), '春天');
  assert.equal(titleOf('Notes.TXT'), 'Notes');
  assert.equal(titleOf('readme.md.md'), 'readme.md');
  assert.equal(titleOf('photo.png'), 'photo.png');
  assert.equal(titleOf('无标题.md'), '');
  assert.equal(titleOf('无标题 2.md'), '');
  assert.equal(titleOf('无标题的信.md'), '无标题的信');
  assert.equal(titleOf(fileNameFor('春天', none)), '春天');
});

test('should_pickEssayFiles_when_folderHasOthers', () => {
  assert.equal(isEssayFile('春天.md'), true);
  assert.equal(isEssayFile('草稿.Markdown'), true);
  assert.equal(isEssayFile('旧文.txt'), true);
  assert.equal(isEssayFile('.DS_Store'), false);
  assert.equal(isEssayFile('._春天.md'), false);
  assert.equal(isEssayFile('封面.png'), false);
  assert.equal(isEssayFile('.md'), false);
});

test('should_nameConflictCopy_when_bothSidesChanged', () => {
  const when = new Date(2026, 10, 10, 21, 5);
  assert.equal(conflictName('春天.md', when), '春天 (冲突 11-10 21-05).md');
  assert.equal(conflictName('notes.TXT', new Date(2026, 0, 3, 7, 9)), 'notes (冲突 01-03 07-09).TXT');
  assert.equal(conflictName('无扩展名', when), '无扩展名 (冲突 11-10 21-05)');
});

// Front matter

test('should_writeBareMarkdown_when_nothingToSay', () => {
  assert.equal(encode(new LocalMeta(), '正文'), '正文');
  assert.equal(encode(meta((m) => { m.created = '2026-11-10T12:00:00+08:00'; }), '正文'), '正文');
  const doc = decode('# 春天\n\n正文\n');
  assert.deepEqual(doc.meta, new LocalMeta());
  assert.equal(doc.body, '# 春天\n\n正文\n');
  roundTrips(new LocalMeta(), '');
  roundTrips(new LocalMeta(), '正文\n\n第二段');
});

test('should_writeFrontMatter_when_essayHasMeta', () => {
  const m = meta((x) => {
    x.notion = '2a1b3c4d-0000-4000-8000-000000000001';
    x.tags = ['读书', '随笔'];
    x.color = NotionColor.Blue;
    x.pinned = true;
    x.created = isoTime(Date.UTC(2026, 10, 10, 4, 0, 0), 480);
  });
  const text = encode(m, '正文');
  assert.equal(text, [
    '---',
    'notion: 2a1b3c4d-0000-4000-8000-000000000001',
    'tags: [读书, 随笔]',
    'color: blue',
    'pinned: true',
    'created: 2026-11-10T12:00:00+08:00',
    '---',
    '',
    '正文',
  ].join('\n'));
  roundTrips(m, '正文');
  roundTrips(m, '');
  roundTrips(m, '\n\n前面有空行\n');
  roundTrips(m, '---\n正文以分隔线开头\n---\n');
  roundTrips(meta((x) => { x.pinned = true; }), '正文');
  roundTrips(meta((x) => { x.color = NotionColor.Red; }), '正文');
});

test('should_quoteTags_when_theyHoldSpecialCharacters', () => {
  const tags = ['C#', '读书, 随笔', ' 空格 ', '[x]', 'a: b', '', 'say "hi"', 'it\'s', '\'引号', 'back\\slash', '换\n行',
    '-减号', '{x}', '😀', '\u0007', '\u2028', '#话题'];
  const m = meta((x) => {
    x.tags = tags;
    x.created = '2026-11-10T12:00:00+08:00';
  });
  const text = encode(m, '正文');
  assert.ok(text.includes('tags: ["C#", "读书, 随笔", " 空格 ", "[x]", "a: b", "", say "hi", it\'s, "\'引号", back\\slash, ' +
    '"换\\n行", "-减号", "{x}", 😀, "\\x07", "\\u2028", "#话题"]\n'), text);
  roundTrips(m, '正文');
});

test('should_quoteScalars_when_plainWouldNotReadBack', () => {
  roundTrips(meta((x) => { x.notion = 'a: b # c'; x.created = ' 有空格'; }), '正文');
  roundTrips(meta((x) => { x.notion = '"引号"'; x.created = '#'; }), '正文');
  roundTrips(meta((x) => { x.notion = 'line\nbreak'; x.created = 'x:'; }), '正文');
  assert.ok(encode(meta((x) => { x.notion = 'a: b'; }), '').startsWith('---\nnotion: "a: b"\n'));
});

test('should_readTags_when_writtenAsBlockListOrScalar', () => {
  const block = decode('---\ntags:\n  - 读书\n  - "随笔, 杂文"\n- 顶格\n  -\n---\n\n正文');
  assert.deepEqual(block.meta.tags, ['读书', '随笔, 杂文', '顶格']);
  assert.equal(block.body, '正文');
  assert.deepEqual(decode('---\ntags: 读书\n---\n正文').meta.tags, ['读书']);
  assert.deepEqual(decode('---\ntags: \'it\'\'s\'\n---\n').meta.tags, ['it\'s']);
  assert.deepEqual(decode('---\ntags: [a, , b, ] # 注释\n---\n').meta.tags, ['a', 'b']);
  assert.deepEqual(decode('---\ntags: [a,\n  b]\n---\n').meta.tags, ['a', 'b']);
  assert.deepEqual(decode('---\ntags:\n---\n').meta.tags, []);
  const back = encode(block.meta, block.body);
  assert.ok(back.startsWith('---\ntags: [读书, "随笔, 杂文", 顶格]\n---\n\n'), back);
});

test('should_keepUnknownKeysInOrder_when_userWroteThem', () => {
  const text = [
    '---',
    'title: 春天',
    'notion: abc',
    'date: 2026-03-01',
    'aliases:',
    '  - 春',
    '  - spring',
    'tags: [读书]',
    'summary: |',
    '  第一行',
    '',
    '  第二行',
    'empty:',
    '---',
    '',
    '正文',
  ].join('\n');
  const doc = decode(text);
  assert.equal(doc.meta.notion, 'abc');
  assert.deepEqual(doc.meta.tags, ['读书']);
  assert.deepEqual(Array.from(doc.meta.extra.keys()), ['title', 'date', 'aliases', 'summary', 'empty']);
  assert.equal(doc.meta.extra.get('title'), ' 春天');
  assert.equal(doc.meta.extra.get('aliases'), '\n  - 春\n  - spring');
  const written = encode(doc.meta, doc.body);
  assert.equal(written, [
    '---',
    'notion: abc',
    'tags: [读书]',
    'title: 春天',
    'date: 2026-03-01',
    'aliases:',
    '  - 春',
    '  - spring',
    'summary: |',
    '  第一行',
    '',
    '  第二行',
    'empty:',
    '---',
    '',
    '正文',
  ].join('\n'));
  roundTrips(doc.meta, doc.body);
});

test('should_writeFrontMatter_when_onlyUserKeysAreLeft', () => {
  const m = meta((x) => { x.extra.set('title', ' 春天'); });
  assert.equal(encode(m, '正文'), '---\ntitle: 春天\n---\n\n正文');
  roundTrips(m, '正文');
  const known = meta((x) => { x.extra.set('tags', ' [不该写]'); x.extra.set('bad key: x', ' y'); });
  assert.equal(encode(known, '正文'), '正文');
});

test('should_acceptCrlf_when_fileComesFromWindows', () => {
  const doc = decode('---\r\nnotion: abc\r\ntags: [a]\r\npinned: true\r\n---\r\n\r\n第一行\r\n第二行\r\n');
  assert.equal(doc.meta.notion, 'abc');
  assert.deepEqual(doc.meta.tags, ['a']);
  assert.equal(doc.meta.pinned, true);
  assert.equal(doc.body, '第一行\n第二行\n');
  assert.equal(decode('甲\r\n乙\r丙').body, '甲\n乙\n丙');
});

test('should_dropBom_when_fileStartsWithOne', () => {
  const doc = decode('\ufeff---\nnotion: abc\n---\n\n正文');
  assert.equal(doc.meta.notion, 'abc');
  assert.equal(doc.body, '正文');
  assert.equal(decode('\ufeff正文').body, '正文');
});

test('should_useDefaultColor_when_colorIsUnknown', () => {
  assert.equal(decode('---\ncolor: chartreuse\n---\n').meta.color, NotionColor.Default);
  assert.equal(decode('---\ncolor: Blue\n---\n').meta.color, NotionColor.Blue);
  assert.equal(decode('---\ncolor: blue_background\n---\n').meta.color, NotionColor.Blue);
  assert.equal(decode('---\ncolor: 绿\n---\n').meta.color, NotionColor.Green);
  assert.equal(decode('---\ncolor: "red" # 注释\n---\n').meta.color, NotionColor.Red);
});

test('should_readLoosely_when_userEditedFrontMatter', () => {
  const doc = decode('---\n# 我的笔记\nnotion: abc # 页面\npinned: yes\ncreated: 2026-11-10\n...\n正文');
  assert.equal(doc.meta.notion, 'abc');
  assert.equal(doc.meta.pinned, true);
  assert.equal(doc.meta.created, '2026-11-10');
  assert.equal(doc.body, '正文');
  assert.equal(decode('---\npinned: false\n---\n').meta.pinned, false);
  assert.equal(decode('---\nnotion: abc\n---').body, '');
});

test('should_keepWholeText_when_markdownStartsWithRule', () => {
  for (const body of ['---\n第一节\n---\n正文', '---\n', '---\n标题: 春天\n没有缩进的正文\n---\n', '---\nnotion: x']) {
    const doc = decode(body);
    assert.deepEqual(doc.meta, new LocalMeta());
    assert.equal(doc.body, body);
    assert.equal(encode(doc.meta, doc.body), body);
  }
});

test('should_guardBody_when_itWouldReadAsFrontMatter', () => {
  const body = '---\nnotion: 不是元数据\n---\n\n正文';
  const text = encode(new LocalMeta(), body);
  assert.equal(text, `---\n---\n\n${body}`);
  roundTrips(new LocalMeta(), body);
  roundTrips(meta((x) => { x.created = '2026-11-10T12:00:00+08:00'; }), body);
});

test('should_formatAndReadCreated_when_offsetIsGiven', () => {
  const t = Date.UTC(2026, 10, 10, 4, 0, 0);
  assert.equal(isoTime(t, 480), '2026-11-10T12:00:00+08:00');
  assert.equal(isoTime(t + 250, 480), '2026-11-10T12:00:00.250+08:00');
  assert.equal(isoTime(t, -330), '2026-11-09T22:30:00-05:30');
  assert.equal(isoTime(t, 0), '2026-11-10T04:00:00+00:00');
  assert.equal(timeOf(isoTime(t, 480)), t);
  assert.equal(timeOf(isoTime(t + 250, -330)), t + 250);
  assert.equal(timeOf(isoTime(t)), t);
  assert.equal(timeOf('2026-11-10T04:00:00Z'), t);
  assert.equal(timeOf('2026-11-10 12:00+0800'), t);
  assert.equal(timeOf('2026-11-10T12:00:00.25+08'), t + 250);
  assert.equal(timeOf('2026-11-10'), new Date(2026, 10, 10).getTime());
  assert.equal(timeOf('2026-11-10 21:05'), new Date(2026, 10, 10, 21, 5).getTime());
  assert.ok(Number.isNaN(timeOf('去年春天')));
  assert.ok(Number.isNaN(timeOf('2026-13-01')));
});

// Hash

test('should_hashStably_when_contentIsTheSame', () => {
  assert.equal(contentHash('', ''), '0f0c6cdd');
  assert.equal(contentHash('foo', 'bar'), '66bde038');
  assert.equal(contentHash('春天', '正文'), 'fdef2b15');
  assert.equal(contentHash('😀', 'a'), '258ab4f5');
  assert.equal(contentHash('春天', '正文'), contentHash('春天', '正文'));
  assert.match(contentHash('任何', '内容'), /^[0-9a-f]{8}$/);
});

test('should_hashDifferently_when_titleOrTextChanges', () => {
  const base = contentHash('春天', '正文');
  assert.equal(contentHash('春天', '正文。'), 'd667a535');
  assert.equal(contentHash('秋天', '正文'), '1720b53b');
  assert.notEqual(contentHash('春天', '正文。'), base);
  assert.notEqual(contentHash('秋天', '正文'), base);
  assert.notEqual(contentHash('春天', '正文\n'), base);
});

// Sync decision

test('should_doNothingOrRecord_when_bothSidesAgree', () => {
  assert.equal(decide(side('a'), side('a'), synced('a')), FolderAction.Nothing);
  assert.equal(decide(side('a'), side('a'), null), FolderAction.Record);
  assert.equal(decide(side('b'), side('b'), synced('a')), FolderAction.Record);
});

test('should_copyTheChangedSide_when_onlyOneChanged', () => {
  assert.equal(decide(side('b'), side('a'), synced('a')), FolderAction.ReadFile);
  assert.equal(decide(side('a'), side('b'), synced('a')), FolderAction.WriteFile);
});

test('should_reportConflict_when_bothChangedOrNeverSynced', () => {
  assert.equal(decide(side('b'), side('c'), synced('a')), FolderAction.Conflict);
  assert.equal(decide(side('b'), side('c'), null), FolderAction.Conflict);
});

test('should_deleteOrKeepFile_when_essayIsGone', () => {
  assert.equal(decide(side('a'), null, synced('a')), FolderAction.DeleteFile);
  assert.equal(decide(side('b'), null, synced('a')), FolderAction.CreateEssay);
  assert.equal(decide(side('a'), null, null), FolderAction.CreateEssay);
});

test('should_deleteOrKeepEssay_when_fileIsGone', () => {
  assert.equal(decide(null, side('a'), synced('a')), FolderAction.DeleteEssay);
  assert.equal(decide(null, side('b'), synced('a')), FolderAction.CreateFile);
  assert.equal(decide(null, side('a'), null), FolderAction.CreateFile);
});

test('should_doNothing_when_bothSidesAreGone', () => {
  assert.equal(decide(null, null, synced('a')), FolderAction.Nothing);
  assert.equal(decide(null, null, null), FolderAction.Nothing);
});
