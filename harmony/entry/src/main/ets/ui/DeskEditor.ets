import { BlockKind, EssayBlock } from '../core/Models';
import { DeskFocus, DeskPart, DeskPartKind, DeskText, Span } from '../core/DeskText';
import { DeskOrigins, DeskStyle, Origin, OriginSpan, STYLE_RULES, StyleHit, StyleRule, styleRuleLabel } from '../core/DeskStyle';
import { DeskLook, Ink, LookSegment } from '../core/DeskLook';
import { LeafApp } from '../data/LeafApp';
import { newId } from '../data/Library';
import {
  DeskFont, familyOf, fontLabel, inkColor, lineHeightOf, PLEX_SANS, PLEX_SANS_SEMIBOLD, quietColor, REFRESH_EVERY_MS,
  REFRESH_REST_MS, sizeOf, WRITE_AFTER_MS,
} from './Look';

/** 专注: off, the sentence being written, or its paragraph; everything else fades. */
export enum FocusMode {
  Off = 'off',
  Sentence = 'sentence',
  Paragraph = 'paragraph',
}

function focusLabel(m: FocusMode): string {
  switch (m) {
    case FocusMode.Sentence:
      return '句';
    case FocusMode.Paragraph:
      return '段';
    default:
      return '全文';
  }
}

const FOCUS_MODES: FocusMode[] = [FocusMode.Off, FocusMode.Sentence, FocusMode.Paragraph];
const FONTS: DeskFont[] = [DeskFont.Serif, DeskFont.Sans, DeskFont.Mono];

/** One text run's result after the pen rested: its blocks now, and 来源 spans per block id. */
export type RunWrite = (runKey: string, blocks: EssayBlock[], spans: Map<string, OriginSpan[]>) => void;

/**
 * One run of text blocks edited as a single Markdown text in a RichEditor. It owns its words while it is
 * on screen: the page never pushes text into it again unless Notion changed the run (then the page
 * rebuilds it under a new key). Port of the run half of Android's DeskEditor.
 */
@Component
struct DeskRun {
  runKey: string = '';
  blocks: EssayBlock[] = [];
  spans: Map<string, OriginSpan[]> = new Map<string, OriginSpan[]>();
  placeholder: string = '';
  @Prop @Watch('restyle') focusMode: FocusMode = FocusMode.Off;
  @Prop @Watch('restyleAll') styleOn: boolean = false;
  @Prop @Watch('restyle') originsOn: boolean = false;
  @Prop @Watch('restyleAll') rulesKey: string = '';
  @Prop @Watch('refont') font: DeskFont = DeskFont.Serif;
  @Prop @Watch('restyle') dark: boolean = false;
  onWrite: RunWrite = () => {};
  /** Every keystroke: the page fades its bar and notes when the pen last moved. */
  onTyping: () => void = () => {};
  /** The caret's line top inside this run (vp), for 打字机. */
  onCaretLine: (runKey: string, top: number) => void = () => {};
  private controller: RichEditorController = new RichEditorController();
  private text: string = '';
  private origins: Origin[] = [];
  private caret: number = -1;
  private hits: StyleHit[] = [];
  private pasting: boolean = false;
  private writeTimer: number = -1;
  private styleTimer: number = -1;

  aboutToDisappear(): void {
    // Words typed in the last 600 ms are written now rather than lost with the component.
    if (this.writeTimer >= 0) {
      clearTimeout(this.writeTimer);
      this.write();
    }
    if (this.styleTimer >= 0) {
      clearTimeout(this.styleTimer);
    }
  }

  private baseStyle(): RichEditorTextStyle {
    const style: RichEditorTextStyle = {
      fontColor: inkColor(Ink.Text, false, this.dark),
      fontSize: sizeOf(this.font),
      fontFamily: familyOf(this.font),
      lineHeight: lineHeightOf(this.font),
      fontWeight: FontWeight.Normal,
    };
    return style;
  }

  private load(): void {
    const r = DeskText.render(this.blocks);
    this.text = r.text;
    this.origins = DeskOrigins.lay(r.text.length, r.starts,
      this.blocks.map((b: EssayBlock) => this.spans.get(b.id) ?? []));
    if (r.text.length > 0) {
      this.controller.addTextSpan(r.text, { style: this.baseStyle() });
    }
    this.checkStyle();
    this.restyle();
  }

  private readText(): string {
    const parts = this.controller.getSpans({ start: -1, end: -1 });
    let out = '';
    for (const s of parts) {
      const t = s as RichEditorTextSpanResult;
      if (t.value !== undefined) {
        out += t.value;
      }
    }
    return out;
  }

  private changed(): void {
    const next = this.readText();
    if (next === this.text) {
      return;
    }
    // A paste (shortcut or menu) reads as pasted; typing and the IME committing a word read as typed.
    this.origins = DeskOrigins.splice(this.origins, this.text, next, this.pasting ? Origin.Pasted : Origin.Typed);
    this.pasting = false;
    this.text = next;
    this.onTyping();
    if (this.writeTimer >= 0) {
      clearTimeout(this.writeTimer);
    }
    this.writeTimer = setTimeout(() => {
      this.writeTimer = -1;
      this.write();
    }, WRITE_AFTER_MS);
    if (this.styleOn) {
      // Style marks are worked out a beat after typing, not on every key.
      if (this.styleTimer >= 0) {
        clearTimeout(this.styleTimer);
      }
      this.styleTimer = setTimeout(() => {
        this.styleTimer = -1;
        this.checkStyle();
        this.restyle();
      }, 300);
    }
    this.restyle();
    this.reportCaret();
  }

  /** Markdown → blocks (ids kept by LCS), and 来源 spans per block, handed to the page to store. */
  private write(): void {
    const update = DeskText.update(this.blocks, this.text, newId);
    const lines = DeskText.parse(this.text);
    const spans = new Map<string, OriginSpan[]>();
    update.blocks.forEach((b: EssayBlock, j: number) => {
      if (j < lines.length) {
        spans.set(b.id, DeskOrigins.spans(this.origins, lines[j].textStart, lines[j].text.length));
      }
    });
    this.blocks = update.blocks;
    this.onWrite(this.runKey, update.blocks, spans);
  }

  private checkStyle(): void {
    if (!this.styleOn) {
      this.hits = [];
      return;
    }
    const rules = new Set<StyleRule>(STYLE_RULES.filter((r: StyleRule) => this.rulesKey.indexOf(r) >= 0));
    this.hits = DeskStyle.check(this.text, rules);
  }

  restyleAll(): void {
    this.checkStyle();
    this.restyle();
  }

  refont(): void {
    if (this.text.length > 0) {
      this.controller.updateSpanStyle({ start: 0, end: this.text.length, textStyle: this.baseStyle() });
    }
    this.restyle();
  }

  private focusSpan(): Span | null {
    if (this.focusMode === FocusMode.Off || this.caret < 0) {
      return null;
    }
    return this.focusMode === FocusMode.Sentence ? DeskFocus.sentence(this.text, this.caret) : DeskFocus.paragraph(this.text, this.caret);
  }

  /** Colours, weights and strikes per segment; never the characters, so the IME sees the text as typed. */
  restyle(): void {
    if (this.text.length === 0) {
      return;
    }
    const segs = DeskLook.segments(this.text, this.originsOn ? this.origins : null, this.styleOn ? this.hits : [], this.focusSpan());
    for (const s of segs) {
      this.controller.updateSpanStyle({ start: s.start, end: s.end, textStyle: this.lookStyle(s) });
    }
  }

  private lookStyle(s: LookSegment): RichEditorTextStyle {
    const struck = s.ink === Ink.Struck;
    const style: RichEditorTextStyle = {
      fontColor: inkColor(s.ink, s.faded, this.dark),
      fontWeight: s.bold ? FontWeight.Medium : FontWeight.Normal,
      fontFamily: s.bold && this.font !== DeskFont.Mono ? `${PLEX_SANS_SEMIBOLD}, ${familyOf(this.font)}` : familyOf(this.font),
      decoration: {
        // A long sentence is underlined faintly (Android shades it; RichEditor spans have no background on API 12).
        type: struck ? TextDecorationType.LineThrough : (s.shaded ? TextDecorationType.Underline : TextDecorationType.None),
        color: quietColor(this.dark, struck ? 0.6 : 0.3),
      },
    };
    return style;
  }

  /** 打字机: tells the page where the caret's line sits inside this run. */
  private reportCaret(): void {
    if (this.caret < 0) {
      return;
    }
    const layout = this.controller.getLayoutManager();
    const count = layout.getLineCount();
    for (let i = 0; i < count; i++) {
      const m = layout.getLineMetrics(i);
      if (this.caret >= m.startIndex && (this.caret < m.endIndex || i === count - 1)) {
        this.onCaretLine(this.runKey, this.getUIContext().px2vp(m.topHeight + m.height / 2));
        return;
      }
    }
  }

  build() {
    RichEditor({ controller: this.controller })
      .placeholder(this.placeholder, { fontColor: quietColor(this.dark, 0.5), font: { size: sizeOf(this.font), family: familyOf(this.font) } })
      .onReady(() => this.load())
      .onDidChange(() => this.changed())
      .onPaste(() => {
        this.pasting = true;
      })
      .onSelectionChange((range: RichEditorRange) => {
        this.caret = range.start ?? -1;
        this.restyle();
        this.reportCaret();
      })
      .onEditingChange((editing: boolean) => {
        if (!editing) {
          // Nothing fades while the cursor is elsewhere.
          this.caret = -1;
          this.restyle();
        }
      })
      .enableDataDetector(false)
      .copyOptions(CopyOptions.LocalDevice)
      .caretColor($r('app.color.primary'))
      .backgroundColor(Color.Transparent)
      .padding(0)
      .width('100%')
  }
}

/** A block the 写作台 shows but does not edit as text. */
@Component
struct ShownBlock {
  block: EssayBlock = EssayBlock.paragraph('', '');
  @Prop dark: boolean = false;

  build() {
    Column({ space: 6 }) {
      if (this.block.kind === BlockKind.Photo) {
        Image(this.block.url)
          .width('100%')
          .borderRadius(16)
          .objectFit(ImageFit.Contain)
          .alt($r('app.media.app_icon'))
        if (this.block.text.length > 0) {
          Text(this.block.text).fontSize(14).fontColor(quietColor(this.dark, 0.9))
        }
      } else if (this.block.kind === BlockKind.Attachment) {
        Row({ space: 10 }) {
          Text('📎').fontSize(18)
          Text(this.block.text.length > 0 ? this.block.text : this.block.type).fontSize(15).layoutWeight(1).maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .padding(14)
        .width('100%')
        .borderRadius(16)
        .backgroundColor($r('app.color.surface_container'))
      } else if (this.block.type === 'divider') {
        Divider().color(quietColor(this.dark, 0.3)).margin({ top: 12, bottom: 12 })
      } else if (this.block.kind === BlockKind.Paragraph) {
        Text(this.block.text).fontSize(17).lineHeight(30).fontColor(quietColor(this.dark, 1))
        Text('带格式的段落，在平板模式或 Notion 里改').fontSize(12).fontColor(quietColor(this.dark, 0.7))
      } else {
        Text(`Notion 里的「${this.block.type}」块，写作台先不改它`).fontSize(13).fontColor(quietColor(this.dark, 0.8))
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .margin({ top: 8, bottom: 8 })
  }
}

/** A run plus the generation it is shown under; a new generation rebuilds the run's editor. */
class ShownPart {
  readonly part: DeskPart;
  readonly generation: number;

  constructor(part: DeskPart, generation: number) {
    this.part = part;
    this.generation = generation;
  }

  get key(): string {
    return `${this.part.key}#${this.generation}`;
  }
}

/**
 * The 写作台: Leaf's editor in 电脑模式 (a hardware keyboard attached). The essay is one Markdown text in a
 * single centred column, with photos, files and other blocks shown between runs of text. 专注 fades all but
 * the sentence or paragraph being written, 打字机 keeps the line being written near the middle, 文风
 * strikes through words to look at twice, 来源 colours what was pasted in.
 *
 * Local first: keystrokes stay in the editor, are written to the library once the pen rests (600 ms), and
 * only then queue for Notion; while the page is open the same essay is fetched again every 30 s once the
 * pen has rested 5 s, and nothing else is. Port of Android's DeskEditor (Leaf PR #15).
 */
@Component
export struct DeskEditor {
  essayId: string = '';
  onBack: () => void = () => {};
  onTabletMode: () => void = () => {};
  @StorageProp('leaf.dark') dark: boolean = false;
  @State shown: ShownPart[] = [];
  @State title: string = '';
  @State focusMode: FocusMode = FocusMode.Off;
  @State typewriter: boolean = false;
  @State font: DeskFont = DeskFont.Serif;
  @State styleOn: boolean = false;
  @State originsOn: boolean = false;
  @State rulesKey: string = STYLE_RULES.join(',');
  @State barShown: boolean = true;
  @State status: string = '';
  @State menuOpen: boolean = false;
  private scroller: Scroller = new Scroller();
  private body: EssayBlock[] = [];
  private runs: Map<string, EssayBlock[]> = new Map<string, EssayBlock[]>();
  private runTops: Map<string, number> = new Map<string, number>();
  private spans: Map<string, OriginSpan[]> = new Map<string, OriginSpan[]>();
  private generations: Map<string, number> = new Map<string, number>();
  private viewport: number = 0;
  private lastKey: number = 0;
  private refreshTimer: number = -1;
  private titleTimer: number = -1;
  private unsubscribe: () => void = () => {};

  aboutToAppear(): void {
    const s = LeafApp.lib.settings;
    this.focusMode = s.getString('desk.focus', FocusMode.Off) as FocusMode;
    this.font = s.getString('desk.font', DeskFont.Serif) as DeskFont;
    this.typewriter = s.getBool('desk.typewriter', false);
    this.styleOn = s.getBool('desk.style', false);
    this.originsOn = s.getBool('desk.origins', false);
    this.rulesKey = s.getString('desk.rules', STYLE_RULES.join(','));
    this.open();
    this.unsubscribe = LeafApp.sync.subscribe(() => {
      this.status = LeafApp.sync.message;
    });
    this.refreshTimer = setInterval(() => this.refresh(), REFRESH_EVERY_MS);
  }

  aboutToDisappear(): void {
    clearInterval(this.refreshTimer);
    this.unsubscribe();
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
    this.spans = DeskOrigins.decode(await LeafApp.lib.origins(this.essayId));
    // The words show at once from the library; Notion is asked afterwards.
    await this.adopt(await LeafApp.lib.body(this.essayId));
    if (await LeafApp.sync.loadBody(this.essayId)) {
      await this.adopt(await LeafApp.lib.body(this.essayId));
    }
  }

  /** Lays the page out from [blocks]: a run whose text is unchanged keeps its editor (and caret). */
  private async adopt(blocks: EssayBlock[]): Promise<void> {
    this.body = blocks;
    const parts = DeskText.parts(blocks);
    const next: ShownPart[] = [];
    for (const p of parts) {
      if (p.kind === DeskPartKind.Text) {
        const before = this.runs.get(p.key);
        // Same words and the same blocks under them: the editor on screen already shows exactly this.
        const same = before !== undefined && before.length === p.blocks.length &&
          before.every((b: EssayBlock, i: number) => b.id === p.blocks[i].id && !b.contentDiffers(p.blocks[i]));
        const gen = (this.generations.get(p.key) ?? 0) + (same || before === undefined ? 0 : 1);
        this.generations.set(p.key, gen);
        this.runs.set(p.key, p.blocks);
        next.push(new ShownPart(p, gen));
      } else {
        next.push(new ShownPart(p, 0));
      }
    }
    this.shown = next;
  }

  /** A run wrote: the page's body is the runs and shown blocks in order again, saved as one. */
  private onRunWrite(runKey: string, blocks: EssayBlock[], spans: Map<string, OriginSpan[]>): void {
    this.runs.set(runKey, blocks);
    spans.forEach((v: OriginSpan[], k: string) => this.spans.set(k, v));
    const after: EssayBlock[] = [];
    for (const sp of this.shown) {
      if (sp.part.kind === DeskPartKind.Text) {
        for (const b of this.runs.get(sp.part.key) ?? []) {
          after.push(b);
        }
      } else if (sp.part.block !== null) {
        after.push(sp.part.block);
      }
    }
    this.body = after;
    // Marks follow the blocks; ids no longer on the page are dropped from the file.
    const live = new Set<string>(after.map((b: EssayBlock) => b.id));
    const keep = new Map<string, OriginSpan[]>();
    this.spans.forEach((v: OriginSpan[], k: string) => {
      if (live.has(k)) {
        keep.set(k, v);
      }
    });
    this.spans = keep;
    LeafApp.lib.saveBody(this.essayId, after);
    LeafApp.lib.saveOrigins(this.essayId, DeskOrigins.encode(keep));
  }

  /** 资源集中在当前这篇: while open and resting, fetch this one page again now and then. */
  private async refresh(): Promise<void> {
    if (Date.now() - this.lastKey < REFRESH_REST_MS) {
      return;
    }
    if (await LeafApp.sync.refreshBody(this.essayId)) {
      await this.adopt(await LeafApp.lib.body(this.essayId));
    }
  }

  private typing(): void {
    this.lastKey = Date.now();
    if (this.barShown) {
      animateTo({ duration: 420 }, () => {
        this.barShown = false;
      });
    }
  }

  private caretLine(runKey: string, top: number): void {
    if (!this.typewriter || this.viewport === 0) {
      return;
    }
    const runTop = this.runTops.get(runKey) ?? 0;
    // The line being written rests a little above the middle of the window.
    const target = Math.max(0, runTop + top - this.viewport * 0.45);
    const now = this.scroller.currentOffset().yOffset;
    if (Math.abs(target - now) > 2) {
      this.scroller.scrollTo({ xOffset: 0, yOffset: target, animation: { duration: 140, curve: Curve.EaseOut } });
    }
  }

  private saveTitle(): void {
    this.titleTimer = -1;
    const e = LeafApp.lib.essay(this.essayId);
    if (e !== undefined) {
      LeafApp.lib.updateMeta(this.essayId, this.title, e.color, e.pinned);
    }
  }

  private setFocus(m: FocusMode): void {
    this.focusMode = m;
    LeafApp.lib.settings.put('desk.focus', m);
  }

  private setFont(f: DeskFont): void {
    this.font = f;
    LeafApp.lib.settings.put('desk.font', f);
  }

  private toggle(key: string, value: boolean): boolean {
    LeafApp.lib.settings.put(key, value);
    return value;
  }

  private toggleRule(r: StyleRule): void {
    const on = new Set<string>(this.rulesKey.split(',').filter((x: string) => x.length > 0));
    if (on.has(r)) {
      on.delete(r);
    } else {
      on.add(r);
    }
    this.rulesKey = Array.from(on).join(',');
    LeafApp.lib.settings.put('desk.rules', this.rulesKey);
  }

  @Builder
  Segment(label: string, on: boolean, action: () => void) {
    Text(label)
      .fontSize(14)
      .fontFamily(PLEX_SANS)
      .fontColor(on ? $r('app.color.on_surface') : $r('app.color.on_surface_variant'))
      .padding({ left: 11, right: 11, top: 5, bottom: 5 })
      .borderRadius(999)
      .backgroundColor(on ? $r('app.color.surface') : Color.Transparent)
      .onClick(action)
  }

  @Builder
  Toggle(label: string, on: boolean, action: () => void) {
    Text(label)
      .fontSize(14)
      .fontColor(on ? $r('app.color.on_primary_container') : $r('app.color.on_surface_variant'))
      .padding({ left: 12, right: 12, top: 6, bottom: 6 })
      .borderRadius(999)
      .backgroundColor(on ? $r('app.color.primary_container') : Color.Transparent)
      .animation({ duration: 160 })
      .onClick(action)
  }

  @Builder
  MoreMenu() {
    Menu() {
      ForEach(STYLE_RULES, (r: StyleRule) => {
        MenuItem({ content: styleRuleLabel(r) })
          .selected(this.rulesKey.indexOf(r) >= 0)
          .selectIcon(true)
          .onChange(() => this.toggleRule(r))
      }, (r: StyleRule) => r)
      MenuItem({ content: '用平板模式编辑这一篇' })
        .onClick(() => this.onTabletMode())
    }
  }

  @Builder
  Bar() {
    Row({ space: 8 }) {
      Text('←')
        .fontSize(20)
        .padding(8)
        .onClick(() => this.onBack())
        .keyboardShortcut(FunctionKey.ESC, [])
      Row({ space: 2 }) {
        ForEach(FOCUS_MODES, (m: FocusMode) => {
          this.Segment(focusLabel(m), this.focusMode === m, () => this.setFocus(m))
        }, (m: FocusMode) => m)
      }
      .padding(3)
      .borderRadius(999)
      .backgroundColor($r('app.color.surface_container'))

      this.Toggle('打字机', this.typewriter, () => {
        this.typewriter = this.toggle('desk.typewriter', !this.typewriter);
      })
      Row({ space: 2 }) {
        ForEach(FONTS, (f: DeskFont) => {
          this.Segment(fontLabel(f), this.font === f, () => this.setFont(f))
        }, (f: DeskFont) => f)
      }
      .padding(3)
      .borderRadius(999)
      .backgroundColor($r('app.color.surface_container'))

      this.Toggle('文风', this.styleOn, () => {
        this.styleOn = this.toggle('desk.style', !this.styleOn);
      })
      this.Toggle('来源', this.originsOn, () => {
        this.originsOn = this.toggle('desk.origins', !this.originsOn);
      })
      Blank()
      if (this.status.length > 0) {
        Text(this.status).fontSize(13).fontColor($r('app.color.on_surface_variant')).maxLines(1)
      }
      Text('⋯')
        .fontSize(20)
        .padding(8)
        .bindMenu(this.MoreMenu())
    }
    .width('100%')
    .padding({ left: 10, right: 10, top: 8, bottom: 8 })
    .backgroundColor($r('app.color.surface'))
    .opacity(this.barShown ? 1 : 0)
  }

  @Builder
  Legend() {
    Row({ space: 14 }) {
      if (this.originsOn) {
        Text('● 手打').fontSize(12).fontColor($r('app.color.on_surface'))
        Text('● 粘贴').fontSize(12).fontColor($r('app.color.pasted'))
      }
      if (this.styleOn) {
        Text('划掉的词：再看一眼，不会替你改').fontSize(12).fontColor($r('app.color.on_surface_variant'))
      }
    }
    .padding({ left: 14, right: 14, top: 6, bottom: 6 })
    .borderRadius(999)
    .backgroundColor($r('app.color.surface_container'))
  }

  build() {
    Stack({ alignContent: Alignment.Top }) {
      Scroll(this.scroller) {
        Column() {
          TextInput({ text: this.title, placeholder: '标题' })
            .fontSize(sizeOf(this.font) * 1.35)
            .fontFamily(familyOf(this.font))
            .fontWeight(FontWeight.Medium)
            .backgroundColor(Color.Transparent)
            .padding(0)
            .margin({ bottom: 24 })
            .onChange((v: string) => {
              this.title = v;
              this.typing();
              if (this.titleTimer >= 0) {
                clearTimeout(this.titleTimer);
              }
              this.titleTimer = setTimeout(() => this.saveTitle(), WRITE_AFTER_MS);
            })
          ForEach(this.shown, (sp: ShownPart) => {
            if (sp.part.kind === DeskPartKind.Text) {
              DeskRun({
                runKey: sp.part.key,
                blocks: sp.part.blocks,
                spans: this.spans,
                placeholder: this.shown.length === 1 ? '开始写…' : '',
                focusMode: this.focusMode,
                styleOn: this.styleOn,
                originsOn: this.originsOn,
                rulesKey: this.rulesKey,
                font: this.font,
                dark: this.dark,
                onWrite: (k: string, b: EssayBlock[], s: Map<string, OriginSpan[]>) => this.onRunWrite(k, b, s),
                onTyping: () => this.typing(),
                onCaretLine: (k: string, top: number) => this.caretLine(k, top),
              })
                .onAreaChange((_: Area, area: Area) => {
                  this.runTops.set(sp.part.key, Number(area.position.y ?? 0));
                })
            } else if (sp.part.block !== null) {
              ShownBlock({ block: sp.part.block, dark: this.dark })
            }
          }, (sp: ShownPart) => sp.key)
          // Room under the last line so 打字机 can bring it to the middle.
          Blank().height(this.typewriter ? '50%' : 120)
        }
        .width('100%')
        .constraintSize({ maxWidth: 720 })
        .padding({ left: 32, right: 32, top: 88 })
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%')
      .height('100%')
      .align(Alignment.Top)
      .scrollBar(BarState.Auto)
      .onAreaChange((_: Area, area: Area) => {
        this.viewport = Number(area.height);
      })

      this.Bar()
      if (this.originsOn || this.styleOn) {
        Column() {
          Blank()
          this.Legend()
        }
        .width('100%')
        .height('100%')
        .padding({ bottom: 16 })
        .hitTestBehavior(HitTestMode.Transparent)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.surface'))
    // Any pointer movement brings the bar back; typing fades it.
    .onHover(() => this.showBar())
    .onMouse(() => this.showBar())
  }

  private showBar(): void {
    if (!this.barShown) {
      animateTo({ duration: 160 }, () => {
        this.barShown = true;
      });
    }
  }
}
