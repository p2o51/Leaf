import { BlockKind, EssayBlock } from '../core/Models';
import { DeskFocus, DeskPart, DeskPartKind, DeskText, Span } from '../core/DeskText';
import { DeskOrigins, DeskStyle, Origin, OriginSpan, STYLE_RULES, StyleHit, StyleRule, styleRuleLabel } from '../core/DeskStyle';
import { DeskLook, Ink, LookSegment } from '../core/DeskLook';
import { LeafApp } from '../data/LeafApp';
import { newId } from '../data/Library';
import {
  DeskFont, familyOf, fontLabel, inkColor, lineHeightOf, PLEX_SANS, PLEX_SANS_SEMIBOLD, quietColor, REFRESH_EVERY_MS,
  headingFamily, REFRESH_REST_MS, sizeOf, topBlur, TopFade, WRITE_AFTER_MS,
} from './Look';
import { CAPTION_RIGHT_KEY, CAPTION_TOP_KEY, WindowChrome } from './WindowChrome';
import { VoiceError, VoiceInput, VoiceState } from '../data/Voice';
import { common } from '@kit.AbilityKit';

/** 专注: off, the sentence being written, or its paragraph; everything else fades. */
export enum FocusMode {
  Off = 'off',
  Sentence = 'sentence',
  Paragraph = 'paragraph',
}

function focusLabel(m: FocusMode): string {
  switch (m) {
    case FocusMode.Sentence:
      return '句子';
    case FocusMode.Paragraph:
      return '段落';
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
  /** The cursor went into this run: dictated words go here. */
  onActive: (runKey: string) => void = () => {};
  /** `seq\u0000words` from 语音输入, for this run only: inserted at the caret (or at the end). */
  @Prop @Watch('dictate') dictation: string = '';
  private controller: RichEditorController = new RichEditorController();
  private text: string = '';
  private origins: Origin[] = [];
  private caret: number = -1;
  private hits: StyleHit[] = [];
  private pasting: boolean = false;
  private speaking: boolean = false;
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
    const origin = this.pasting ? Origin.Pasted : (this.speaking ? Origin.Spoken : Origin.Typed);
    this.origins = DeskOrigins.splice(this.origins, this.text, next, origin);
    this.pasting = false;
    this.speaking = false;
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

  dictate(): void {
    const cut = this.dictation.indexOf('\u0000');
    const words = cut >= 0 ? this.dictation.substring(cut + 1) : '';
    if (words.length === 0) {
      return;
    }
    const at = this.caret >= 0 ? this.caret : this.text.length;
    this.speaking = true;
    this.controller.addTextSpan(words, { offset: at, style: this.baseStyle() });
    this.controller.setCaretOffset(at + words.length);
    this.changed();
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
      fontFamily: s.bold ? headingFamily(this.font) : familyOf(this.font),
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
        if (editing) {
          this.onActive(this.runKey);
        }
        if (!editing) {
          // Nothing fades while the cursor is elsewhere.
          this.caret = -1;
          this.restyle();
        }
      })
      .enableDataDetector(false)
      .copyOptions(CopyOptions.LocalDevice)
      .caretColor($r('app.color.leaf_ink'))
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
          SymbolGlyph($r('sys.symbol.paperclip')).fontSize(18).fontColor([$r('sys.color.icon_secondary')])
          Text(this.block.text.length > 0 ? this.block.text : this.block.type).fontSize(15).layoutWeight(1).maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .padding(14)
        .width('100%')
        .borderRadius(16)
        .backgroundColor($r('sys.color.comp_background_tertiary'))
      } else if (this.block.type === 'divider') {
        Divider().color(quietColor(this.dark, 0.3)).margin({ top: 12, bottom: 12 })
      } else if (this.block.kind === BlockKind.Paragraph) {
        Text(this.block.text).fontSize(17).lineHeight(30).fontColor(quietColor(this.dark, 1))
        Text('此段落带格式。请在平板模式或 Notion 中编辑。').fontSize(12).fontColor(quietColor(this.dark, 0.7))
      } else {
        Text(`不支持“${this.block.type}”块。请在 Notion 中编辑。`).fontSize(13).fontColor(quietColor(this.dark, 0.8))
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .margin({ top: 8, bottom: 8 })
  }
}

/** A toolbar chip. Passed to a @Builder as one object literal so it is passed by reference. */
interface ChipParams {
  label: string;
  on: boolean;
  action: () => void;
}

/** A toolbar switch drawn as an icon, as on the Mac's toolbar; [tip] shows on hover. */
interface ToggleParams extends ChipParams {
  symbol: Resource;
  tip: string;
}

// What the bar's controls take (vp), left of the window buttons, besides the face pop-up: everything; and with
// the three switches folded into 更多. Measured on the MateBook.
const BAR_FULL: number = 344;
const BAR_NO_SWITCHES: number = 248;
const BACK_ROOM: number = 48;
/** How far below the page's top the words start to blur and fade under the bar (the bar is 56vp). */
const TOP_EDGE: number = 72;

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
  /** Shown beside the essay list rather than as its own page: no back button. */
  embedded: boolean = false;
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
  @State barWidth: number = 0;
  @State pageHeight: number = 0;
  @State voiceState: VoiceState = VoiceState.Idle;
  @State dictation: string = '';
  private voice: VoiceInput = new VoiceInput();
  private dictated: number = 0;
  private activeRun: string = '';
  private unsubscribeVoice: () => void = () => {};
  @State status: string = '';
  @State menuOpen: boolean = false;
  @StorageProp(CAPTION_TOP_KEY) captionTop: number = 0;
  @StorageProp(CAPTION_RIGHT_KEY) captionRight: number = 0;
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
    this.voice.onText = (words: string) => {
      this.dictated++;
      this.dictation = `${this.dictated}\u0000${words}`;
    };
    this.voice.onError = (e: VoiceError) => {
      this.getUIContext().getPromptAction().showToast({ message: e.message, duration: 3000 });
    };
    this.unsubscribeVoice = this.voice.subscribe((s: VoiceState) => {
      this.voiceState = s;
    });
  }

  aboutToDisappear(): void {
    clearInterval(this.refreshTimer);
    this.unsubscribe();
    this.unsubscribeVoice();
    this.voice.cancel();
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

  /** Chinese characters plus Latin words in the body, as a writer counts 字. */
  private wordCount(): number {
    let n = 0;
    for (const b of this.body) {
      const cjk = b.text.match(/[\u3400-\u9fff\uf900-\ufaff]/g);
      const latin = b.text.match(/[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*/g);
      n += (cjk === null ? 0 : cjk.length) + (latin === null ? 0 : latin.length);
    }
    return n;
  }

  private countLabel(): string {
    const digits = String(this.wordCount()).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return `${digits} 字`;
  }

  /** About 400 Chinese characters a minute. */
  private readingLabel(): string {
    const n = this.wordCount();
    return n === 0 ? '' : `约 ${Math.max(1, Math.round(n / 400))} 分钟读完`;
  }

  private setTypewriter(on: boolean): void {
    this.typewriter = this.toggle('desk.typewriter', on);
  }

  private setStyleOn(on: boolean): void {
    this.styleOn = this.toggle('desk.style', on);
  }

  private setOriginsOn(on: boolean): void {
    this.originsOn = this.toggle('desk.origins', on);
  }

  /** The bar's right padding: the window buttons' room (which keeps its own air), or a plain margin. */
  private endInset(): number {
    return Math.max(16, this.captionRight);
  }

  /** Where dictated words go: the run the cursor was last in, else the last run (where new writing goes). */
  private dictationRun(): string {
    let last = '';
    for (const sp of this.shown) {
      if (sp.part.kind === DeskPartKind.Text) {
        if (sp.part.key === this.activeRun) {
          return this.activeRun;
        }
        last = sp.part.key;
      }
    }
    return last;
  }

  private toggleVoice(): void {
    this.voice.toggle(this.getUIContext().getHostContext() as common.UIAbilityContext);
  }

  /**
   * How much of the bar goes into 更多 when the desk is narrow (beside the list, the window buttons on its
   * right), as the Mac folds its toolbar: 0 nothing, 1 the three switches, 2 the face as well.
   */
  private fold(): number {
    if (this.barWidth <= 0) {
      return 0;
    }
    const room = this.barWidth - 16 - this.endInset() - (this.embedded ? 0 : BACK_ROOM);
    // The face pop-up is as wide as its name: 无衬线 takes a character more than 衬线 and 等宽.
    const face = this.font === DeskFont.Sans ? 79 : 65;
    return room >= BAR_FULL + face ? 0 : (room >= BAR_NO_SWITCHES + face ? 1 : 2);
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

  // One object-literal parameter, so ArkUI passes it by reference and the chip redraws when [on] changes;
  // separate parameters are copied once and the chip would keep its first look.
  //
  // Chips are Buttons, not Texts with onClick: on a 2-in-1 the bar is the window's caption row, and there the
  // system hands clicks only to buttons; anything else is taken as a press on the title bar.
  //
  // A segment is drawn as HarmonyOS's capsule SegmentButton is: a grey track, the chosen one a raised white pill.
  @Builder
  Segment(p: ChipParams) {
    Button({ type: ButtonType.Capsule, stateEffect: true }) {
      Text(p.label)
        .fontSize(14)
        .fontWeight(p.on ? FontWeight.Medium : FontWeight.Regular)
        .fontColor(p.on ? $r('sys.color.font_primary') : $r('sys.color.font_secondary'))
    }
    .height(28)
    .padding({ left: 10, right: 10 })
    .backgroundColor(p.on ? $r('sys.color.comp_background_primary') : Color.Transparent)
    .shadow(p.on ? ShadowStyle.OUTER_DEFAULT_XS : { radius: 0 })
    .onClick(() => p.action())
  }

  @Builder
  ToggleFill(p: ChipParams) {
    Row()
      .width('100%')
      .height('100%')
      .borderRadius(999)
      .backgroundColor($r('app.color.leaf_ink'))
      .opacity(p.on ? 0.1 : 0)
  }

  // An icon, as on the Mac's toolbar; on: Leaf's indigo ink and a 10% fill (the caret's colour too).
  @Builder
  Toggle(p: ToggleParams) {
    Button({ type: ButtonType.Circle, stateEffect: true }) {
      SymbolGlyph(p.symbol)
        .fontSize(18)
        .fontColor([p.on ? $r('app.color.leaf_ink') : $r('sys.color.icon_primary')])
    }
    .width(32)
    .height(32)
    .backgroundColor(Color.Transparent)
    // A background builder is sized by the button it sits under (a Stack sibling would take the bar's size).
    .background(this.ToggleFill(p))
    .accessibilityText(p.label)
    .accessibilityChecked(p.on)
    .bindTips(p.tip)
    .onClick(() => p.action())
  }

  @Builder
  FontMenu() {
    Menu() {
      ForEach(FONTS, (f: DeskFont) => {
        MenuItem({ content: fontLabel(f) })
          .selected(this.font === f)
          .selectIcon(true)
          .onClick(() => this.setFont(f))
      }, (f: DeskFont) => f)
    }
  }

  @Builder
  MoreMenu() {
    Menu() {
      // The count lives here rather than on the page, as the plan for Leaf's editor has it.
      MenuItem({ content: this.countLabel(), labelInfo: this.readingLabel() })
        .enabled(false)
      // What the bar had no room for comes first.
      if (this.fold() >= 1) {
        MenuItemGroup() {
          MenuItem({ content: '打字机' })
            .selected(this.typewriter)
            .selectIcon(true)
            .onClick(() => this.setTypewriter(!this.typewriter))
          MenuItem({ content: '风格检查' })
            .selected(this.styleOn)
            .selectIcon(true)
            .onClick(() => this.setStyleOn(!this.styleOn))
          MenuItem({ content: '作者' })
            .selected(this.originsOn)
            .selectIcon(true)
            .onClick(() => this.setOriginsOn(!this.originsOn))
        }
      }
      if (this.fold() >= 2) {
        MenuItemGroup({ header: '字体' }) {
          ForEach(FONTS, (f: DeskFont) => {
            MenuItem({ content: fontLabel(f) })
              .selected(this.font === f)
              .selectIcon(true)
              .onClick(() => this.setFont(f))
          }, (f: DeskFont) => f)
        }
      }
      MenuItemGroup({ header: '风格检查' }) {
        ForEach(STYLE_RULES, (r: StyleRule) => {
          MenuItem({ content: styleRuleLabel(r) })
            .selected(this.rulesKey.indexOf(r) >= 0)
            .selectIcon(true)
            .onChange(() => this.toggleRule(r))
        }, (r: StyleRule) => r)
      }
      MenuItem({ content: '在平板模式中编辑' })
        .onClick(() => this.onTabletMode())
    }
  }

  // 语音输入: tap to start, tap again to stop; the words land at the cursor once Scribe answers.
  @Builder
  Mic() {
    Button({ type: ButtonType.Circle, stateEffect: true }) {
      if (this.voiceState === VoiceState.Transcribing) {
        LoadingProgress().width(20).height(20).color($r('app.color.leaf_ink'))
      } else {
        if (this.voiceState === VoiceState.Recording) {
          SymbolGlyph($r('sys.symbol.mic_fill')).fontSize(18).fontColor([$r('sys.color.warning')])
        } else {
          SymbolGlyph($r('sys.symbol.mic')).fontSize(18).fontColor([$r('sys.color.icon_primary')])
        }
      }
    }
    .width(32)
    .height(32)
    .backgroundColor(Color.Transparent)
    .accessibilityText(this.voiceState === VoiceState.Recording ? '停止语音输入' : '语音输入')
    .bindTips('语音输入')
    .onClick(() => this.toggleVoice())
  }

  @Builder
  Bar() {
    Row({ space: 8 }) {
      // Beside the list (the Mac layout) the desk has nowhere to go back to.
      if (!this.embedded) {
        Button({ type: ButtonType.Circle }) {
          SymbolGlyph($r('sys.symbol.arrow_left')).fontSize(20).fontColor([$r('sys.color.icon_primary')])
        }
        .width(40)
        .height(40)
        .backgroundColor($r('sys.color.comp_background_tertiary'))
        .accessibilityText('返回')
        .onClick(() => this.onBack())
        .keyboardShortcut(FunctionKey.ESC, [])
      }
      Row({ space: 2 }) {
        ForEach(FOCUS_MODES, (m: FocusMode) => {
          this.Segment({ label: focusLabel(m), on: this.focusMode === m, action: () => this.setFocus(m) })
        }, (m: FocusMode) => m)
      }
      .padding(3)
      .borderRadius(999)
      .backgroundColor($r('sys.color.comp_background_tertiary'))

      // One pop-up for the face, as the Mac toolbar has it: the face's name and a chevron.
      if (this.fold() < 2) {
        Button({ type: ButtonType.Capsule, stateEffect: true }) {
          Row({ space: 4 }) {
            Text(fontLabel(this.font)).fontSize(14).fontColor($r('sys.color.font_primary'))
            SymbolGlyph($r('sys.symbol.chevron_down')).fontSize(12).fontColor([$r('sys.color.icon_secondary')])
          }
        }
        .height(32)
        .padding({ left: 12, right: 10 })
        .backgroundColor($r('sys.color.comp_background_tertiary'))
        .accessibilityText(`字体：${fontLabel(this.font)}`)
        .bindMenu(this.FontMenu())
      }
      // The switches sit together as one cluster, then the microphone, which never folds away.
      Row() {
        if (this.fold() === 0) {
          this.Toggle({
            label: '打字机', symbol: $r('sys.symbol.text_aligncenter'), tip: '打字机',
            on: this.typewriter, action: () => this.setTypewriter(!this.typewriter)
          })
          this.Toggle({
            label: '风格检查', symbol: $r('sys.symbol.doc_text_badge_checkmark'), tip: '风格检查',
            on: this.styleOn, action: () => this.setStyleOn(!this.styleOn)
          })
          this.Toggle({
            label: '作者', symbol: $r('sys.symbol.text_clipboard'), tip: '作者',
            on: this.originsOn, action: () => this.setOriginsOn(!this.originsOn)
          })
        }
        this.Mic()
      }
      // Stretched to the bar's own height: '100%' would take the page's and make the bar fill it.
      Blank()
        .alignSelf(ItemAlign.Stretch)
        .onTouch((e: TouchEvent) => WindowChrome.drag(e))
        .gesture(TapGesture({ count: 2 }).onAction(() => WindowChrome.toggleMaximize()))
      if (this.status.length > 0) {
        Text(this.status)
          .fontSize(13)
          .fontColor($r('sys.color.font_secondary'))
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .flexShrink(1)
      }
      Button({ type: ButtonType.Circle }) {
        SymbolGlyph($r('sys.symbol.dot_grid_2x2')).fontSize(20).fontColor([$r('sys.color.icon_primary')])
      }
      .width(40)
      .height(40)
      .backgroundColor($r('sys.color.comp_background_tertiary'))
      .accessibilityText('更多')
      .bindTips('更多')
      // Opens leftwards, so it stays inside the window rather than hanging past its right edge.
      .bindMenu(this.MoreMenu(), { placement: Placement.BottomRight })
    }
    .width('100%')
    .onAreaChange((_: Area, area: Area) => {
      this.barWidth = Number(area.width);
    })
    // On a 2-in-1 the bar is the window's caption row: the window buttons sit at its right end.
    .constraintSize({ minHeight: this.captionTop })
    .padding({ left: 16, right: this.endInset(), top: 8, bottom: 8 })
    .opacity(this.barShown ? 1 : 0)
  }

  @Builder
  Legend() {
    Row({ space: 14 }) {
      if (this.originsOn) {
        Text('● 你').fontSize(12).fontColor($r('sys.color.font_primary'))
        Text('● 粘贴').fontSize(12).fontColor($r('app.color.pasted'))
        Text('● 语音').fontSize(12).fontColor($r('app.color.leaf_green'))
      }
      if (this.styleOn) {
        Text('仅作标记，不修改文本').fontSize(12).fontColor($r('sys.color.font_secondary'))
      }
    }
    .padding({ left: 14, right: 14, top: 6, bottom: 6 })
    .borderRadius(999)
    .backgroundColor($r('app.color.leaf_side'))
  }

  build() {
    Stack({ alignContent: Alignment.Top }) {
      Scroll(this.scroller) {
        Column() {
          TextInput({ text: this.title, placeholder: '标题' })
            .fontSize(sizeOf(this.font) * 1.35)
            .fontFamily(headingFamily(this.font))
            .fontWeight(FontWeight.Medium)
            .backgroundColor(Color.Transparent)
            .padding(0)
            .margin({ bottom: 24 })
            .onChange((v: string) => {
              // The field also reports the title the page itself put in (on opening); that is not typing.
              if (v === this.title) {
                return;
              }
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
                onActive: (k: string) => {
                  this.activeRun = k;
                },
                dictation: sp.part.key === this.dictationRun() ? this.dictation : '',
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
      // Words that scroll up under the bar blur and fade into the paper instead of being cut off.
      .linearGradientBlur(24, topBlur(TOP_EDGE, this.pageHeight))
      .onAreaChange((_: Area, area: Area) => {
        this.viewport = Number(area.height);
        this.pageHeight = this.viewport;
      })

      TopFade(TOP_EDGE)
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
    .backgroundColor($r('app.color.leaf_paper'))
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
