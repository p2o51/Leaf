import { resourceManager } from '@kit.LocalizationKit';
import { Essay, FilterKind, HomeFilter, NotionColor } from '../core/Models';
import { TagRules } from '../core/Tags';
import { LeafApp } from '../data/LeafApp';
import { SyncState } from '../data/SyncEngine';
import { EssayPage } from '../ui/EssayPage';
import { SettingsPage } from '../ui/SettingsPage';
import { cardColor, familyOf, DeskFont, PLEX_SANS, registerFonts } from '../ui/Look';

/** Below this width (vp) the sidebar becomes a drawer and cards go to two columns. */
const WIDE: number = 840;

/**
 * Home: every essay as a card, filtered by 全部 / a colour / a tag from the sidebar (Keep-style, as on
 * Android). Wide windows (2-in-1, tablet landscape) keep the sidebar open; narrow ones slide it in.
 * Quiet on purpose: no counts, no badges, no streaks.
 */
@Entry
@Component
struct Index {
  @Provide('leafNav') nav: NavPathStack = new NavPathStack();
  @State essays: Essay[] = [];
  @State filter: HomeFilter = HomeFilter.All;
  @State winWidth: number = 0;
  @State drawer: boolean = false;
  @State status: string = '';
  @State connected: boolean = false;
  @State loading: boolean = true;
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
  }

  private get wide(): boolean {
    return this.winWidth >= WIDE;
  }

  private get visible(): Essay[] {
    return this.essays.filter((e: Essay) => TagRules.matches(e, this.filter));
  }

  private open(id: string): void {
    this.nav.pushPath({ name: 'essay', param: id });
  }

  private async create(): Promise<void> {
    const e = await LeafApp.lib.createEssay();
    this.open(e.id);
  }

  private pick(f: HomeFilter): void {
    this.filter = f;
    if (!this.wide) {
      this.drawer = false;
    }
  }

  @Builder
  FilterRow(label: string, f: HomeFilter, dot: ResourceColor | null) {
    Row({ space: 10 }) {
      if (dot !== null) {
        Circle().width(12).height(12).fill(dot)
      }
      Text(label).fontSize(15).fontFamily(PLEX_SANS).layoutWeight(1).maxLines(1)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    .borderRadius(999)
    .backgroundColor(this.filter.equals(f) ? $r('app.color.primary_container') : Color.Transparent)
    .onClick(() => this.pick(f))
  }

  @Builder
  Sidebar() {
    Column({ space: 2 }) {
      Text('Leaf').fontSize(26).fontFamily(familyOf(DeskFont.Serif)).fontWeight(FontWeight.Medium)
        .padding({ left: 16, top: 24, bottom: 16 })
      this.FilterRow('全部', HomeFilter.All, null)
      ForEach(TagRules.colorsInUse(this.essays), (c: NotionColor) => {
        this.FilterRow(c.label, new HomeFilter(FilterKind.Color, c.apiName), cardColor(c))
      }, (c: NotionColor) => c.apiName)
      if (TagRules.all(this.essays).length > 0) {
        Text('标签').fontSize(13).fontColor($r('app.color.on_surface_variant')).padding({ left: 16, top: 16, bottom: 6 })
      }
      ForEach(TagRules.all(this.essays), (t: string) => {
        this.FilterRow(`#${t}`, new HomeFilter(FilterKind.Tag, t), null)
      }, (t: string) => t)
      Blank()
      Row() {
        Text('设置').fontSize(15).layoutWeight(1)
        if (this.status.length > 0) {
          Text(this.status).fontSize(12).fontColor($r('app.color.on_surface_variant')).maxLines(1)
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 20 })
      .onClick(() => this.nav.pushPath({ name: 'settings' }))
    }
    .width('100%')
    .height('100%')
    .padding({ left: 8, right: 8 })
    .alignItems(HorizontalAlign.Start)
    .backgroundColor($r('app.color.surface_low'))
  }

  @Builder
  CardMenu(e: Essay) {
    Menu() {
      MenuItem({ content: e.pinned ? '取消置顶' : '置顶' })
        .onClick(() => LeafApp.lib.updateMeta(e.id, e.title, e.color, !e.pinned))
      // A submenu of the nine Notion colours plus none.
      MenuItem({ content: '卡片颜色', builder: (): void => this.ColorMenu(e) })
      MenuItem({ content: '删除（Notion 里可在回收站找回）' })
        .onClick(() => LeafApp.lib.deleteEssay(e.id))
    }
  }

  @Builder
  ColorMenu(e: Essay) {
    Menu() {
      ForEach(NotionColor.entries, (c: NotionColor) => {
        MenuItem({ content: c.label })
          .selected(e.color === c)
          .onClick(() => LeafApp.lib.updateMeta(e.id, e.title, c, e.pinned))
      }, (c: NotionColor) => c.apiName)
    }
  }

  @Builder
  Card(e: Essay) {
    Column({ space: 8 }) {
      if (e.title.length > 0) {
        Text(e.title).fontSize(18).fontFamily(familyOf(DeskFont.Serif)).fontWeight(FontWeight.Medium).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      if (e.previewText.length > 0) {
        Text(e.previewText).fontSize(14).lineHeight(22).fontColor($r('app.color.on_surface_variant')).maxLines(7)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      if (e.title.length === 0 && e.previewText.length === 0) {
        Text('空白随笔').fontSize(14).fontColor($r('app.color.on_surface_variant'))
      }
      if (e.tags.length > 0) {
        Text(e.tags.map((t: string) => `#${t}`).join('  ')).fontSize(12).fontColor($r('app.color.primary')).maxLines(1)
      }
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding(18)
    .borderRadius(24)
    .backgroundColor(cardColor(e.color))
    .onClick(() => this.open(e.id))
    .bindContextMenu(this.CardMenu(e), ResponseType.LongPress)
    .bindContextMenu(this.CardMenu(e), ResponseType.RightClick)
  }

  @Builder
  Empty() {
    Column({ space: 14 }) {
      Text(this.connected ? '还没有随笔' : '写在这里，存进你的 Notion').fontSize(18).fontFamily(familyOf(DeskFont.Serif))
      if (!this.connected) {
        Button('连接 Notion', { type: ButtonType.Capsule })
          .backgroundColor($r('app.color.primary'))
          .onClick(() => this.nav.pushPath({ name: 'settings' }))
      }
    }
    .width('100%')
    .padding({ top: 120 })
  }

  @Builder
  Main() {
    Stack({ alignContent: Alignment.BottomEnd }) {
      Column() {
        if (!this.wide) {
          Row() {
            Text('☰').fontSize(22).padding(10).onClick(() => {
              this.drawer = true;
            })
            Text('Leaf').fontSize(22).fontFamily(familyOf(DeskFont.Serif)).fontWeight(FontWeight.Medium)
            Blank()
            if (this.status.length > 0) {
              Text(this.status).fontSize(12).fontColor($r('app.color.on_surface_variant'))
            }
          }
          .width('100%')
          .padding({ left: 8, right: 16, top: 8 })
        }
        if (!this.loading && this.visible.length === 0) {
          this.Empty()
        } else {
          WaterFlow() {
            ForEach(this.visible, (e: Essay) => {
              FlowItem() {
                this.Card(e)
              }
            }, (e: Essay) => `${e.id}:${e.editedAt}:${e.color.apiName}:${e.pinned}`)
          }
          .columnsTemplate(this.winWidth >= 1200 ? '1fr 1fr 1fr 1fr' : (this.winWidth >= 600 ? '1fr 1fr 1fr' : '1fr 1fr'))
          .columnsGap(12)
          .rowsGap(12)
          .padding({ left: 16, right: 16, top: 16, bottom: 96 })
          .layoutWeight(1)
        }
      }
      .width('100%')
      .height('100%')

      Button('＋', { type: ButtonType.Normal })
        .width(64)
        .height(64)
        .fontSize(28)
        .borderRadius(22)
        .fontColor($r('app.color.on_primary_container'))
        .backgroundColor($r('app.color.primary_container'))
        .margin(24)
        .keyboardShortcut('n', [ModifierKey.CTRL])
        .onClick(() => this.create())
    }
    .width('100%')
    .height('100%')
    .backgroundColor($r('app.color.surface'))
  }

  @Builder
  Pages(name: string, param: Object) {
    if (name === 'essay') {
      NavDestination() {
        EssayPage({ essayId: param as string, onBack: () => this.nav.pop() })
      }
      .hideTitleBar(true)
    } else if (name === 'settings') {
      NavDestination() {
        SettingsPage({ onBack: () => this.nav.pop() })
      }
      .hideTitleBar(true)
    }
  }

  build() {
    Navigation(this.nav) {
      SideBarContainer(this.wide ? SideBarContainerType.Embed : SideBarContainerType.Overlay) {
        this.Sidebar()
        this.Main()
      }
      .showSideBar(this.wide || this.drawer)
      .showControlButton(false)
      .sideBarWidth(260)
      .minSideBarWidth(220)
      .maxSideBarWidth(320)
      .autoHide(false)
      .divider({ strokeWidth: 0 })
      .onChange((shown: boolean) => {
        if (!this.wide) {
          this.drawer = shown;
        }
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
