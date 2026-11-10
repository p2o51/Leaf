import { NotionColor } from './Models';

/**
 * Leaf's folder: one Markdown file per essay in a folder the user picks, kept in step with the library both
 * ways, so Leaf works without Notion and the files stay usable in iA Writer and other editors. This is the
 * pure part (the file format, file names and the sync decision for one essay/file pair), tested on Node with
 * the rest of core/; reading and writing the folder is data/'s job.
 *
 * A file is the essay's Markdown, with a front matter block (a YAML subset, see [encode]) on top only when
 * there is something to say. The title is the file name ([fileNameFor], [titleOf]), never a front matter key.
 *
 * Sync compares [contentHash]es: what each side holds now against what both held at the last sync
 * ([decide]). Hash both sides in the same form (the same title, and the file's text as [encode] would write
 * it), so a file that only differs by CRLF or a BOM does not look edited.
 */

/** The name an essay without a title is saved under; such a file reads back as an untitled essay. */
export const UNTITLED: string = '无标题';

/** The longest name stem Leaf makes, in code points, before any " 2" and the `.md`. */
export const NAME_LIMIT: number = 80;

/** Extensions Leaf reads as essays, longest first. Leaf itself writes `.md`. */
const EXTENSIONS: string[] = ['.markdown', '.md', '.txt'];
const ILLEGAL: RegExp = new RegExp('[/\\\\:*?"<>|\\u0000-\\u001f\\u007f-\\u009f]', 'g');
const SPACES: RegExp = new RegExp('\\s+', 'g');
const LEADING: RegExp = new RegExp('^[\\s.]+');
const UNTITLED_STEM: RegExp = new RegExp(`^${UNTITLED}(?: \\d+)?$`);

function isHigh(code: number): boolean {
  return code >= 0xD800 && code <= 0xDBFF;
}

function isLow(code: number): boolean {
  return code >= 0xDC00 && code <= 0xDFFF;
}

/** Whether s[i] is half of a surrogate pair that has lost its other half. */
function loneSurrogate(s: string, i: number): boolean {
  const c = s.charCodeAt(i);
  if (isHigh(c)) {
    return i + 1 >= s.length || !isLow(s.charCodeAt(i + 1));
  }
  if (isLow(c)) {
    return i === 0 || !isHigh(s.charCodeAt(i - 1));
  }
  return false;
}

/** [s] with unpaired surrogates, which no file system can store, turned into spaces. */
function wellFormed(s: string): string {
  const out: string[] = [];
  for (let i = 0; i < s.length; i++) {
    out.push(loneSurrogate(s, i) ? ' ' : s.charAt(i));
  }
  return out.join('');
}

/** The first [limit] code points of a well-formed [s]; a surrogate pair is never split. */
function cutCodePoints(s: string, limit: number): string {
  let i = 0;
  let n = 0;
  while (i < s.length && n < limit) {
    i += isHigh(s.charCodeAt(i)) && i + 1 < s.length ? 2 : 1;
    n++;
  }
  return s.substring(0, i);
}

/** How names are compared for clashes: case-insensitively, and NFC so é typed two ways is one name. */
function nameKey(name: string): string {
  return name.normalize('NFC').toLowerCase();
}

/** The essay extension [fileName] ends with, as spelled there, or '' (also for a bare ".md"). */
function extensionOf(fileName: string): string {
  for (const ext of EXTENSIONS) {
    if (fileName.length > ext.length) {
      const tail = fileName.substring(fileName.length - ext.length);
      if (tail.toLowerCase() === ext) {
        return tail;
      }
    }
  }
  return '';
}

/**
 * The file name (with `.md`) for an essay titled [title]: / \ : * ? " < > | and control characters become
 * spaces, whitespace runs become one space, leading dots and spaces go (no hidden files), and the rest is cut
 * to [NAME_LIMIT] code points; nothing left gives [UNTITLED]. A name already in [taken] (compared
 * case-insensitively) gets " 2", " 3"… before the `.md`. Pass every name in the folder except the one this
 * essay's file already has.
 */
export function fileNameFor(title: string, taken: Set<string>): string {
  const clean = wellFormed(title.replace(ILLEGAL, ' ')).replace(SPACES, ' ').replace(LEADING, '');
  const cut = cutCodePoints(clean, NAME_LIMIT).trim();
  const stem = cut.length > 0 ? cut : UNTITLED;
  const used = new Set<string>();
  taken.forEach((name: string) => {
    used.add(nameKey(name));
  });
  let name = `${stem}.md`;
  let n = 2;
  while (used.has(nameKey(name))) {
    name = `${stem} ${n}.md`;
    n++;
  }
  return name;
}

/**
 * The title of an essay read from a file that appeared from outside: the name without its `.md`, `.markdown`
 * or `.txt`. [UNTITLED] (also "无标题 2"…, as [fileNameFor] numbers them) is an untitled essay, "".
 */
export function titleOf(fileName: string): string {
  const stem = fileName.substring(0, fileName.length - extensionOf(fileName).length).trim();
  return UNTITLED_STEM.test(stem) ? '' : stem;
}

/** Whether a file in the folder is an essay: a `.md`, `.markdown` or `.txt` that is not hidden (`.`, `._`). */
export function isEssayFile(fileName: string): boolean {
  return !fileName.startsWith('.') && extensionOf(fileName).length > 0;
}

function two(n: number): string {
  return n.toString().padStart(2, '0');
}

/**
 * Where the outside copy goes when both sides changed: next to the essay's own file, named with the local
 * time, e.g. "春天 (冲突 11-10 21-05).md". The extension stays the file's own.
 */
export function conflictName(fileName: string, when: Date): string {
  const ext = extensionOf(fileName);
  const stem = fileName.substring(0, fileName.length - ext.length);
  return `${stem} (冲突 ${two(when.getMonth() + 1)}-${two(when.getDate())} ${two(when.getHours())}-` +
    `${two(when.getMinutes())})${ext}`;
}

/** What a file says about its essay besides the words. A new LocalMeta says nothing. */
export class LocalMeta {
  /** The essay's Notion page id; '' when it has none. */
  notion: string = '';
  tags: string[] = [];
  color: NotionColor = NotionColor.Default;
  pinned: boolean = false;
  /** When the essay was created, as written in the file (see [isoTime], [timeOf]); '' when unknown. */
  created: string = '';
  /**
   * Keys Leaf does not know (a user's own title:, date:…), in file order. Each value is the YAML exactly as
   * it stands after `key:`, leading space and any following lines included (" 春天", "\n  - a\n  - b"), so
   * it is written back unchanged.
   */
  extra: Map<string, string> = new Map<string, string>();
}

/** A file read by [decode]. */
export class LocalDoc {
  readonly meta: LocalMeta;
  /** The Markdown after the front matter, with LF line ends. */
  readonly body: string;

  constructor(meta: LocalMeta, body: string) {
    this.meta = meta;
    this.body = body;
  }
}

const KNOWN: Set<string> = new Set<string>(['notion', 'tags', 'color', 'pinned', 'created']);
const OPEN: RegExp = new RegExp('^---[ \\t]*$');
const CLOSE: RegExp = new RegExp('^(?:---|\\.\\.\\.)[ \\t]*$');
/** `key:` then a space or the line's end. A key starts with neither a space, `#`, `:` nor `-`. */
const KEY_LINE: RegExp = new RegExp('^([^\\s#:\\-][^:]*):(?=[ \\t]|$)');
const BLANK_OR_COMMENT: RegExp = new RegExp('^[ \\t]*(?:#.*)?$');
/** A line that belongs to the key above it: indented, or a `- item` (YAML allows those unindented). */
const CONTINUATION: RegExp = new RegExp('^(?:[ \\t]|-(?:[ \\t]|$))');
const ITEM: RegExp = new RegExp('^[ \\t]*-(?:[ \\t]+(.*))?$');
const COMMENT: RegExp = new RegExp('[ \\t]#');
const NEWLINES: RegExp = new RegExp('\\r\\n?', 'g');
const HEX: RegExp = new RegExp('^[0-9a-fA-F]+$');
const FLOW_SPECIAL: RegExp = new RegExp('[,\\[\\]{}:#]');
/** A plain YAML scalar may not start with one of these. */
const INDICATORS: string = '-?:,[]{}#&*!|>\'"%@`';
const PINNED_TRUE: Set<string> = new Set<string>(['true', 'yes', 'on']);

/** The front matter's lines, and where the body starts in the text. */
class Block {
  readonly lines: string[];
  readonly bodyStart: number;

  constructor(lines: string[], bodyStart: number) {
    this.lines = lines;
    this.bodyStart = bodyStart;
  }
}

/** A quoted scalar's value and the index just past its closing quote. */
class Quoted {
  readonly value: string;
  readonly end: number;

  constructor(value: string, end: number) {
    this.value = value;
    this.end = end;
  }
}

/** Escapes of a double-quoted scalar other than \x, \u and \U. */
const ESCAPES: Map<string, string> = new Map<string, string>([
  ['0', '\u0000'], ['a', '\u0007'], ['b', '\b'], ['t', '\t'], ['n', '\n'], ['v', '\u000b'], ['f', '\f'],
  ['r', '\r'], ['e', '\u001b'], [' ', ' '], ['"', '"'], ['/', '/'], ['\\', '\\'], ['N', '\u0085'],
  ['_', '\u00a0'], ['L', '\u2028'], ['P', '\u2029'],
]);

function normalized(text: string): string {
  const t = text.startsWith('\ufeff') ? text.substring(1) : text;
  return t.replace(NEWLINES, '\n');
}

/**
 * Whether the lines between two `---` read as YAML Leaf understands: blank lines and comments, keys, and
 * under a key, indented or `- ` lines. A Markdown text that merely starts with a `---` rule does not.
 */
function looksLikeYaml(lines: string[]): boolean {
  let keyed = false;
  for (const line of lines) {
    if (BLANK_OR_COMMENT.test(line)) {
      continue;
    }
    if (KEY_LINE.test(line)) {
      keyed = true;
      continue;
    }
    if (!keyed || !CONTINUATION.test(line)) {
      return false;
    }
  }
  return true;
}

/** The front matter at the top of an LF text, or null. One blank line after the closing `---` is skipped. */
function frontMatter(t: string): Block | null {
  const firstEnd = t.indexOf('\n');
  if (firstEnd < 0 || !OPEN.test(t.substring(0, firstEnd))) {
    return null;
  }
  const lines: string[] = [];
  let pos = firstEnd + 1;
  while (pos <= t.length) {
    const nl = t.indexOf('\n', pos);
    const line = t.substring(pos, nl < 0 ? t.length : nl);
    if (CLOSE.test(line)) {
      let bodyStart = nl < 0 ? t.length : nl + 1;
      if (t.charAt(bodyStart) === '\n') {
        bodyStart++;
      }
      return looksLikeYaml(lines) ? new Block(lines, bodyStart) : null;
    }
    lines.push(line);
    if (nl < 0) {
      return null;
    }
    pos = nl + 1;
  }
  return null;
}

/** The quoted scalar starting at s[at] (a `"` or `'`), or null when it never closes. */
function quoted(s: string, at: number): Quoted | null {
  const quote = s.charAt(at);
  const out: string[] = [];
  let i = at + 1;
  while (i < s.length) {
    const c = s.charAt(i);
    if (quote === '\'') {
      if (c !== '\'') {
        out.push(c);
        i++;
      } else if (s.charAt(i + 1) === '\'') {
        out.push('\'');
        i += 2;
      } else {
        return new Quoted(out.join(''), i + 1);
      }
      continue;
    }
    if (c === '"') {
      return new Quoted(out.join(''), i + 1);
    }
    if (c !== '\\' || i + 1 >= s.length) {
      out.push(c);
      i++;
      continue;
    }
    const e = s.charAt(i + 1);
    const len = e === 'x' ? 2 : e === 'u' ? 4 : e === 'U' ? 8 : 0;
    const hex = s.substring(i + 2, i + 2 + len);
    const code = len > 0 && hex.length === len && HEX.test(hex) ? parseInt(hex, 16) : -1;
    const simple = ESCAPES.get(e);
    if (code >= 0 && code <= 0x10FFFF) {
      out.push(String.fromCodePoint(code));
      i += 2 + len;
    } else if (len === 0 && simple !== undefined) {
      out.push(simple);
      i += 2;
    } else {
      out.push(c + e);
      i += 2;
    }
  }
  return null;
}

/** A scalar value on one line: quoted, or plain with a trailing ` # comment` dropped. */
function scalarOf(s: string): string {
  const t = s.trim();
  if (t.startsWith('"') || t.startsWith('\'')) {
    const q = quoted(t, 0);
    if (q !== null) {
      return q.value;
    }
  }
  if (t.startsWith('#')) {
    return '';
  }
  const hash = t.search(COMMENT);
  return hash < 0 ? t : t.substring(0, hash).trim();
}

/** The items of a flow list `[a, "b, c"]`; an unquoted empty item (a stray comma) is skipped. */
function flowItems(s: string): string[] {
  const items: string[] = [];
  let i = 1;
  while (i < s.length) {
    const c = s.charAt(i);
    if (c === ']') {
      break;
    }
    if (c === ',' || c === ' ' || c === '\t') {
      i++;
      continue;
    }
    if (c === '"' || c === '\'') {
      const q = quoted(s, i);
      if (q !== null) {
        items.push(q.value);
        i = q.end;
        while (i < s.length && s.charAt(i) !== ',' && s.charAt(i) !== ']') {
          i++;
        }
        continue;
      }
    }
    let j = i;
    while (j < s.length && s.charAt(j) !== ',' && s.charAt(j) !== ']') {
      j++;
    }
    items.push(s.substring(i, j).trim());
    i = j;
  }
  return items;
}

/** tags: as a flow list, a single scalar, or (nothing after the colon) a block list of `- item` lines. */
function tagsOf(raw: string[]): string[] {
  if (raw[0].trim().startsWith('[')) {
    return flowItems(raw.join(' ').trim());
  }
  const one = scalarOf(raw[0]);
  if (one.length > 0) {
    return [one];
  }
  const items: string[] = [];
  for (let k = 1; k < raw.length; k++) {
    const m = ITEM.exec(raw[k]);
    const item = m !== null && m[1] !== undefined ? scalarOf(m[1]) : '';
    if (item.length > 0) {
      items.push(item);
    }
  }
  return items;
}

/** A colour by Notion's name (blue, blue_background) or Leaf's label (蓝); anything else is the default. */
function colorOf(name: string): NotionColor {
  const hit = NotionColor.entries.find((c: NotionColor) => c.label === name);
  return hit ?? NotionColor.fromApi(name.toLowerCase());
}

/** One key and its YAML (the text after `key:` and the lines under it) into [meta]. */
function take(meta: LocalMeta, key: string, raw: string[]): void {
  switch (key) {
    case 'notion':
      meta.notion = scalarOf(raw[0]);
      break;
    case 'tags':
      meta.tags = tagsOf(raw);
      break;
    case 'color':
      meta.color = colorOf(scalarOf(raw[0]));
      break;
    case 'pinned':
      meta.pinned = PINNED_TRUE.has(scalarOf(raw[0]).toLowerCase());
      break;
    case 'created':
      meta.created = scalarOf(raw[0]);
      break;
    default:
      meta.extra.set(key, raw.join('\n'));
  }
}

function readMeta(lines: string[]): LocalMeta {
  const meta = new LocalMeta();
  let key = '';
  let raw: string[] = [];
  for (const line of lines) {
    const m = KEY_LINE.exec(line);
    if (m !== null) {
      if (raw.length > 0) {
        take(meta, key, raw);
      }
      key = m[1].trim();
      raw = [line.substring(m[0].length)];
    } else if (raw.length > 0) {
      raw.push(line);
    }
  }
  if (raw.length > 0) {
    take(meta, key, raw);
  }
  return meta;
}

/** Whether s[i] must be escaped in a double-quoted scalar: controls, line separators, a BOM, lone halves. */
function mustEscape(s: string, i: number): boolean {
  const c = s.charCodeAt(i);
  return c < 0x20 || (c >= 0x7F && c <= 0x9F) || c === 0x2028 || c === 0x2029 || c === 0xFEFF || loneSurrogate(s, i);
}

function needsQuotes(s: string, flow: boolean): boolean {
  if (s.length === 0 || s !== s.trim() || INDICATORS.indexOf(s.charAt(0)) >= 0) {
    return true;
  }
  if (flow ? FLOW_SPECIAL.test(s) : s.indexOf(': ') >= 0 || s.indexOf(' #') >= 0 || s.endsWith(':')) {
    return true;
  }
  for (let i = 0; i < s.length; i++) {
    if (mustEscape(s, i)) {
      return true;
    }
  }
  return false;
}

function doubleQuoted(s: string): string {
  const out: string[] = ['"'];
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i);
    const code = s.charCodeAt(i);
    if (c === '"' || c === '\\') {
      out.push(`\\${c}`);
    } else if (c === '\n') {
      out.push('\\n');
    } else if (c === '\t') {
      out.push('\\t');
    } else if (c === '\r') {
      out.push('\\r');
    } else if (mustEscape(s, i)) {
      out.push(code <= 0xFF ? `\\x${code.toString(16).padStart(2, '0')}` : `\\u${code.toString(16).padStart(4, '0')}`);
    } else {
      out.push(c);
    }
  }
  out.push('"');
  return out.join('');
}

/** A value after `key: `, plain when YAML reads it back unchanged, else double-quoted. */
function scalar(s: string): string {
  return needsQuotes(s, false) ? doubleQuoted(s) : s;
}

/** An item of a flow list, double-quoted when it holds , [ ] { } : # or would not read back plain. */
function flowItem(s: string): string {
  return needsQuotes(s, true) ? doubleQuoted(s) : s;
}

/** Whether an extra key can be written as `key:` and read back as the same key. */
function writableKey(key: string): boolean {
  if (KNOWN.has(key) || key !== key.trim() || key.indexOf('\n') >= 0) {
    return false;
  }
  const m = KEY_LINE.exec(`${key}:`);
  return m !== null && m[0].length === key.length + 1;
}

/**
 * The file's text. Front matter is written only when there is something to say (a Notion page, tags, a
 * colour, pinned, or keys of the user's own), as
 *
 *     ---
 *     notion: <page id>
 *     tags: [读书, "随笔, 杂文"]
 *     color: blue
 *     pinned: true
 *     created: 2026-11-10T12:00:00+08:00
 *     ---
 *
 * then a blank line and [body]. Each known key is left out when empty, default or false; created alone is
 * not something to say, but rides along whenever front matter is written. Extra keys follow the known ones,
 * unchanged, in their order. A body that would itself read as front matter gets a block above it anyway
 * (empty but for created), so [decode] always gives [body] back.
 */
export function encode(meta: LocalMeta, body: string): string {
  const lines: string[] = [];
  if (meta.notion.length > 0) {
    lines.push(`notion: ${scalar(meta.notion)}`);
  }
  if (meta.tags.length > 0) {
    lines.push(`tags: [${meta.tags.map((t: string) => flowItem(t)).join(', ')}]`);
  }
  if (meta.color !== NotionColor.Default) {
    lines.push(`color: ${meta.color.apiName}`);
  }
  if (meta.pinned) {
    lines.push('pinned: true');
  }
  const extra: string[] = [];
  meta.extra.forEach((value: string, key: string) => {
    if (writableKey(key)) {
      extra.push(`${key}:${value}`);
    }
  });
  if (lines.length === 0 && extra.length === 0 && frontMatter(normalized(body)) === null) {
    return body;
  }
  if (meta.created.length > 0) {
    lines.push(`created: ${scalar(meta.created)}`);
  }
  return `---\n${lines.concat(extra).map((l: string) => `${l}\n`).join('')}---\n\n${body}`;
}

/**
 * A file's meta and body, read forgivingly: a leading BOM is dropped and CRLF (or CR) line ends become LF;
 * no front matter gives an empty meta and the whole text; tags may also be a block list or a single scalar;
 * an unknown colour is the default; unknown keys land in [LocalMeta.extra]. decode(encode(m, b)) gives m and
 * b back (unless m says nothing but created, which is then not written), and encode(decode(t)) gives back
 * any t that encode wrote.
 */
export function decode(text: string): LocalDoc {
  const t = normalized(text);
  const block = frontMatter(t);
  if (block === null) {
    return new LocalDoc(new LocalMeta(), t);
  }
  return new LocalDoc(readMeta(block.lines), t.substring(block.bodyStart));
}

const ISO: RegExp = new RegExp('^(\\d{4})-(\\d{2})-(\\d{2})(?:[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?' +
  '[ \\t]*([Zz]|[+-]\\d{2}(?::?\\d{2})?)?)?$');

function pad(n: number, width: number): string {
  return n.toString().padStart(width, '0');
}

/**
 * [millis] as ISO 8601 with an offset, for created: e.g. 2026-11-10T12:00:00+08:00 (milliseconds only when
 * there are some). [offsetMinutes] is east of UTC (480 for China); left out, it is this device's at that time.
 */
export function isoTime(millis: number, offsetMinutes?: number): string {
  const off = offsetMinutes !== undefined ? Math.round(offsetMinutes) : -new Date(millis).getTimezoneOffset();
  const d = new Date(millis + off * 60000);
  const ms = d.getUTCMilliseconds();
  const a = Math.abs(off);
  return `${pad(d.getUTCFullYear(), 4)}-${pad(d.getUTCMonth() + 1, 2)}-${pad(d.getUTCDate(), 2)}T` +
    `${pad(d.getUTCHours(), 2)}:${pad(d.getUTCMinutes(), 2)}:${pad(d.getUTCSeconds(), 2)}` +
    `${ms > 0 ? `.${pad(ms, 3)}` : ''}${off < 0 ? '-' : '+'}${pad(Math.floor(a / 60), 2)}:${pad(a % 60, 2)}`;
}

/**
 * Epoch millis of a created value, NaN when it is not a time. Reads what [isoTime] writes, Z, a space for
 * the T, and a bare date; a time without an offset is this device's local time.
 */
export function timeOf(text: string): number {
  const m = ISO.exec(text.trim());
  if (m === null) {
    return NaN;
  }
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  const h = m[4] !== undefined ? Number(m[4]) : 0;
  const mi = m[5] !== undefined ? Number(m[5]) : 0;
  const s = m[6] !== undefined ? Number(m[6]) : 0;
  const ms = m[7] !== undefined ? Number(`${m[7]}00`.substring(0, 3)) : 0;
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 60) {
    return NaN;
  }
  const zone = m[8];
  if (zone === undefined) {
    return new Date(y, mo - 1, d, h, mi, s, ms).getTime();
  }
  let off = 0;
  if (zone !== 'Z' && zone !== 'z') {
    const digits = zone.substring(1).replace(':', '');
    off = Number(digits.substring(0, 2)) * 60 + (digits.length > 2 ? Number(digits.substring(2, 4)) : 0);
    off = zone.charAt(0) === '-' ? -off : off;
  }
  return Date.UTC(y, mo - 1, d, h, mi, s, ms) - off * 60000;
}

function fnv(start: number, s: string): number {
  let h = start;
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(h ^ s.charCodeAt(i), 0x01000193);
  }
  return h;
}

/**
 * A short fingerprint of an essay or a file: FNV-1a (32-bit) over the UTF-16 code units of
 * title + "\n" + text, as 8 lowercase hex digits. Not cryptographic; it only tells versions apart.
 */
export function contentHash(title: string, text: string): string {
  const h = fnv(fnv(fnv(0x811c9dc5, title), '\n'), text);
  return (h >>> 0).toString(16).padStart(8, '0');
}

/** One side of a pair as it is now. */
export interface Side {
  hash: string;
}

/** What both sides were at the last sync of the pair. */
export interface Synced {
  hash: string;
}

/** What to do with one essay/file pair. */
export enum FolderAction {
  /** All agree, or there is nothing on either side. */
  Nothing = 'nothing',
  /** Both sides agree but the last sync does not know it: remember the hash, touch nothing. */
  Record = 'record',
  /** Only the essay changed: write it to the file. */
  WriteFile = 'write_file',
  /** Only the file changed: read it into the essay. */
  ReadFile = 'read_file',
  /** A file with no essay that is new, or was edited outside after its essay was deleted: make an essay. */
  CreateEssay = 'create_essay',
  /** An essay with no file that is new, or was edited after its file was deleted outside: write a file. */
  CreateFile = 'create_file',
  /** The file was deleted outside and the essay is as it was synced: delete the essay. */
  DeleteEssay = 'delete_essay',
  /** The essay was deleted in Leaf and the file is as it was synced: delete the file. */
  DeleteFile = 'delete_file',
  /** Both changed, or they differ with no last sync to tell which: keep both (see [conflictName]). */
  Conflict = 'conflict',
}

/**
 * The two-way decision for one pair. [file] and [essay] are null when that side is missing; [last] is null
 * when the pair was never synced. A deletion wins only over a side that has not changed since the last sync,
 * so nothing edited is ever deleted.
 */
export function decide(file: Side | null, essay: Side | null, last: Synced | null): FolderAction {
  if (file !== null && essay !== null) {
    if (last !== null && file.hash === last.hash && essay.hash === last.hash) {
      return FolderAction.Nothing;
    }
    if (file.hash === essay.hash) {
      return FolderAction.Record;
    }
    if (last === null) {
      return FolderAction.Conflict;
    }
    const fileChanged = file.hash !== last.hash;
    const essayChanged = essay.hash !== last.hash;
    if (fileChanged && essayChanged) {
      return FolderAction.Conflict;
    }
    return fileChanged ? FolderAction.ReadFile : FolderAction.WriteFile;
  }
  if (file !== null) {
    return last !== null && file.hash === last.hash ? FolderAction.DeleteFile : FolderAction.CreateEssay;
  }
  if (essay !== null) {
    return last !== null && essay.hash === last.hash ? FolderAction.DeleteEssay : FolderAction.CreateFile;
  }
  return FolderAction.Nothing;
}
