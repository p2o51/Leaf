import { BlockKind, EssayBlock } from '../core/Models';
import { DeskText } from '../core/DeskText';
import { LeafApp } from '../data/LeafApp';
import { newId } from '../data/Library';
import { familyOf, DeskFont, IconButton, quietColor, REFRESH_EVERY_MS, REFRESH_REST_MS, WRITE_AFTER_MS } from './Look';
import { CAPTION_RIGHT_KEY, CAPTION_TOP_KEY, WindowChrome } from './WindowChrome';

/** The block types a touch user can turn a text block into, in the order the menu offers them. */
const TYPES: string[] = ['paragraph', 'heading_1', 'heading_2', 'heading_3', 'quote', 'bulleted_list_item', 'numbered_list_item'];

function typeLabel(type: string): string {
  switch (type) {
    case 'heading_1':
      return '标题 1';
    case 'heading_2':
      return '标题 2';
    case 'heading_3':
      return '标题 3';
    case 'quote':
      return '引用';
    case 'bulleted_list_item':
      return '列表';
    case 'numbered_list_item':
      return '有序列表';
    default:
      return '正文';
  }
}

function sizeFor(type: string): number {
  switch (type) {
    case 'heading_1':
      return 28;
    case 'heading_2':
      return 23;
    case 'heading_3':
      return 20;
    default:
      return 17;
  }
}

/** A block plus a counter, so ForEach redraws a row whose block changed under the same id. */
class Row_ {
  readonly block: EssayBlock;
  readonly version: number;

  constructor(block: EssayBlock, version: number) {
    this.block = block;
    this.version = version;
  }
}

/**
 * 平板模式 (no hardware keyboard): the essay as blocks, drag first. Long-press a block's handle to drag it
 * to a new place (Notion has no move, so the outbox writes it again there and deletes the old one); the
 * block menu changes its type or removes it. Markdown is the secondary path: 「Markdown」 opens the same
 * essay in the 写作台. Mirrors the Android 平板模式 rule from Leaf PR #14/#15.
 */
@Component
export struct TabletEditor {
  essayId: string = '';
  onBack: () => void = () => {};
  /** Shown beside the essay list rather than as its own page: no back button. */
  embedded: boolean = false;
  onDeskMode: () => void = () => {};
  @StorageProp('leaf.dark') dark: boolean = false;
  @State rows: Row_[] = [];
  @State title: string = '';
  @State status: string = '';
  @StorageProp(CAPTION_TOP_KEY) captionTop: number = 0;
  @StorageProp(CAPTION_RIGHT_KEY) captionRight: number = 0;
  private writeTimer: number = -1;
  private titleTimer: number = -1;
  private refreshTimer: number = -1;
  private lastKey: number = 0;
  private unsubscribe: () => void = () => {};

  aboutToAppear(): void {
    this.open();
    this.unsubscribe = LeafApp.sync.subscribe(() => {
      this.status = LeafApp.sync.message;
    });
    this.refreshTimer = setInterval(() => this.refresh(), REFRESH_EVERY_MS);
  }

  aboutToDisappear(): void {
    clearInterval(this.refreshTimer);
    this.unsubscribe();
    if (this.writeTimer >= 0) {
      clearTimeout(this.writeTimer);
      this.save();
    }
    if (this.titleTimer >= 0) {
      clearTimeout(this.titleTimer);
      this.saveTitle();
    }
  }

  private async open(): Promise<void> {
    await LeafApp.ready();
    const e = LeafApp.lib.essay(this.essayId);
    if (e === undefined) {
      return;
    }
    this.title = e.title;
    this.show(await LeafApp.lib.body(this.essayId));
    if (await LeafApp.sync.loadBody(this.essayId)) {
      this.show(await LeafApp.lib.body(this.essayId));
    }
  }

  private show(blocks: EssayBlock[]): void {
    this.rows = blocks.map((b: EssayBlock) => new Row_(b, 0));
  }

  private async refresh(): Promise<void> {
    if (Date.now() - this.lastKey < REFRESH_REST_MS || this.writeTimer >= 0) {
      return;
    }
    if (await LeafApp.sync.refreshBody(this.essayId)) {
      this.show(await LeafApp.lib.body(this.essayId));
    }
  }

  private blocks(): EssayBlock[] {
    return this.rows.map((r: Row_) => r.block);
  }

  private schedule(): void {
    this.lastKey = Date.now();
    if (this.writeTimer >= 0) {
      clearTimeout(this.writeTimer);
    }
    this.writeTimer = setTimeout(() => this.save(), WRITE_AFTER_MS);
  }

  private save(): void {
    this.writeTimer = -1;
    LeafApp.lib.saveBody(this.essayId, this.blocks());
  }

  private saveTitle(): void {
    this.titleTimer = -1;
    const e = LeafApp.lib.essay(this.essayId);
    if (e !== undefined) {
      LeafApp.lib.updateMeta(this.essayId, this.title, e.color, e.pinned);
    }
  }

  private edit(index: number, text: string): void {
    const r = this.rows[index];
    // The row keeps its version: rebuilding it would move the caret while typing.
    this.rows[index] = new Row_(r.block.withText(text, r.block.type), r.version);
    this.schedule();
  }

  private retype(index: number, type: string): void {
    const r = this.rows[index];
    this.rows[index] = new Row_(r.block.withText(r.block.text, type), r.version + 1);
    this.schedule();
  }

  private remove(index: number): void {
    this.rows.splice(index, 1);
    this.schedule();
  }

  private addAfter(index: number): void {
    const b = EssayBlock.paragraph(newId(), '');
    this.rows.splice(index + 1, 0, new Row_(b, 0));
    this.schedule();
  }

  private move(from: number, to: number): void {
    const r = this.rows.splice(from, 1)[0];
    this.rows.splice(to, 0, r);
    this.schedule();
  }

  @Builder
  BlockMenu(index: number) {
    Menu() {
      ForEach(TYPES, (t: string) => {
        MenuItem({ content: typeLabel(t) })
          .selected(this.rows[index]?.block.type === t)
          .onClick(() => this.retype(index, t))
      }, (t: string) => t)
      MenuItem({ content: '在下方添加段落' }).onClick(() => this.addAfter(index))
      MenuItem({ content: '删除块' }).onClick(() => this.remove(index))
    }
  }

  @Builder
  TextRow(index: number, b: EssayBlock) {
    Row({ space: 6 }) {
      // The handle: long-press it to drag (List's onMove), tap it for the block menu.
      SymbolGlyph($r('sys.symbol.line_3_horizontal'))
        .fontSize(16)
        .fontColor([$r('sys.color.icon_tertiary')])
        .padding({ top: 10, left: 4, right: 4 })
        .bindMenu(this.BlockMenu(index))
      if (b.type === 'bulleted_list_item') {
        Text('•').fontSize(17).padding({ top: 8 })
      } else if (b.type === 'numbered_list_item') {
        Text(`${index + 1}.`).fontSize(15).padding({ top: 9 })
      }
      TextArea({ text: b.text, placeholder: index === 0 && this.rows.length === 1 ? '开始写…' : '' })
        .fontSize(sizeFor(b.type))
        .fontFamily(familyOf(b.type.startsWith('heading') ? DeskFont.Sans : DeskFont.Serif))
        .fontWeight(b.type.startsWith('heading') ? FontWeight.Medium : FontWeight.Normal)
        .fontStyle(b.type === 'quote' ? FontStyle.Italic : FontStyle.Normal)
        .lineHeight(sizeFor(b.type) * 1.7)
        .backgroundColor(Color.Transparent)
        .padding({ left: b.type === 'quote' ? 12 : 0, top: 6, bottom: 6 })
        .border(b.type === 'quote' ? { width: { left: 3 }, color: $r('sys.color.font_tertiary') } : { width: 0 })
        .layoutWeight(1)
        .onChange((v: string) => this.edit(index, v))
    }
    .alignItems(VerticalAlign.Top)
    .width('100%')
  }

  @Builder
  OtherRow(index: number, b: EssayBlock) {
    Row({ space: 6 }) {
      SymbolGlyph($r('sys.symbol.line_3_horizontal'))
        .fontSize(16)
        .fontColor([$r('sys.color.icon_tertiary')])
        .padding({ top: 10, left: 4, right: 4 })
        .bindMenu(this.BlockMenu(index))
      if (b.kind === BlockKind.Photo) {
        Image(b.url).width('100%').borderRadius(16).objectFit(ImageFit.Contain).layoutWeight(1)
      } else if (b.type === 'divider') {
        Divider().layoutWeight(1).margin({ top: 14 })
      } else {
        Text(b.kind === BlockKind.Paragraph ? b.text : `Notion“${b.type}”块`)
          .fontSize(15)
          .fontColor(quietColor(this.dark, 0.85))
          .padding(12)
          .borderRadius(14)
          .backgroundColor($r('sys.color.comp_background_tertiary'))
          .layoutWeight(1)
      }
    }
    .alignItems(VerticalAlign.Top)
    .width('100%')
  }

  build() {
    Column() {
      Row({ space: 8 }) {
        if (!this.embedded) {
          IconButton({ icon: $r('sys.symbol.arrow_left'), label: '返回', action: () => this.onBack() })
        }
        // Stretched to the bar's own height: '100%' would take the page's and make the bar fill it.
        Blank()
          .alignSelf(ItemAlign.Stretch)
          .onTouch((e: TouchEvent) => WindowChrome.drag(e))
          .gesture(TapGesture({ count: 2 }).onAction(() => WindowChrome.toggleMaximize()))
        if (this.status.length > 0) {
          Text(this.status).fontSize(13).fontColor($r('sys.color.font_secondary')).maxLines(1)
        }
        Button('Markdown', { buttonStyle: ButtonStyleMode.NORMAL, controlSize: ControlSize.SMALL })
          .onClick(() => this.onDeskMode())
        IconButton({ icon: $r('sys.symbol.plus'), label: '添加段落', action: () => this.addAfter(this.rows.length - 1) })
      }
      .width('100%')
      .constraintSize({ minHeight: this.captionTop })
      .padding({ left: 16, right: Math.max(16, this.captionRight) })

      List({ space: 4 }) {
        ListItem() {
          TextInput({ text: this.title, placeholder: '标题' })
            .fontSize(26)
            .fontFamily(familyOf(DeskFont.Sans))
            .fontWeight(FontWeight.Medium)
            .backgroundColor(Color.Transparent)
            .onChange((v: string) => {
              this.title = v;
              if (this.titleTimer >= 0) {
                clearTimeout(this.titleTimer);
              }
              this.titleTimer = setTimeout(() => this.saveTitle(), WRITE_AFTER_MS);
            })
        }
        ForEach(this.rows, (r: Row_, index: number) => {
          ListItem() {
            if (DeskText.editable(r.block) || (r.block.kind === BlockKind.Paragraph && r.block.text.length === 0 && !r.block.fixed)) {
              this.TextRow(index, r.block)
            } else {
              this.OtherRow(index, r.block)
            }
          }
        }, (r: Row_) => `${r.block.id}#${r.version}`)
          .onMove((from: number, to: number) => this.move(from, to))
        ListItem() {
          // Tapping under the last block starts a new one there, as on paper.
          Column().height(160).width('100%').onClick(() => this.addAfter(this.rows.length - 1))
        }
      }
      .width('100%')
      .layoutWeight(1)
      .constraintSize({ maxWidth: 760 })
      .padding({ left: 16, right: 24 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.leaf_paper'))
  }
}
