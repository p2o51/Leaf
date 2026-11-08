import { DeskFocus } from './DeskText';

/**
 * 文风检查, after iA Writer's Style Check but for Chinese: it marks words a careful writer would look at
 * twice and never rewrites them. Every rule is a word list or a fixed-size pattern, never a free regular
 * expression, so a check over a long essay stays well under a frame. Port of Android DeskStyle.kt.
 */
export enum StyleRule {
  Filler = 'filler',
  Degree = 'degree',
  ManyDe = 'many_de',
  Long = 'long',
  Punct = 'punct',
}

export const STYLE_RULES: StyleRule[] = [StyleRule.Filler, StyleRule.Degree, StyleRule.ManyDe, StyleRule.Long, StyleRule.Punct];

export function styleRuleLabel(rule: StyleRule): string {
  switch (rule) {
    case StyleRule.Filler:
      return '填充词：进行、其实、基本上…';
    case StyleRule.Degree:
      return '程度副词：非常、十分、极其…';
    case StyleRule.ManyDe:
      return '一句里「的」超过 3 个';
    case StyleRule.Long:
      return '长句：一句超过 60 字';
    default:
      return '中文里夹了半角标点';
  }
}

/** [start] until [end] in the run's text. Long marks a whole sentence; the others mark the words. */
export class StyleHit {
  readonly start: number;
  readonly end: number;
  readonly rule: StyleRule;

  constructor(start: number, end: number, rule: StyleRule) {
    this.start = start;
    this.end = end;
    this.rule = rule;
  }
}

const LONG_SENTENCE: number = 60;
const DE_LIMIT: number = 3;
const HALF_PUNCT: string = ',;:?!';

function isHan(c: string): boolean {
  if (c.length === 0) {
    return false;
  }
  const code = c.charCodeAt(0);
  return (code >= 0x4E00 && code <= 0x9FFF) || (code >= 0x3400 && code <= 0x4DBF);
}

function isSpace(c: string): boolean {
  return c.trim().length === 0;
}

export class DeskStyle {
  static readonly Fillers: string[] = ['某种程度上', '总的来说', '一般来说', '事实上', '可以说', '基本上', '其实', '进行', '的话'];
  static readonly Degrees: string[] = ['非常', '十分', '极其', '特别', '相当', '超级'];

  static check(text: string, rules: Set<StyleRule>): StyleHit[] {
    if (text.length === 0 || rules.size === 0) {
      return [];
    }
    const hits: StyleHit[] = [];
    const words = (list: string[], rule: StyleRule): void => {
      for (const w of list) {
        let i = text.indexOf(w);
        while (i >= 0) {
          hits.push(new StyleHit(i, i + w.length, rule));
          i = text.indexOf(w, i + w.length);
        }
      }
    };
    if (rules.has(StyleRule.Filler)) {
      words(DeskStyle.Fillers, StyleRule.Filler);
    }
    if (rules.has(StyleRule.Degree)) {
      words(DeskStyle.Degrees, StyleRule.Degree);
    }
    if (rules.has(StyleRule.ManyDe) || rules.has(StyleRule.Long)) {
      let i = 0;
      while (i < text.length) {
        const s = DeskFocus.sentence(text, i);
        if (s.isEmpty) {
          i++;
          continue;
        }
        const body = text.substring(s.start, s.end);
        let de = 0;
        let visible = 0;
        for (let k = 0; k < body.length; k++) {
          const c = body.charAt(k);
          if (c === '的') {
            de++;
          }
          if (!isSpace(c)) {
            visible++;
          }
        }
        if (rules.has(StyleRule.ManyDe) && de > DE_LIMIT) {
          for (let k = 0; k < body.length; k++) {
            if (body.charAt(k) === '的') {
              hits.push(new StyleHit(s.start + k, s.start + k + 1, StyleRule.ManyDe));
            }
          }
        }
        if (rules.has(StyleRule.Long) && !body.startsWith('#') && visible > LONG_SENTENCE) {
          hits.push(new StyleHit(s.start, s.end, StyleRule.Long));
        }
        i = s.end + 1;
      }
    }
    if (rules.has(StyleRule.Punct)) {
      for (let k = 0; k < text.length; k++) {
        if (HALF_PUNCT.indexOf(text.charAt(k)) < 0) {
          continue;
        }
        const before = k > 0 && isHan(text.charAt(k - 1));
        const after = k + 1 < text.length && isHan(text.charAt(k + 1));
        if (before || after) {
          hits.push(new StyleHit(k, k + 1, StyleRule.Punct));
        }
      }
    }
    // A stable sort keeps hits at the same start in the order the rules found them, as Kotlin's sortedBy does.
    return hits.map((h: StyleHit, i: number) => new IndexedHit(h, i))
      .sort((a: IndexedHit, b: IndexedHit) => a.hit.start - b.hit.start || a.index - b.index)
      .map((x: IndexedHit) => x.hit);
  }
}

class IndexedHit {
  readonly hit: StyleHit;
  readonly index: number;

  constructor(hit: StyleHit, index: number) {
    this.hit = hit;
    this.index = index;
  }
}

/**
 * 来源 (after iA Writer's Authorship): which words were typed here and which came in pasted, so 51 can see
 * at a glance what is their own. Kept on this device per essay, by block, and never written into Notion.
 */
export enum Origin {
  Typed = 'T',
  Pasted = 'P',
}

/** A run of non-typed words inside one block's text. */
export class OriginSpan {
  readonly start: number;
  readonly end: number;
  readonly origin: Origin;

  constructor(start: number, end: number, origin: Origin) {
    this.start = start;
    this.end = end;
    this.origin = origin;
  }

  equals(other: OriginSpan): boolean {
    return this.start === other.start && this.end === other.end && this.origin === other.origin;
  }
}

export class DeskOrigins {
  private static common(before: string, after: string): number[] {
    let p = 0;
    while (p < before.length && p < after.length && before.charAt(p) === after.charAt(p)) {
      p++;
    }
    let s = 0;
    while (s < before.length - p && s < after.length - p &&
      before.charAt(before.length - 1 - s) === after.charAt(after.length - 1 - s)) {
      s++;
    }
    return [p, s];
  }

  /** The origins after an edit turned [before] into [after]; the inserted characters are [inserted]. */
  static splice(origins: Origin[], before: string, after: string, inserted: Origin): Origin[] {
    const ps = DeskOrigins.common(before, after);
    const p = ps[0];
    const s = ps[1];
    const added = after.length - p - s;
    const keep: Origin[] = origins.slice(0, p);
    for (let k = 0; k < added; k++) {
      keep.push(inserted);
    }
    for (const o of origins.slice(Math.max(before.length - s, 0))) {
      keep.push(o);
    }
    if (keep.length === after.length) {
      return keep;
    }
    const out: Origin[] = [];
    for (let k = 0; k < after.length; k++) {
      out.push(k < keep.length ? keep[k] : Origin.Typed);
    }
    return out;
  }

  /** How many characters an edit inserted, to tell a paste from typing when the keyboard did not say. */
  static insertedLength(before: string, after: string): number {
    const ps = DeskOrigins.common(before, after);
    return after.length - ps[0] - ps[1];
  }

  /** Spans for one block whose words sit at [start] in a run whose characters have [origins]. */
  static spans(origins: Origin[], start: number, length: number): OriginSpan[] {
    const at = (k: number): Origin => {
      const i = start + k;
      return i >= 0 && i < origins.length ? origins[i] : Origin.Typed;
    };
    const out: OriginSpan[] = [];
    let k = 0;
    while (k < length) {
      const o = at(k);
      if (o === Origin.Typed) {
        k++;
        continue;
      }
      let e = k;
      while (e < length && at(e) === o) {
        e++;
      }
      out.push(new OriginSpan(k, e, o));
      k = e;
    }
    return out;
  }

  /** Origins for a run's text, from each block's stored spans laid at its start. */
  static lay(length: number, starts: number[], spans: OriginSpan[][]): Origin[] {
    const out: Origin[] = [];
    for (let k = 0; k < length; k++) {
      out.push(Origin.Typed);
    }
    for (let i = 0; i < starts.length; i++) {
      const list = i < spans.length ? spans[i] : [];
      for (const span of list) {
        for (let k = span.start; k < span.end; k++) {
          const at = starts[i] + k;
          if (at >= 0 && at < length) {
            out[at] = span.origin;
          }
        }
      }
    }
    return out;
  }

  /** One line per block: id, then start-end-code triples. The same plain text the Android app writes. */
  static encode(byBlock: Map<string, OriginSpan[]>): string {
    const lines: string[] = [];
    byBlock.forEach((spans: OriginSpan[], id: string) => {
      if (spans.length > 0) {
        lines.push(id + '\t' + spans.map((s: OriginSpan) => `${s.start}-${s.end}-${s.origin}`).join(' '));
      }
    });
    return lines.join('\n');
  }

  static decode(text: string): Map<string, OriginSpan[]> {
    const out = new Map<string, OriginSpan[]>();
    for (const line of text.split('\n')) {
      const tab = line.indexOf('\t');
      if (tab <= 0) {
        continue;
      }
      const spans: OriginSpan[] = [];
      for (const part of line.substring(tab + 1).split(' ')) {
        const bits = part.split('-');
        if (bits.length !== 3) {
          continue;
        }
        const a = Number.parseInt(bits[0], 10);
        const b = Number.parseInt(bits[1], 10);
        if (Number.isNaN(a) || Number.isNaN(b)) {
          continue;
        }
        spans.push(new OriginSpan(a, b, bits[2].charAt(0) === 'P' ? Origin.Pasted : Origin.Typed));
      }
      out.set(line.substring(0, tab), spans);
    }
    return out;
  }
}
