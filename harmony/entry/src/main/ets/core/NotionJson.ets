import { NotionColor } from './Models';

/**
 * Notion's JSON in Leaf's terms. Same rules as the Android NotionJson.kt / LeafNotion.kt (Notion API
 * 2026-03-11, data sources): which row is the wall, which multi-select holds the tags, which text blocks
 * Leaf can write back losslessly ("fixed" when it cannot).
 *
 * Incoming JSON is read through the interfaces below (JSON.parse(...) as X); outgoing bodies are built as
 * Json records because a block's payload sits under a key named after its type.
 */

export type Json = Record<string, Object | null>;

export interface RichText {
  type?: string;
  plain_text?: string;
  href?: string | null;
  text?: RichTextContent;
  annotations?: Annotations;
}

export interface RichTextContent {
  content?: string;
  link?: Object | null;
}

export interface Annotations {
  bold?: boolean;
  italic?: boolean;
  strikethrough?: boolean;
  underline?: boolean;
  code?: boolean;
  color?: string;
}

export interface FileRef {
  type?: string;
  name?: string;
  external?: UrlHolder;
  file?: UrlHolder;
  caption?: RichText[];
}

export interface UrlHolder {
  url?: string;
}

export interface SelectOption {
  name?: string;
  color?: string;
}

export interface PageProperty {
  id?: string;
  name?: string;
  type?: string;
  title?: RichText[];
  select?: SelectOption | null;
  multi_select?: SelectOption[];
  checkbox?: boolean;
}

export interface NotionPage {
  object?: string;
  id: string;
  in_trash?: boolean;
  created_time?: string;
  last_edited_time?: string;
  cover?: FileRef | null;
  properties?: Record<string, PageProperty>;
}

export interface NotionDataSource {
  id: string;
  title?: RichText[];
  properties?: Record<string, PageProperty>;
}

/** A text block's payload, or a file's: Notion puts it under the block's type. */
export interface BlockValue {
  rich_text?: RichText[];
  color?: string;
  checked?: boolean;
  is_toggleable?: boolean;
  caption?: RichText[];
  type?: string;
  name?: string;
  external?: UrlHolder;
  file?: UrlHolder;
}

export interface NotionBlock {
  object?: string;
  id: string;
  type?: string;
  in_trash?: boolean;
  has_children?: boolean;
  created_time?: string;
  paragraph?: BlockValue;
  heading_1?: BlockValue;
  heading_2?: BlockValue;
  heading_3?: BlockValue;
  quote?: BlockValue;
  bulleted_list_item?: BlockValue;
  numbered_list_item?: BlockValue;
  to_do?: BlockValue;
  callout?: BlockValue;
  toggle?: BlockValue;
  image?: BlockValue;
  file?: BlockValue;
  pdf?: BlockValue;
  video?: BlockValue;
  audio?: BlockValue;
}

export interface NotionList<T> {
  results?: T[];
  has_more?: boolean;
  next_cursor?: string | null;
}

export interface NotionUser {
  name?: string;
  bot?: NotionBot;
}

export interface NotionBot {
  workspace_name?: string;
}

export class PropertyInfo {
  readonly id: string;
  readonly name: string;
  readonly type: string;

  constructor(id: string, name: string, type: string) {
    this.id = id;
    this.name = name;
    this.type = type;
  }
}

export class DataSourceInfo {
  readonly id: string;
  readonly name: string;
  readonly properties: PropertyInfo[];

  constructor(id: string, name: string, properties: PropertyInfo[]) {
    this.id = id;
    this.name = name;
    this.properties = properties;
  }

  get titleProperty(): string {
    const t = this.properties.find((p: PropertyInfo) => p.type === 'title');
    return t !== undefined ? t.name : '';
  }

  has(name: string, type: string): boolean {
    return this.properties.some((p: PropertyInfo) => p.name === name && p.type === type);
  }
}

export class RemoteRow {
  pageId: string = '';
  title: string = '';
  isWall: boolean = false;
  color: NotionColor = NotionColor.Default;
  pinned: boolean = false;
  coverUrl: string = '';
  editedAt: number = 0;
  createdAt: number = 0;
  tags: string[] = [];
}

/** One top-level block of an essay body. [fixed]: a text block Leaf cannot write again as it is. */
export class RemoteBlock {
  readonly blockId: string;
  readonly type: string;
  readonly text: string;
  readonly color: NotionColor;
  readonly url: string;
  readonly fixed: boolean;

  constructor(blockId: string, type: string, text: string, color: NotionColor, url: string, fixed: boolean) {
    this.blockId = blockId;
    this.type = type;
    this.text = text;
    this.color = color;
    this.url = url;
    this.fixed = fixed;
  }

  get editable(): boolean {
    return TEXT_TYPES.has(this.type);
  }
}

export const TEXT_TYPES: Set<string> = new Set<string>([
  'paragraph', 'heading_1', 'heading_2', 'heading_3', 'quote', 'bulleted_list_item', 'numbered_list_item', 'to_do',
  'callout', 'toggle',
]);

/** Notion's file-like blocks: Leaf shows them as a card that opens the file, and leaves them as they are. */
export const FILE_TYPES: Set<string> = new Set<string>(['file', 'pdf', 'video', 'audio']);

/** Leaf's database contract. Field names are what Leaf adds when missing; the title keeps its own name. */
export class LeafSchema {
  static readonly Type: string = '类型';
  static readonly TypeEssay: string = '随笔';
  static readonly TypeWall: string = '涂鸦墙';
  static readonly CardColor: string = '卡片颜色';
  static readonly Pinned: string = '置顶';
  static readonly Tags: string = '标签';
  static readonly WallTitle: string = '涂鸦墙';

  /** The properties PATCH body for whatever is missing, or null when the schema is already complete. */
  static missingProperties(source: DataSourceInfo): Json | null {
    const add: Json = {};
    let missing = false;
    if (!source.has(LeafSchema.Type, 'select')) {
      add[LeafSchema.Type] = NotionJson.selectSchema([
        NotionJson.option(LeafSchema.TypeEssay, 'default'), NotionJson.option(LeafSchema.TypeWall, 'yellow')]);
      missing = true;
    }
    if (!source.has(LeafSchema.CardColor, 'select')) {
      add[LeafSchema.CardColor] = NotionJson.selectSchema(
        NotionColor.entries.map((c: NotionColor) => NotionJson.option(c.label, c.apiName)));
      missing = true;
    }
    if (!source.has(LeafSchema.Pinned, 'checkbox')) {
      const checkbox: Json = {};
      checkbox['checkbox'] = NotionJson.obj();
      add[LeafSchema.Pinned] = checkbox;
      missing = true;
    }
    return missing ? add : null;
  }
}

const TAG_FIELD_NAMES: string[] = [LeafSchema.Tags, 'Tags', 'Tag', 'Labels', 'Label'];

function parseTime(value: string | undefined): number {
  if (value === undefined || value.length === 0) {
    return 0;
  }
  const t = Date.parse(value);
  return Number.isNaN(t) ? 0 : t;
}

export class NotionJson {
  static obj(): Json {
    const o: Json = {};
    return o;
  }

  static option(name: string, color: string): Json {
    const o: Json = {};
    o['name'] = name;
    o['color'] = color;
    return o;
  }

  static selectSchema(options: Json[]): Json {
    const inner: Json = {};
    inner['options'] = options;
    const o: Json = {};
    o['select'] = inner;
    return o;
  }

  static plain(array: RichText[] | undefined): string {
    if (array === undefined || array === null) {
      return '';
    }
    let out = '';
    for (const part of array) {
      const p = part.plain_text ?? '';
      out += p.length > 0 ? p : (part.text?.content ?? '');
    }
    return out;
  }

  /** Whether rich text is only unformatted words, the one thing textBlock writes back exactly. */
  static isPlain(array: RichText[] | undefined): boolean {
    if (array === undefined || array === null) {
      return true;
    }
    return array.every((part: RichText) => {
      const a = part.annotations;
      const link = part.text?.link;
      const formatted = a !== undefined && (a.bold === true || a.italic === true || a.strikethrough === true ||
        a.underline === true || a.code === true || (a.color !== undefined && a.color !== 'default'));
      return (part.type ?? 'text') === 'text' && (link === undefined || link === null) &&
        (part.href === undefined || part.href === null) && !formatted;
    });
  }

  static fileUrl(file: FileRef | BlockValue | undefined | null): string {
    if (file === undefined || file === null) {
      return '';
    }
    if (file.type === 'external') {
      return file.external?.url ?? '';
    }
    if (file.type === 'file') {
      return file.file?.url ?? '';
    }
    return '';
  }

  static dataSource(json: NotionDataSource): DataSourceInfo {
    const props = json.properties ?? NotionJson.emptyProps();
    const list: PropertyInfo[] = Object.keys(props).map((key: string) => {
      const p = props[key];
      return new PropertyInfo(p.id ?? '', p.name ?? key, p.type ?? '');
    });
    const name = NotionJson.plain(json.title).trim();
    return new DataSourceInfo(json.id, name.length > 0 ? name : '未命名数据库', list);
  }

  private static emptyProps(): Record<string, PageProperty> {
    const r: Record<string, PageProperty> = {};
    return r;
  }

  static row(json: NotionPage, titleProperty: string): RemoteRow {
    const props = json.properties ?? NotionJson.emptyProps();
    let title = '';
    const named = titleProperty.length > 0 ? props[titleProperty] : undefined;
    if (named !== undefined && named.title !== undefined) {
      title = NotionJson.plain(named.title);
    } else {
      for (const key of Object.keys(props)) {
        if (props[key].type === 'title') {
          title = NotionJson.plain(props[key].title);
          break;
        }
      }
    }
    const typeProp = props[LeafSchema.Type];
    const type = typeProp?.select?.name ?? '';
    const colorOption = props[LeafSchema.CardColor]?.select;
    const row = new RemoteRow();
    row.pageId = json.id;
    row.title = title;
    row.isWall = type === LeafSchema.TypeWall || (type.length === 0 && title.trim() === LeafSchema.WallTitle);
    row.color = colorOption !== undefined && colorOption !== null ? NotionJson.optionColor(colorOption) : NotionColor.Default;
    row.pinned = props[LeafSchema.Pinned]?.checkbox === true;
    row.coverUrl = NotionJson.fileUrl(json.cover);
    row.editedAt = parseTime(json.last_edited_time);
    row.createdAt = parseTime(json.created_time);
    row.tags = NotionJson.tags(props);
    return row;
  }

  /**
   * Tags are a multi-select named like tags (标签, then Tags / Tag / Labels in any case), or else the row's
   * first multi-select of any name. The names come first because Notion's property order is not the
   * table's: a database with 作者 and Tags must not read authors as tags.
   */
  static tags(props: Record<string, PageProperty>): string[] {
    const keys = Object.keys(props).filter((k: string) => props[k].type === 'multi_select');
    let field: PageProperty | undefined = undefined;
    for (const name of TAG_FIELD_NAMES) {
      const k = keys.find((key: string) => key.trim().toLowerCase() === name.toLowerCase());
      if (k !== undefined) {
        field = props[k];
        break;
      }
    }
    if (field === undefined && keys.length > 0) {
      field = props[keys[0]];
    }
    if (field === undefined) {
      return [];
    }
    const out: string[] = [];
    for (const o of field.multi_select ?? []) {
      const n = (o.name ?? '').trim();
      if (n.length > 0) {
        out.push(n);
      }
    }
    return out;
  }

  /** Card colour reads the option's Notion colour first, so renamed options still work, then its name. */
  static optionColor(option: SelectOption): NotionColor {
    const byColor = NotionColor.fromApi(option.color);
    if (byColor !== NotionColor.Default) {
      return byColor;
    }
    return NotionColor.entries.find((c: NotionColor) => c.label === option.name) ?? NotionColor.Default;
  }

  static valueOf(block: NotionBlock): BlockValue | undefined {
    switch (block.type) {
      case 'paragraph':
        return block.paragraph;
      case 'heading_1':
        return block.heading_1;
      case 'heading_2':
        return block.heading_2;
      case 'heading_3':
        return block.heading_3;
      case 'quote':
        return block.quote;
      case 'bulleted_list_item':
        return block.bulleted_list_item;
      case 'numbered_list_item':
        return block.numbered_list_item;
      case 'to_do':
        return block.to_do;
      case 'callout':
        return block.callout;
      case 'toggle':
        return block.toggle;
      case 'image':
        return block.image;
      case 'file':
        return block.file;
      case 'pdf':
        return block.pdf;
      case 'video':
        return block.video;
      case 'audio':
        return block.audio;
      default:
        return undefined;
    }
  }

  /** What to call an attached file: its name in Notion, else its caption, else the last part of its URL. */
  static fileName(value: BlockValue): string {
    const name = (value.name ?? '').trim();
    if (name.length > 0) {
      return name;
    }
    const caption = NotionJson.plain(value.caption).trim();
    if (caption.length > 0) {
      return caption;
    }
    const url = NotionJson.fileUrl(value);
    if (url.length === 0) {
      return '';
    }
    const path = url.split('?')[0];
    const last = path.substring(path.lastIndexOf('/') + 1);
    try {
      return decodeURIComponent(last);
    } catch (e) {
      return last;
    }
  }

  static bodyBlock(block: NotionBlock): RemoteBlock | null {
    if (block.in_trash === true) {
      return null;
    }
    const type = block.type ?? '';
    const value: BlockValue = NotionJson.valueOf(block) ?? NotionJson.emptyValue();
    let text = '';
    if (TEXT_TYPES.has(type)) {
      text = NotionJson.plain(value.rich_text);
    } else if (type === 'image') {
      text = NotionJson.plain(value.caption);
    } else if (FILE_TYPES.has(type)) {
      text = NotionJson.fileName(value);
    }
    const url = (type === 'image' || FILE_TYPES.has(type)) ? NotionJson.fileUrl(value) : '';
    const fixed = TEXT_TYPES.has(type) && (block.has_children === true || type === 'callout' || type === 'toggle' ||
      value.is_toggleable === true || value.checked === true || !NotionJson.isPlain(value.rich_text));
    return new RemoteBlock(block.id, type, text, NotionColor.fromApi(value.color), url, fixed);
  }

  private static emptyValue(): BlockValue {
    const v: BlockValue = {};
    return v;
  }

  /** Notion caps one text object at 2000 characters, so long text becomes several. */
  static richText(text: string): Json[] {
    const out: Json[] = [];
    for (let i = 0; i < text.length; i += 2000) {
      const content: Json = {};
      content['content'] = text.substring(i, i + 2000);
      const part: Json = {};
      part['type'] = 'text';
      part['text'] = content;
      out.push(part);
    }
    return out;
  }

  /** Any text type as a new block; a to-do starts unchecked. */
  static textBlock(type: string, text: string, color: NotionColor): Json {
    const value: Json = {};
    value['rich_text'] = NotionJson.richText(text);
    value['color'] = color.backgroundApiName;
    if (type === 'to_do') {
      value['checked'] = false;
    }
    const block: Json = {};
    block['object'] = 'block';
    block['type'] = type;
    block[type] = value;
    return block;
  }

  static divider(): Json {
    const block: Json = {};
    block['object'] = 'block';
    block['type'] = 'divider';
    block['divider'] = NotionJson.obj();
    return block;
  }

  /** PATCH body that rewrites a text block's text (and type's colour), keeping its type. */
  static textUpdate(type: string, text: string, color: NotionColor | null): Json {
    const value: Json = {};
    value['rich_text'] = NotionJson.richText(text);
    if (color !== null) {
      value['color'] = color.backgroundApiName;
    }
    const body: Json = {};
    body[type] = value;
    return body;
  }

  /** Append body; 2026-03-11 uses a position object (after a block, or the start); omitted means the end. */
  static children(blocks: Json[], afterBlockId: string, atStart: boolean): Json {
    const body: Json = {};
    body['children'] = blocks;
    if (afterBlockId.length > 0) {
      const after: Json = {};
      after['id'] = afterBlockId;
      const position: Json = {};
      position['type'] = 'after_block';
      position['after_block'] = after;
      body['position'] = position;
    } else if (atStart) {
      const position: Json = {};
      position['type'] = 'start';
      body['position'] = position;
    }
    return body;
  }

  static newPage(dataSourceId: string, titleProperty: string, title: string, type: string): Json {
    const parent: Json = {};
    parent['type'] = 'data_source_id';
    parent['data_source_id'] = dataSourceId;
    const titleValue: Json = {};
    titleValue['title'] = NotionJson.richText(title);
    const select: Json = {};
    select['name'] = type;
    const typeValue: Json = {};
    typeValue['select'] = select;
    const props: Json = {};
    props[titleProperty] = titleValue;
    props[LeafSchema.Type] = typeValue;
    const body: Json = {};
    body['parent'] = parent;
    body['properties'] = props;
    return body;
  }

  static essayProperties(titleProperty: string, title: string, color: NotionColor, pinned: boolean): Json {
    const titleValue: Json = {};
    titleValue['title'] = NotionJson.richText(title);
    const colorValue: Json = {};
    if (color === NotionColor.Default) {
      colorValue['select'] = null;
    } else {
      const option: Json = {};
      option['name'] = color.label;
      colorValue['select'] = option;
    }
    const pinValue: Json = {};
    pinValue['checkbox'] = pinned;
    const props: Json = {};
    props[titleProperty] = titleValue;
    props[LeafSchema.CardColor] = colorValue;
    props[LeafSchema.Pinned] = pinValue;
    const body: Json = {};
    body['properties'] = props;
    return body;
  }

  /** Accepts a Notion URL or a bare id; returns the dashed form, or '' when there is none. */
  static extractId(value: string): string {
    const re = new RegExp('[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}', 'g');
    const path = value.split('?')[0];
    let last = '';
    let m = re.exec(path);
    while (m !== null) {
      last = m[0];
      m = re.exec(path);
    }
    if (last.length === 0) {
      return '';
    }
    const hex = last.split('-').join('').toLowerCase();
    return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}`;
  }
}
