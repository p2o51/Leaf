import { BlockKind, EssayBlock } from './Models';

/**
 * A piece of the 写作台 (电脑模式) page: a run of text blocks edited together as one Markdown text, or a
 * block Leaf only shows (a photo, a file, a rule, anything Notion holds more of than Leaf can write back).
 * Port of the Android DeskText.kt (Leaf PR #15); the two must agree, case for case (tools/core-test).
 */
export enum DeskPartKind {
  Text = 'text',
  Shown = 'shown',
}

export class DeskPart {
  readonly kind: DeskPartKind;
  readonly key: string;
  /** Text parts: the run's blocks. */
  readonly blocks: EssayBlock[];
  /** Shown parts: the one block. */
  readonly block: EssayBlock | null;

  private constructor(kind: DeskPartKind, key: string, blocks: EssayBlock[], block: EssayBlock | null) {
    this.kind = kind;
    this.key = key;
    this.blocks = blocks;
    this.block = block;
  }

  static text(key: string, blocks: EssayBlock[]): DeskPart {
    return new DeskPart(DeskPartKind.Text, key, blocks, null);
  }

  static shown(block: EssayBlock): DeskPart {
    return new DeskPart(DeskPartKind.Shown, block.id, [], block);
  }
}

/** One block parsed out of a run's text, and where its words sit in that text (for 来源 marks). */
export class DeskLine {
  readonly type: string;
  readonly text: string;
  readonly textStart: number;

  constructor(type: string, text: string, textStart: number) {
    this.type = type;
    this.text = text;
    this.textStart = textStart;
  }
}

/** The run's text and, for each block in it, where that block's words start. */
export class DeskRendered {
  readonly text: string;
  readonly starts: number[];

  constructor(text: string, starts: number[]) {
    this.text = text;
    this.starts = starts;
  }
}

/** A run's blocks after an edit, and the ids of blocks that are gone. */
export class DeskUpdate {
  readonly blocks: EssayBlock[];
  readonly gone: Set<string>;

  constructor(blocks: EssayBlock[], gone: Set<string>) {
    this.blocks = blocks;
    this.gone = gone;
  }
}

/** A half-open range [start, end) in a run's text; empty when start == end. */
export class Span {
  readonly start: number;
  readonly end: number;

  constructor(start: number, end: number) {
    this.start = start;
    this.end = end;
  }

  get isEmpty(): boolean {
    return this.end <= this.start;
  }

  contains(i: number): boolean {
    return i >= this.start && i < this.end;
  }

  static readonly EMPTY: Span = new Span(0, 0);
}

const LIST_TYPES: Set<string> = new Set<string>(['bulleted_list_item', 'numbered_list_item']);
const HEADING: RegExp = new RegExp('^(#{1,3}) ');
const BULLET: RegExp = new RegExp('^[-*+] ');
const NUMBERED: RegExp = new RegExp('^\\d{1,3}[.)] ');
const QUOTE: RegExp = new RegExp('^> ?');

class Marker {
  readonly type: string;
  readonly length: number;

  constructor(type: string, length: number) {
    this.type = type;
    this.length = length;
  }
}

/**
 * Markdown in, Notion blocks out, for the 写作台. Pure, so the round trip is tested without ArkUI.
 *
 * A blank line ends a block, as in Markdown; a single line break stays inside the block (Notion keeps
 * it as a soft break). List items and headings start a block of their own even without a blank line,
 * so `- a` / `- b` on consecutive lines are two items. `#tag` without a space stays text: Leaf's tags are
 * written that way.
 */
export class DeskText {
  /** Block types the 写作台 edits as Markdown; everything else is shown, not edited. */
  static readonly Editable: Set<string> = new Set<string>([
    'paragraph', 'heading_1', 'heading_2', 'heading_3', 'quote', 'bulleted_list_item', 'numbered_list_item',
  ]);

  static editable(block: EssayBlock): boolean {
    return block.kind === BlockKind.Paragraph && !block.fixed && DeskText.Editable.has(block.type) &&
      DeskText.roundTrips(block);
  }

  /**
   * Whether the block reads back as itself from its Markdown. A paragraph whose words start with `# ` or
   * `- `, or a quote with a line starting `>`, would turn into another block (or lose a character) the
   * first time any block in its run is edited, so the 写作台 shows it, unedited, instead.
   */
  private static roundTrips(block: EssayBlock): boolean {
    if (block.text.length === 0) {
      return true;
    }
    const lines = DeskText.parse(DeskText.prefix(block.type, 1) + block.text);
    return lines.length === 1 && lines[0].type === block.type && lines[0].text === block.text;
  }

  /** The page as runs and shown blocks. A run always follows the last shown block, so there is somewhere to keep writing. */
  static parts(blocks: EssayBlock[]): DeskPart[] {
    const out: DeskPart[] = [];
    let run: EssayBlock[] = [];
    let key = 'run:start';
    for (const b of blocks) {
      if (DeskText.editable(b)) {
        run.push(b);
      } else {
        out.push(DeskPart.text(key, run));
        out.push(DeskPart.shown(b));
        run = [];
        key = `run:after:${b.id}`;
      }
    }
    out.push(DeskPart.text(key, run));
    // An empty run between two shown blocks (or before the first) has nothing to edit; keep only the
    // last one, which is where new writing goes.
    const last = out.length - 1;
    return out.filter((p: DeskPart, i: number) => p.kind !== DeskPartKind.Text || p.blocks.length > 0 || i === last);
  }

  /** The page's blocks again, in order, from its parts. */
  static blocks(parts: DeskPart[]): EssayBlock[] {
    const out: EssayBlock[] = [];
    for (const p of parts) {
      if (p.kind === DeskPartKind.Text) {
        for (const b of p.blocks) {
          out.push(b);
        }
      } else if (p.block !== null) {
        out.push(p.block);
      }
    }
    return out;
  }

  static prefix(type: string, num: number): string {
    switch (type) {
      case 'heading_1':
        return '# ';
      case 'heading_2':
        return '## ';
      case 'heading_3':
        return '### ';
      case 'quote':
        return '> ';
      case 'bulleted_list_item':
        return '- ';
      case 'numbered_list_item':
        return `${num}. `;
      default:
        return '';
    }
  }

  static render(blocks: EssayBlock[]): DeskRendered {
    let text = '';
    const starts: number[] = [];
    let num = 0;
    for (let i = 0; i < blocks.length; i++) {
      const b = blocks[i];
      if (i > 0) {
        text += (LIST_TYPES.has(b.type) && LIST_TYPES.has(blocks[i - 1].type)) ? '\n' : '\n\n';
      }
      num = b.type === 'numbered_list_item' ? num + 1 : 0;
      text += DeskText.prefix(b.type, num);
      starts.push(text.length);
      text += b.text;
    }
    return new DeskRendered(text, starts);
  }

  private static startsBlock(line: string): boolean {
    return HEADING.test(line) || BULLET.test(line) || NUMBERED.test(line);
  }

  private static marker(line: string): Marker {
    const h = HEADING.exec(line);
    if (h !== null) {
      return new Marker(`heading_${h[1].length}`, h[0].length);
    }
    const b = BULLET.exec(line);
    if (b !== null) {
      return new Marker('bulleted_list_item', b[0].length);
    }
    const n = NUMBERED.exec(line);
    if (n !== null) {
      return new Marker('numbered_list_item', n[0].length);
    }
    const q = QUOTE.exec(line);
    if (q !== null) {
      return new Marker('quote', q[0].length);
    }
    return new Marker('paragraph', 0);
  }

  /** The blocks a run's text holds, in order, with where each one's words start in [text]. */
  static parse(text: string): DeskLine[] {
    const out: DeskLine[] = [];
    let type = '';
    let start = -1;
    let words = '';
    const flush = (): void => {
      if (start >= 0) {
        out.push(new DeskLine(type, words, start));
      }
      start = -1;
      words = '';
    };
    let at = 0;
    for (const line of text.split('\n')) {
      const lineStart = at;
      at += line.length + 1;
      if (line.trim().length === 0) {
        flush();
        continue;
      }
      if (start >= 0 && !DeskText.startsBlock(line)) {
        // A soft break inside the block; a quote's later lines may repeat the marker.
        const m = type === 'quote' ? QUOTE.exec(line) : null;
        words += '\n' + (m !== null ? line.substring(m[0].length) : line);
        continue;
      }
      flush();
      const mk = DeskText.marker(line);
      type = mk.type;
      start = lineStart + mk.length;
      words = line.substring(mk.length);
    }
    flush();
    return out;
  }

  /**
   * The run's blocks after its text became [text]: unchanged blocks keep their ids, an edited one keeps
   * its id (so Notion updates it in place), new ones get [newId].
   */
  static update(old: EssayBlock[], text: string, newId: () => string): DeskUpdate {
    const lines = DeskText.parse(text);
    const pairs = DeskText.match(
      old.map((b: EssayBlock) => DeskText.key(b.type, b.text)),
      lines.map((l: DeskLine) => DeskText.key(l.type, l.text)));
    const used = new Set<number>();
    const blocks: EssayBlock[] = [];
    for (let j = 0; j < lines.length; j++) {
      const i = pairs[j];
      if (i >= 0) {
        used.add(i);
        blocks.push(old[i].withText(lines[j].text, lines[j].type));
      } else {
        blocks.push(EssayBlock.paragraph(newId(), lines[j].text, lines[j].type));
      }
    }
    const gone = new Set<string>();
    for (let i = 0; i < old.length; i++) {
      if (!used.has(i)) {
        gone.add(old[i].id);
      }
    }
    return new DeskUpdate(blocks, gone);
  }

  private static key(type: string, text: string): string {
    return type + '\u0000' + text;
  }

  /**
   * For each new line, the old block it continues, or -1 for a new block. Equal lines are matched first
   * (longest common subsequence); in each gap between them the leftovers pair up in order, so a
   * paragraph that was edited keeps its block rather than becoming a delete plus a create.
   */
  static match(old: string[], next: string[]): number[] {
    const n = old.length;
    const m = next.length;
    const lcs: number[][] = [];
    for (let i = 0; i <= n; i++) {
      const row: number[] = [];
      for (let j = 0; j <= m; j++) {
        row.push(0);
      }
      lcs.push(row);
    }
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        lcs[i][j] = old[i] === next[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    const result: number[] = [];
    for (let j = 0; j < m; j++) {
      result.push(-1);
    }
    const anchorsOld: number[] = [];
    const anchorsNew: number[] = [];
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (old[i] === next[j]) {
        anchorsOld.push(i);
        anchorsNew.push(j);
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
    for (let k = 0; k < anchorsOld.length; k++) {
      result[anchorsNew[k]] = anchorsOld[k];
    }
    anchorsOld.push(n);
    anchorsNew.push(m);
    let prevOld = -1;
    let prevNew = -1;
    for (let k = 0; k < anchorsOld.length; k++) {
      const a = anchorsOld[k];
      const b = anchorsNew[k];
      let x = prevOld + 1;
      let y = prevNew + 1;
      while (x < a && y < b) {
        result[y] = x;
        x++;
        y++;
      }
      prevOld = a;
      prevNew = b;
    }
    return result;
  }
}

const SENTENCE_ENDS: string = '。！？!?…\n';
const CLOSERS: string = '”」』）)"\'';

/** Sentence and paragraph spans for 专注, over a run's Markdown text. */
export class DeskFocus {
  private static endsSentence(text: string, i: number): boolean {
    const c = text.charAt(i);
    if (SENTENCE_ENDS.indexOf(c) >= 0) {
      return true;
    }
    // A Latin full stop ends a sentence only before a space or the end, not inside 3.14 or e.g.
    return c === '.' && (i + 1 >= text.length || text.charAt(i + 1) === ' ' || text.charAt(i + 1) === '\n');
  }

  /** The sentence around [offset]: back to just after the previous end, on through its own end mark (and a closing quote). */
  static sentence(text: string, offset: number): Span {
    if (text.length === 0) {
      return Span.EMPTY;
    }
    const at = Math.min(Math.max(offset, 0), text.length);
    let a = at;
    while (a > 0 && !DeskFocus.endsSentence(text, a - 1)) {
      a--;
    }
    while (a < text.length && text.charAt(a) === ' ') {
      a++;
    }
    let b = at;
    while (b < text.length && !DeskFocus.endsSentence(text, b)) {
      b++;
    }
    if (b < text.length && text.charAt(b) !== '\n') {
      b++;
    }
    while (b < text.length && CLOSERS.indexOf(text.charAt(b)) >= 0) {
      b++;
    }
    return new Span(a, b);
  }

  /** The paragraph around [offset]: the block between blank lines (a list item is its own line). */
  static paragraph(text: string, offset: number): Span {
    if (text.length === 0) {
      return Span.EMPTY;
    }
    const at = Math.min(Math.max(offset, 0), text.length);
    const lines = DeskText.parse(text);
    let line: DeskLine | null = null;
    for (const l of lines) {
      if (l.textStart <= at) {
        line = l;
      }
    }
    if (line === null) {
      return Span.EMPTY;
    }
    const head = line.textStart > 0 ? text.lastIndexOf('\n', line.textStart - 1) + 1 : 0;
    return new Span(head, line.textStart + line.text.length);
  }
}
