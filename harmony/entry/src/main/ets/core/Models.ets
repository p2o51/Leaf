/**
 * Leaf's data, the same as the Android app's (data/Models.kt), written in the ArkTS subset that is also
 * plain TypeScript: tools/core-test compiles everything under core/ with tsc and runs the shared
 * acceptance cases on Node, because the cloud machine that writes this code cannot run the HarmonyOS SDK.
 * So nothing in core/ imports a @kit module.
 */

/** The nine Notion highlight colours plus "none". Essays store it as the 卡片颜色 select option. */
export class NotionColor {
  static readonly Default: NotionColor = new NotionColor('default', '默认');
  static readonly Gray: NotionColor = new NotionColor('gray', '灰');
  static readonly Brown: NotionColor = new NotionColor('brown', '棕');
  static readonly Orange: NotionColor = new NotionColor('orange', '橙');
  static readonly Yellow: NotionColor = new NotionColor('yellow', '黄');
  static readonly Green: NotionColor = new NotionColor('green', '绿');
  static readonly Blue: NotionColor = new NotionColor('blue', '蓝');
  static readonly Purple: NotionColor = new NotionColor('purple', '紫');
  static readonly Pink: NotionColor = new NotionColor('pink', '粉');
  static readonly Red: NotionColor = new NotionColor('red', '红');

  static readonly entries: NotionColor[] = [
    NotionColor.Default, NotionColor.Gray, NotionColor.Brown, NotionColor.Orange, NotionColor.Yellow,
    NotionColor.Green, NotionColor.Blue, NotionColor.Purple, NotionColor.Pink, NotionColor.Red,
  ];

  readonly apiName: string;
  readonly label: string;

  private constructor(apiName: string, label: string) {
    this.apiName = apiName;
    this.label = label;
  }

  get backgroundApiName(): string {
    return this.apiName === 'default' ? this.apiName : `${this.apiName}_background`;
  }

  /** Both spellings Notion uses map here: a block's `yellow_background` and a select option's `yellow`. */
  static fromApi(name: string | undefined | null): NotionColor {
    if (name === undefined || name === null) {
      return NotionColor.Default;
    }
    const hit = NotionColor.entries.find((c: NotionColor) => c.apiName === name || c.backgroundApiName === name);
    return hit ?? NotionColor.Default;
  }
}

/** Block kinds Leaf tells apart. A Notion block type string rides along in [EssayBlock.type]. */
export enum BlockKind {
  /** A text block (paragraph, heading, quote, list item…); Leaf edits its words and keeps its type. */
  Paragraph = 'paragraph',
  /** An image block; its caption is [EssayBlock.text]. */
  Photo = 'photo',
  /** A file, PDF, video or audio block: a card that opens it. */
  Attachment = 'attachment',
  /** Anything else Notion holds (table, embed, divider…): shown, never edited. */
  Other = 'other',
}

/**
 * One top-level block of an essay. Kotlin's sealed interface becomes one class with a [kind], because
 * ArkTS has no structural unions; the fields a kind does not use stay at their defaults.
 */
export class EssayBlock {
  id: string;
  kind: BlockKind;
  /** Notion's block type: paragraph, heading_2, quote, image, divider, file… */
  type: string;
  text: string;
  color: NotionColor;
  /** A text block that holds more in Notion than Leaf can write back (children, links, formatting). */
  fixed: boolean;
  /** Image or file URL (Notion signs them for about an hour), or a local file for a photo not yet sent. */
  url: string;
  /** Notion's block id; empty until the outbox has created the block. The local [id] never changes. */
  blockId: string;

  constructor(id: string, kind: BlockKind, type: string, text: string, color: NotionColor = NotionColor.Default,
    fixed: boolean = false, url: string = '') {
    this.id = id;
    this.kind = kind;
    this.type = type;
    this.text = text;
    this.color = color;
    this.fixed = fixed;
    this.url = url;
    this.blockId = '';
  }

  static paragraph(id: string, text: string, type: string = 'paragraph', color: NotionColor = NotionColor.Default,
    fixed: boolean = false): EssayBlock {
    return new EssayBlock(id, BlockKind.Paragraph, type, text, color, fixed);
  }

  /** The same block with other words and type, keeping its id and colour (an edit in the 写作台). */
  withText(text: string, type: string): EssayBlock {
    const b = new EssayBlock(this.id, this.kind, type, text, this.color, this.fixed, this.url);
    b.blockId = this.blockId;
    return b;
  }

  /** Whether Notion would need to hear about the difference between this and [other] (same block). */
  contentDiffers(other: EssayBlock): boolean {
    return this.type !== other.type || this.text !== other.text || this.color !== other.color;
  }

  sameAs(other: EssayBlock): boolean {
    return this.id === other.id && this.kind === other.kind && this.type === other.type && this.text === other.text &&
      this.color === other.color && this.fixed === other.fixed && this.url === other.url && this.blockId === other.blockId;
  }
}

/** Notion's divider block type; Leaf draws it as a rule. */
export const DIVIDER: string = 'divider';

/** Every database row other than the wall. The title is the page title; blocks are the page body. */
export class Essay {
  /** Local id; stays the same when the row first reaches Notion. */
  id: string;
  /** Notion page id, empty until the outbox has created the page. */
  pageId: string;
  title: string;
  blocks: EssayBlock[];
  color: NotionColor;
  pinned: boolean;
  /** Epoch millis of Notion's last_edited_time (or of the local edit while it waits to go out). */
  editedAt: number;
  createdAt: number;
  /** The card's words before the body is loaded. */
  preview: string;
  /** The row's 标签 multi-select, read from Notion; see TagRules. */
  tags: string[];
  coverUrl: string;
  bodyLoaded: boolean;

  constructor(id: string, title: string) {
    this.id = id;
    this.pageId = '';
    this.title = title;
    this.blocks = [];
    this.color = NotionColor.Default;
    this.pinned = false;
    this.editedAt = 0;
    this.createdAt = 0;
    this.preview = '';
    this.tags = [];
    this.coverUrl = '';
    this.bodyLoaded = false;
  }

  get previewText(): string {
    const words = this.blocks.filter((b: EssayBlock) => b.kind === BlockKind.Paragraph).map((b: EssayBlock) => b.text)
      .join(' ').trim();
    return words.length > 0 ? words : this.preview;
  }
}

/** What home shows: everything, one colour, or one tag. */
export enum FilterKind {
  All = 'all',
  Color = 'color',
  Tag = 'tag',
}

export class HomeFilter {
  readonly kind: FilterKind;
  readonly value: string;

  constructor(kind: FilterKind, value: string = '') {
    this.kind = kind;
    this.value = value;
  }

  static readonly All: HomeFilter = new HomeFilter(FilterKind.All);

  equals(other: HomeFilter): boolean {
    return this.kind === other.kind && this.value === other.value;
  }
}
