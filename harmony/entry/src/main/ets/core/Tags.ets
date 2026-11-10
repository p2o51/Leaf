import { Essay, FilterKind, HomeFilter, NotionColor } from './Models';

/**
 * Tags on essays are the row's 标签 multi-select (see NotionJson.tags). Leaf only reads them; they are
 * edited in Notion, which is also the only place an option can be renamed. Same rules as Android Tags.kt,
 * minus the scribble wall, which the HarmonyOS app does not have yet.
 */
export class TagRules {
  /** Every tag in use, each once, in dictionary order (pinyin order for Chinese). */
  static all(essays: Essay[]): string[] {
    const seen = new Set<string>();
    for (const e of essays) {
      for (const t of e.tags) {
        seen.add(t);
      }
    }
    return Array.from(seen).sort((a: string, b: string) => a.localeCompare(b, 'zh-Hans-CN'));
  }

  /** The colours some essay actually has, in Notion's order; plain (default) is not a filter. */
  static colorsInUse(essays: Essay[]): NotionColor[] {
    const used = new Set<NotionColor>(essays.map((e: Essay) => e.color));
    return NotionColor.entries.filter((c: NotionColor) => c !== NotionColor.Default && used.has(c));
  }

  static matches(essay: Essay, filter: HomeFilter): boolean {
    switch (filter.kind) {
      case FilterKind.Color:
        return essay.color.apiName === filter.value;
      case FilterKind.Tag:
        return essay.tags.indexOf(filter.value) >= 0;
      default:
        return true;
    }
  }

  /** Home order: pinned first, then the most recently edited. */
  static ordered(essays: Essay[]): Essay[] {
    return essays.slice().sort((a: Essay, b: Essay) => {
      if (a.pinned !== b.pinned) {
        return a.pinned ? -1 : 1;
      }
      return b.editedAt - a.editedAt;
    });
  }
}
