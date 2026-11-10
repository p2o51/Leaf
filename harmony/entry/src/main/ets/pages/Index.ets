import { LengthMetrics, SymbolGlyphModifier } from '@kit.ArkUI';
import { resourceManager } from '@kit.LocalizationKit';
import {
  HdsNavDestination,
  HdsNavDestinationAttribute,
  HdsNavigation,
  HdsNavigationAttribute,
  HdsSideBar,
  DividerShowType,
  TitleBarStyleOptions,
} from '@kit.UIDesignKit';
import { Essay, FilterKind, HomeFilter, NotionColor } from '../core/Models';
import { TagRules } from '../core/Tags';
import { LeafApp } from '../data/LeafApp';
import { SyncState } from '../data/SyncEngine';
import { EssayPage } from '../ui/EssayPage';
import { SettingsPage } from '../ui/SettingsPage';
import { SideEntry, SideMenu } from '../ui/SideMenu';
import { dayLabel, IconButtonParams, PLEX_SANS_SEMIBOLD, readingFamily, registerFonts, swatchColor } from '../ui/Look';
import { CAPTION_RIGHT_KEY, CAPTION_TOP_KEY, CaptionStrip, WINDOW_WIDTH_KEY, WindowChrome } from '../ui/WindowChrome';

/** From this width (vp) the sidebar sits beside the list and the desk, as the Mac window's three columns. */
const THREE: number = 960;
/** From this width the list and the desk sit side by side; below it the list is alone and an essay opens over it. */
const SPLIT: number = 720;
const SIDEBAR: number = 220;
const LIST: number = 300;
/** The top row of each column, as tall as the window's caption row on a 2-in-1 (vp). */
const HEADER: number = 56;
/** Room the settings page's back button takes at the left end of its title bar (vp). */
const BACK_WIDTH: number = 64;

/**
 * Home, laid out as Leaf for Mac: the sidebar (search, 全部随笔, 颜色, 标签, and a quiet sync line), the essay
 * list (a colour edge, title, two lines, date and tags), and the 写作台 for the chosen essay beside it. Narrower
 * windows fold the sidebar into a drawer, then open an essay over the list. Quiet on purpose: no counts, no
 * badges, no streaks.
 */
@Entry
@Component
struct Index {
  @Provide('leafNav') nav: NavPathStack = new NavPathStack();
  @State essays: Essay[] = [];
  @State filter: HomeFilter = HomeFilter.All;
  @State search: string = '';
  /** The essay open in the desk beside the list; '' for none. */
  @State selected: string = '';
  @State hovered: string = '';
  // Seeded with the window's opening width: starting from 0 would lay out the narrow form first and switch a
  // frame later.
  @State winWidth: number = AppStorage.get<number>(WINDOW_WIDTH_KEY) ?? 0;
  /** Wide windows: the sidebar was put away. */
  @State sideClosed: boolean = false;
  /** Narrower windows: the sidebar is open over the list. */
  @State drawer: boolean = false;
  @State status: string = '';
  @State connected: boolean = false;
  @State loading: boolean = true;
  @StorageProp(CAPTION_TOP_KEY) captionTop: number = 0;
  @StorageProp(CAPTION_RIGHT_KEY) captionRight: number = 0;
  private unsubscribe: Array<() => void> = [];

  aboutToAppear(): void {
    registerFonts(this.getUIContext());
    this.trackDarkMode();
    LeafApp.ready().then(() => {
      this.loading = false;
      this.refresh();
      this.unsubscribe.push(LeafApp.lib.subscribe(() => this.refresh()));
      this.unsubscribe.push(LeafApp.sync.subscribe(() => {
        const s = LeafApp.sync;
        this.status = s.state === SyncState.Running ? '同步中…' : s.message;
      }));
    });
  }

  aboutToDisappear(): void {
    this.unsubscribe.forEach((u: () => void) => u());
  }

  onPageShow(): void {
    this.trackDarkMode();
  }

  /** The 写作台 draws faded ink as explicit colours, so it needs to know the colour mode. */
  private trackDarkMode(): void {
    const mode = this.getUIContext().getHostContext()?.resourceManager.getConfigurationSync().colorMode;
    AppStorage.setOrCreate<boolean>('leaf.dark', mode === resourceManager.ColorMode.DARK);
  }

  private refresh(): void {
    this.essays = TagRules.ordered(LeafApp.lib.essays);
    this.connected = LeafApp.lib.settings.connection() !== null;
    if (this.filterIndex() < 0) {
      // The colour or tag it showed is gone (its last essay changed or went away).
      this.filter = HomeFilter.All;
    }
    if (this.selected.length > 0 && LeafApp.lib.essay(this.selected) === undefined) {
      this.selected = '';
    }
  }

  // Methods, not getters: the ArkUI transform drops getters declared in a struct.
  private wide(): boolean {
    return this.winWidth >= THREE;
  }

  private split(): boolean {
    return this.winWidth >= SPLIT;
  }

  /** The essay open on the 写作台 beside the list; alone in the window the list shows none as open. */
  private isOpen(id: string): boolean {
    return this.split() && id === this.selected;
  }

  private sideShown(): boolean {
    return this.wide() ? !this.sideClosed : this.drawer;
  }

  /** The list: the sidebar's filter, then the search words in a title or the opening lines, as on the Mac. */
  private visibleEssays(): Essay[] {
    const q = this.search.trim().toLowerCase();
    return this.essays.filter((e: Essay) => TagRules.matches(e, this.filter) && (q.length === 0 ||
      e.title.toLowerCase().indexOf(q) >= 0 || e.previewText.toLowerCase().indexOf(q) >= 0));
  }

  /** The sidebar's filters in menu order: 全部, the colours in use, then the tags. */
  private filters(): HomeFilter[] {
    const out: HomeFilter[] = [HomeFilter.All];
    TagRules.colorsInUse(this.essays).forEach((c: NotionColor) => out.push(new HomeFilter(FilterKind.Color, c.apiName)));
    TagRules.all(this.essays).forEach((t: string) => out.push(new HomeFilter(FilterKind.Tag, t)));
    return out;
  }

  private filterIndex(): number {
    return this.filters().findIndex((f: HomeFilter) => f.equals(this.filter));
  }

  private sideEntries(): SideEntry[] {
    const out: SideEntry[] = [{ label: '全部随笔', symbol: $r('sys.symbol.doc_text') }];
    TagRules.colorsInUse(this.essays).forEach((c: NotionColor, i: number) => {
      out.push({ label: colorName(c), symbol: $r('sys.symbol.circle_fill'), dot: swatchColor(c), group: i === 0 ? '颜色' : undefined });
    });
    TagRules.all(this.essays).forEach((t: string, i: number) => {
      out.push({ label: t, symbol: $r('sys.symbol.label'), glyph: '#', group: i === 0 ? '标签' : undefined });
    });
    return out;
  }

  private title(): string {
    switch (this.filter.kind) {
      case FilterKind.Color:
        return colorName(NotionColor.fromApi(this.filter.value));
      case FilterKind.Tag:
        return `#${this.filter.value}`;
      default:
        return '全部随笔';
    }
  }

  /** Beside the list when there is room for the desk, otherwise over it. */
  private open(id: string): void {
    if (this.split()) {
      this.selected = id;
    } else {
      this.nav.pushPath({ name: 'essay', param: id });
    }
  }

  /** As the Mac's ⌘N: a new essay under 全部随笔, open at once. */
  private async create(): Promise<void> {
    const e = await LeafApp.lib.createEssay();
    this.filter = HomeFilter.All;
    this.search = '';
    this.open(e.id);
  }

  private pick(index: number): void {
    const f = this.filters()[index];
    if (f !== undefined) {
      this.filter = f;
    }
    if (!this.wide()) {
      this.drawer = false;
    }
  }

  private toggleSide(): void {
    if (this.wide()) {
      this.sideClosed = !this.sideClosed;
    } else {
      this.drawer = !this.drawer;
    }
  }

  private openSettings(): void {
    this.drawer = false;
    this.nav.pushPath({ name: 'settings' });
  }

  /** Under a row's title: the day it was written and up to three tags, as the Mac list shows them. */
  private rowMeta(e: Essay): string {
    const day = dayLabel(e.createdAt > 0 ? e.createdAt : e.editedAt);
    return [day].concat(e.tags.slice(0, 3).map((t: string) => `#${t}`)).join('  ');
  }

  /** A caption-row button: a plain symbol in Leaf's ink. On a 2-in-1 only buttons there take clicks. */
  @Builder
  Tool(p: IconButtonParams) {
    Button({ type: ButtonType.Circle, stateEffect: true }) {
      SymbolGlyph(p.icon).fontSize(20).fontColor([$r('app.color.leaf_text')])
    }
    .width(36)
    .height(36)
    .backgroundColor(Color.Transparent)
    .accessibilityText(p.label)
    .onClick(() => p.action())
  }

  /** The empty part of a caption row: pressing it moves the window, a double-click maximizes it. */
  @Builder
  DragSpace() {
    Blank()
      .alignSelf(ItemAlign.Stretch)
      .onTouch((e: TouchEvent) => WindowChrome.drag(e))
      .gesture(TapGesture({ count: 2 }).onAction(() => WindowChrome.toggleMaximize()))
  }

  @Builder
  Sidebar() {
    Column() {
      // Leaf's mark, as on Android's drawer: a lime badge and the name. Its row is the left end of the
      // window's caption row on a 2-in-1.
      Row({ space: 10 }) {
        Text('L')
          .width(26)
          .height(26)
          .borderRadius(13)
          .textAlign(TextAlign.Center)
          .fontSize(15)
          .fontFamily(PLEX_SANS_SEMIBOLD)
          .fontColor($r('app.color.leaf_on_lime'))
          .backgroundColor($r('app.color.leaf_lime'))
        Text('Leaf')
          .fontSize(18)
          .fontFamily(PLEX_SANS_SEMIBOLD)
          .fontColor($r('app.color.leaf_text'))
        this.DragSpace()
        this.Tool({ icon: $r('sys.symbol.close_sidebar'), label: '收起侧栏', action: () => this.toggleSide() })
      }
      .width('100%')
      .height(HEADER)
      .padding({ left: 16, right: 8 })

      Search({ value: this.search, placeholder: '搜索随笔' })
        .height(36)
        .margin({ left: 12, right: 12, bottom: 4 })
        .backgroundColor($r('app.color.leaf_chip'))
        .placeholderColor($r('app.color.leaf_text_2'))
        .fontColor($r('app.color.leaf_text'))
        .textFont({ size: 15 })
        .onChange((v: string) => {
          this.search = v;
        })

      SideMenu({
        entries: this.sideEntries(),
        selected: Math.max(this.filterIndex(), 0),
        onPick: (i: number) => this.pick(i),
      })
        .layoutWeight(1)

      // The Mac's sync line: says something only while syncing or when something needs the person. HarmonyOS
      // has no menu bar, so settings sit here too.
      Row({ space: 4 }) {
        if (this.connected) {
          Text(this.status)
            .fontSize(12)
            .fontColor($r('app.color.leaf_text_2'))
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        } else {
          Button('连接 Notion…', { buttonStyle: ButtonStyleMode.NORMAL, controlSize: ControlSize.SMALL })
            .fontColor($r('app.color.leaf_text'))
            .backgroundColor($r('app.color.leaf_chip'))
            .onClick(() => this.openSettings())
          Blank()
        }
        this.Tool({ icon: $r('sys.symbol.gearshape'), label: '设置', action: () => this.openSettings() })
      }
      .width('100%')
      .padding({ left: 14, right: 8, top: 4, bottom: 10 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.leaf_side'))
  }

  @Builder
  CardMenu(e: Essay) {
    Menu() {
      MenuItem({
        symbolStartIcon: new SymbolGlyphModifier($r(e.pinned ? 'sys.symbol.pin_slash' : 'sys.symbol.pin')),
        content: e.pinned ? '取消置顶' : '置顶',
      })
        .onClick(() => LeafApp.lib.updateMeta(e.id, e.title, e.color, !e.pinned))
      // A submenu of the nine Notion colours plus none.
      MenuItem({
        symbolStartIcon: new SymbolGlyphModifier($r('sys.symbol.paintpalette')),
        content: '卡片颜色',
        builder: (): void => this.ColorMenu(e),
      })
      MenuItem({
        symbolStartIcon: new SymbolGlyphModifier($r('sys.symbol.trash')),
        content: '移到 Notion 回收站',
      })
        .onClick(() => {
          if (this.selected === e.id) {
            this.selected = '';
          }
          LeafApp.lib.deleteEssay(e.id);
        })
    }
  }

  @Builder
  ColorMenu(e: Essay) {
    Menu() {
      ForEach(NotionColor.entries, (c: NotionColor) => {
        MenuItem({ content: c === NotionColor.Default ? '无颜色' : colorName(c) })
          .selected(e.color === c)
          .selectIcon(true)
          .onClick(() => LeafApp.lib.updateMeta(e.id, e.title, c, e.pinned))
      }, (c: NotionColor) => c.apiName)
    }
  }

  // As a row of the Mac list: the colour as a thin edge, the title (pinned ones marked), two lines of the
  // words, then the day and tags. The open essay sits on Leaf's lime.
  @Builder
  EssayRow(e: Essay) {
    Row({ space: 10 }) {
      Column()
        .width(4)
        .alignSelf(ItemAlign.Stretch)
        .borderRadius(2)
        .backgroundColor(swatchColor(e.color))
      Column({ space: 3 }) {
        Row({ space: 4 }) {
          if (e.pinned) {
            SymbolGlyph($r('sys.symbol.pin_fill'))
              .fontSize(12)
              .fontColor([this.isOpen(e.id) ? $r('app.color.leaf_on_lime') : $r('app.color.leaf_text_2')])
          }
          Text(e.title.length > 0 ? e.title : '无题')
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.isOpen(e.id) ? $r('app.color.leaf_on_lime') : $r('app.color.leaf_text'))
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        if (e.previewText.length > 0) {
          Text(e.previewText)
            .fontSize(14)
            .lineHeight(20)
            .fontColor(this.isOpen(e.id) ? $r('app.color.leaf_on_lime') : $r('app.color.leaf_text_2'))
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        Text(this.rowMeta(e))
          .fontSize(12)
          .fontColor(this.isOpen(e.id) ? $r('app.color.leaf_on_lime') : $r('app.color.leaf_text_2'))
          .opacity(0.75)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .width('100%')
    .padding({ left: 8, right: 12, top: 10, bottom: 10 })
    .borderRadius(12)
    .backgroundColor(this.isOpen(e.id) ? $r('app.color.leaf_lime')
      : (e.id === this.hovered ? $r('sys.color.interactive_hover') : Color.Transparent))
    .onHover((on: boolean) => {
      this.hovered = on ? e.id : (this.hovered === e.id ? '' : this.hovered);
    })
    .onClick(() => this.open(e.id))
    .bindContextMenu(this.CardMenu(e), ResponseType.LongPress)
    .bindContextMenu(this.CardMenu(e), ResponseType.RightClick)
    .accessibilityGroup(true)
    .accessibilitySelected(this.isOpen(e.id))
  }

  @Builder
  Empty() {
    Column({ space: 16 }) {
      Text(this.search.trim().length > 0 ? '没有找到' : (this.connected ? '还没有随笔' : '写在这里，存进 Notion'))
        .fontSize(16)
        .fontFamily(readingFamily(false))
        .fontColor($r('app.color.leaf_text_2'))
      if (!this.connected && this.search.trim().length === 0) {
        Button('连接 Notion')
          .backgroundColor($r('app.color.leaf_green'))
          .fontColor($r('app.color.leaf_on_green'))
          .onClick(() => this.openSettings())
      }
    }
    .width('100%')
    .layoutWeight(1)
    .padding({ top: 96 })
  }

  @Builder
  ListPane() {
    Column() {
      Row({ space: 4 }) {
        if (!this.sideShown()) {
          this.Tool({ icon: $r('sys.symbol.open_sidebar'), label: '打开侧栏', action: () => this.toggleSide() })
        }
        Column({ space: 1 }) {
          Text(this.title())
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor($r('app.color.leaf_text'))
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          // With the sidebar away its sync line has nowhere to show, so it comes up here.
          if (!this.sideShown() && this.status.length > 0) {
            Text(this.status)
              .fontSize(12)
              .fontColor($r('app.color.leaf_text_2'))
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .constraintSize({ maxWidth: '70%' })
        this.DragSpace()
        this.Tool({
          icon: $r('sys.symbol.square_and_pencil'), label: '新随笔', action: () => {
            this.create();
          }
        })
      }
      .width('100%')
      .height(HEADER)
      // Alone in the window, the list's caption row also holds the window buttons at its right end.
      .padding({ left: this.sideShown() ? 16 : 8, right: this.split() ? 8 : Math.max(8, this.captionRight) })

      if (!this.loading && this.visibleEssays().length === 0) {
        this.Empty()
      } else {
        // A hairline between rows from the text's left edge, as the Mac list draws them; the 2vp gap keeps it
        // off the open row's fill.
        List({ space: 2 }) {
          ForEach(this.visibleEssays(), (e: Essay) => {
            ListItem() {
              this.EssayRow(e)
            }
          }, (e: Essay) => `${e.id}:${e.editedAt}:${e.color.apiName}:${e.pinned}`)
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: 8, right: 8, bottom: 16 })
        .divider({ strokeWidth: 0.5, color: $r('app.color.leaf_chip'), startMargin: 22, endMargin: 12 })
        .scrollBar(BarState.Auto)
      }
    }
    .width(this.split() ? LIST : '100%')
    .height('100%')
    .backgroundColor($r('app.color.leaf_paper'))
  }

  @Builder
  Detail() {
    Stack({ alignContent: Alignment.TopStart }) {
      if (this.selected.length > 0) {
        // Keyed by the essay, so choosing another one builds a fresh editor (the old one writes its last
        // words as it goes).
        ForEach([this.selected], (id: string) => {
          EssayPage({
            essayId: id,
            embedded: true,
            onBack: () => {
              this.selected = '';
            },
          })
        }, (id: string) => id)
      } else {
        Column() {
          Text(this.connected ? '选一篇随笔，或写一篇新的（Ctrl+N）' : '在设置里连接 Notion，或先写一篇（Ctrl+N）')
            .fontSize(15)
            .fontColor($r('app.color.leaf_text_2'))
            .opacity(0.7)
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        CaptionStrip({ startInset: 0, endInset: 0 })
      }
    }
    .layoutWeight(1)
    .height('100%')
    .backgroundColor($r('app.color.leaf_paper'))
  }

  @Builder
  Main() {
    Stack({ alignContent: Alignment.TopStart }) {
      Row() {
        this.ListPane()
        if (this.split()) {
          Divider()
            .vertical(true)
            .strokeWidth(1)
            .color($r('app.color.leaf_chip'))
            .height('100%')
          this.Detail()
        }
      }
      .width('100%')
      .height('100%')

      // Ctrl+N makes a new essay, as the list's 新随笔 does; Ctrl+, opens settings, as on the Mac.
      Button()
        .width(1)
        .height(1)
        .opacity(0)
        .focusable(false)
        .hitTestBehavior(HitTestMode.None)
        .keyboardShortcut('n', [ModifierKey.CTRL])
        .onClick(() => this.create())
      Button()
        .width(1)
        .height(1)
        .opacity(0)
        .focusable(false)
        .hitTestBehavior(HitTestMode.None)
        .keyboardShortcut(',', [ModifierKey.CTRL])
        .onClick(() => this.openSettings())
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  Pages(name: string, param: Object) {
    if (name === 'essay') {
      HdsNavDestination() {
        EssayPage({ essayId: param as string, onBack: () => this.nav.pop() })
      }
      .hideTitleBar(true)
      .backgroundColor($r('app.color.leaf_paper'))
    } else if (name === 'settings') {
      HdsNavDestination() {
        Stack({ alignContent: Alignment.TopStart }) {
          SettingsPage({ top: HEADER })
          CaptionStrip({ startInset: BACK_WIDTH, endInset: 0 })
        }
      }
      .titleBar({
        padding: { end: LengthMetrics.vp(16 + this.captionRight) },
        style: titleBarStyle($r('app.color.leaf_side')),
        content: { divider: { showType: DividerShowType.OFF }, title: { mainTitle: '设置' } },
      })
      .backgroundColor($r('app.color.leaf_side'))
    }
  }

  build() {
    HdsNavigation(this.nav) {
      HdsSideBar({
        sideBarPanelBuilder: (): void => this.Sidebar(),
        contentPanelBuilder: (): void => this.Main(),
        isShowSideBar: this.sideShown(),
        $isShowSideBar: (shown: boolean) => {
          if (this.wide()) {
            this.sideClosed = !shown;
          } else {
            this.drawer = shown;
          }
        },
        sideBarContainerType: this.wide() ? SideBarContainerType.Embed : SideBarContainerType.Overlay,
        sideBarWidth: SIDEBAR,
        minSideBarWidth: 200,
        maxSideBarWidth: 300,
        autoHide: false,
        contentAreaMask: true,
      })
    }
    .mode(NavigationMode.Stack)
    .hideTitleBar(true)
    .hideToolBar(true)
    .navDestination(this.Pages)
    .onAreaChange((_: Area, area: Area) => {
      this.winWidth = Number(area.width);
    })
  }
}

/**
 * Title bars stay the page's own paper when content scrolls under them: no frosted panel and no rule (on the
 * MateBook the blur renders as an opaque white slab that the paper does not match).
 */
function titleBarStyle(paper: ResourceColor): TitleBarStyleOptions {
  return {
    scrollEffectOpts: { enableScrollEffect: false },
    originalStyle: { backgroundStyle: { backgroundColor: paper } },
    scrollEffectStyle: { backgroundStyle: { backgroundColor: paper } },
  };
}

/** How the sidebar and title name a colour: 红 → 红色. */
function colorName(c: NotionColor): string {
  return `${c.label}色`;
}
