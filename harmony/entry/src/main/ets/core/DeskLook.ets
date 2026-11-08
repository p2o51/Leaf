import { DeskText, Span } from './DeskText';
import { Origin, StyleHit, StyleRule } from './DeskStyle';

/** Which colour a stretch of the 写作台 is drawn in; the UI maps these to theme colours. */
export enum Ink {
  /** The words, as typed. */
  Text = 'text',
  /** Words that came in pasted (来源 on). */
  Pasted = 'pasted',
  /** Markdown marks (`## `, `- `, `> `): faint, so the words lead. */
  Mark = 'mark',
  /** Words 文风 asks to look at twice: struck through and faint. */
  Struck = 'struck',
}

/** One stretch of a run's text with a single look. [faded] is outside the 专注 focus. */
export class LookSegment {
  readonly start: number;
  readonly end: number;
  readonly ink: Ink;
  readonly faded: boolean;
  readonly bold: boolean;
  /** Inside a 长句 (文风): a faint background, never a change to the words. */
  readonly shaded: boolean;

  constructor(start: number, end: number, ink: Ink, faded: boolean, bold: boolean, shaded: boolean) {
    this.start = start;
    this.end = end;
    this.ink = ink;
    this.faded = faded;
    this.bold = bold;
    this.shaded = shaded;
  }

  sameLook(o: LookSegment): boolean {
    return this.ink === o.ink && this.faded === o.faded && this.bold === o.bold && this.shaded === o.shaded;
  }
}

/**
 * How a run reads, as the Android DeskLook draws it: Markdown marks faint, headings semibold, pasted words
 * in the accent, 文风 hits struck through, long sentences shaded, everything outside the focus faded. Only
 * colours and weights change, never the characters, so the caret and the IME see the text as typed.
 * Pure, so the segments are tested on Node; the ArkUI editor turns each segment into one span style.
 */
export class DeskLook {
  /**
   * [origins] null means 来源 is off; [focus] null means 专注 is off (or the caret is elsewhere). Segments
   * cover the whole text in order, adjacent segments always look different.
   */
  static segments(text: string, origins: Origin[] | null, hits: StyleHit[], focus: Span | null): LookSegment[] {
    const n = text.length;
    if (n === 0) {
      return [];
    }
    const ink: Ink[] = [];
    const bold: boolean[] = [];
    const shaded: boolean[] = [];
    for (let i = 0; i < n; i++) {
      ink.push(origins !== null && i < origins.length && origins[i] === Origin.Pasted ? Ink.Pasted : Ink.Text);
      bold.push(false);
      shaded.push(false);
    }
    for (const line of DeskText.parse(text)) {
      const head = line.textStart > 0 ? text.lastIndexOf('\n', line.textStart - 1) + 1 : 0;
      for (let i = head; i < line.textStart; i++) {
        ink[i] = Ink.Mark;
      }
      if (line.type.startsWith('heading')) {
        for (let i = line.textStart; i < line.textStart + line.text.length && i < n; i++) {
          bold[i] = true;
        }
      }
    }
    for (const h of hits) {
      const a = Math.min(Math.max(h.start, 0), n);
      const b = Math.min(Math.max(h.end, a), n);
      for (let i = a; i < b; i++) {
        if (h.rule === StyleRule.Long) {
          shaded[i] = true;
        } else {
          ink[i] = Ink.Struck;
        }
      }
    }
    const out: LookSegment[] = [];
    let i = 0;
    while (i < n) {
      const seg = new LookSegment(i, i + 1, ink[i], focus !== null && !focus.contains(i), bold[i], shaded[i]);
      let j = i + 1;
      while (j < n && new LookSegment(j, j + 1, ink[j], focus !== null && !focus.contains(j), bold[j], shaded[j]).sameLook(seg)) {
        j++;
      }
      out.push(new LookSegment(i, j, seg.ink, seg.faded, seg.bold, seg.shaded));
      i = j;
    }
    return out;
  }
}
