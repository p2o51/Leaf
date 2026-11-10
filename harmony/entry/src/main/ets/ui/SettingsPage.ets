import { TextModifier } from '@kit.ArkUI';
import { HdsListItemCard, SuffixArrow } from '@kit.UIDesignKit';
import { DataSourceInfo } from '../core/NotionJson';
import { LeafApp } from '../data/LeafApp';
import { NotionError } from '../data/NotionApi';

/**
 * Connecting Leaf to a Notion database, laid out as HarmonyOS settings are: grey page, white HDS list cards,
 * a small grey heading over each group. For now the token is an internal integration's secret pasted here (the
 * Android app's fallback path); 用 Notion 登录 through notion-oauth-relay needs a HarmonyOS callback on the
 * relay first (see harmony/HANDOFF.md). The title bar and its back button belong to the page around this.
 */
@Component
export struct SettingsPage {
  /** The HDS title bar floats over the page; this much is left free under it (vp). */
  top: number = 0;
  @State token: string = '';
  @State busy: boolean = false;
  @State error: string = '';
  @State sources: DataSourceInfo[] = [];
  @State connected: boolean = false;
  @State workspace: string = '';
  @State database: string = '';

  aboutToAppear(): void {
    this.readConnection();
  }

  private readConnection(): void {
    const c = LeafApp.lib.settings.connection();
    this.connected = c !== null;
    this.workspace = c === null ? '' : c.workspace;
    this.database = c === null ? '' : c.databaseName;
  }

  private async discover(): Promise<void> {
    this.busy = true;
    this.error = '';
    try {
      this.sources = await LeafApp.sync.discover(this.token);
      if (this.sources.length === 0) {
        this.error = '集成看不到数据库：在 Notion 打开数据库 › ⋯ › 连接，加上它';
      }
    } catch (e) {
      this.error = e instanceof NotionError ? e.userMessage : '连不上 Notion，检查网络';
    }
    this.busy = false;
  }

  private async connect(source: DataSourceInfo): Promise<void> {
    this.busy = true;
    this.error = '';
    try {
      await LeafApp.sync.connect(this.token, source);
      this.token = '';
      this.sources = [];
      this.readConnection();
    } catch (e) {
      this.error = e instanceof NotionError ? e.userMessage : '连接失败，再试一次';
    }
    this.busy = false;
  }

  private async disconnect(): Promise<void> {
    await LeafApp.sync.disconnect();
    this.readConnection();
  }

  @Builder
  Heading(text: string) {
    Text(text)
      .fontSize(14)
      .fontWeight(FontWeight.Medium)
      .fontColor($r('sys.color.font_secondary'))
      .padding({ left: 20, top: 20, bottom: 8 })
  }

  @Builder
  Footnote(text: string) {
    Text(text)
      .fontSize(12)
      .fontColor($r('sys.color.font_secondary'))
      .padding({ left: 20, right: 20, top: 8 })
  }

  @Builder
  Connected() {
    this.Heading('Notion')
    HdsListItemCard({
      cardBackgroundColor: $r('app.color.leaf_raised'),
      textItem: {
        primaryText: { text: this.database },
        secondaryText: { text: this.workspace },
      },
    })
    this.Footnote('随笔先存本机，打开的随写随传，其余后台同步')
    Column() {
      HdsListItemCard({
        cardBackgroundColor: $r('app.color.leaf_raised'),
        textItem: {
          primaryText: { text: '断开连接', modifier: new TextModifier().fontColor($r('sys.color.warning')) },
        },
        onClick: () => {
          this.disconnect();
        },
      })
    }
    .margin({ top: 20 })
  }

  @Builder
  NotConnected() {
    this.Heading('连接 Notion')
    Column({ space: 12 }) {
      Text('在 notion.so/profile/integrations 新建内部集成（读取、更新、插入内容），加到随笔数据库，再把密钥贴到这里')
        .fontSize(14)
        .fontColor($r('sys.color.font_secondary'))
      TextInput({ text: this.token, placeholder: '集成密钥 ntn_…' })
        .type(InputType.Password)
        .caretColor($r('app.color.leaf_ink'))
        .onChange((v: string) => {
          this.token = v;
        })
      Button(this.busy ? '正在找…' : '找数据库')
        .width('100%')
        .backgroundColor($r('app.color.leaf_green'))
        .fontColor($r('app.color.leaf_on_green'))
        .enabled(!this.busy && this.token.trim().length > 0)
        .onClick(() => this.discover())
    }
    .alignItems(HorizontalAlign.Start)
    .padding(16)
    .width('100%')
    .borderRadius(20)
    .backgroundColor($r('app.color.leaf_raised'))

    if (this.sources.length > 0) {
      this.Heading('选择随笔数据库')
      Column({ space: 2 }) {
        ForEach(this.sources, (s: DataSourceInfo) => {
          HdsListItemCard({
            cardBackgroundColor: $r('app.color.leaf_raised'),
            textItem: { primaryText: { text: s.name } },
            suffixItem: new SuffixArrow(),
            enable: !this.busy,
            onClick: () => {
              this.connect(s);
            },
          })
        }, (s: DataSourceInfo) => s.id)
      }
    }
  }

  build() {
    Scroll() {
      Column() {
        if (this.connected) {
          this.Connected()
        } else {
          this.NotConnected()
        }
        if (this.error.length > 0) {
          Text(this.error)
            .fontSize(13)
            .fontColor($r('sys.color.warning'))
            .padding({ left: 20, right: 20, top: 12 })
        }
      }
      .width('100%')
      .constraintSize({ maxWidth: 720 })
      .padding({ left: 16, right: 16, top: this.top, bottom: 32 })
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .align(Alignment.Top)
    .scrollBar(BarState.Auto)
  }
}
