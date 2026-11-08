import { DataSourceInfo } from '../core/NotionJson';
import { LeafApp } from '../data/LeafApp';
import { NotionError } from '../data/NotionApi';

/**
 * Connecting Leaf to a Notion database. For now the token is an internal integration's secret pasted
 * here (the Android app's fallback path); 用 Notion 登录 through notion-oauth-relay needs a HarmonyOS
 * callback on the relay first (see harmony/HANDOFF.md).
 */
@Component
export struct SettingsPage {
  onBack: () => void = () => {};
  @State token: string = '';
  @State busy: boolean = false;
  @State error: string = '';
  @State sources: DataSourceInfo[] = [];
  @State connectedTo: string = '';

  aboutToAppear(): void {
    this.readConnection();
  }

  private readConnection(): void {
    const c = LeafApp.lib.settings.connection();
    this.connectedTo = c === null ? '' : `${c.workspace} · ${c.databaseName}`;
  }

  private async discover(): Promise<void> {
    this.busy = true;
    this.error = '';
    try {
      this.sources = await LeafApp.sync.discover(this.token);
      if (this.sources.length === 0) {
        this.error = '这个集成还看不到任何数据库：在 Notion 里打开数据库 › ⋯ › 连接，加上它';
      }
    } catch (e) {
      this.error = e instanceof NotionError ? e.userMessage : '连不上 Notion，检查一下网络';
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
      this.error = e instanceof NotionError ? e.userMessage : '连接没成功，再试一次';
    }
    this.busy = false;
  }

  private async disconnect(): Promise<void> {
    await LeafApp.sync.disconnect();
    this.readConnection();
  }

  build() {
    Column({ space: 16 }) {
      Row() {
        Text('←').fontSize(20).padding(10).onClick(() => this.onBack())
        Text('设置').fontSize(20).fontWeight(FontWeight.Medium)
      }
      .width('100%')

      Text('Notion').fontSize(15).fontColor($r('app.color.on_surface_variant'))
      if (this.connectedTo.length > 0) {
        Column({ space: 10 }) {
          Text(`已连接：${this.connectedTo}`).fontSize(16)
          Text('随笔先存在这台设备上，打开的那篇随写随传，其余的在后台慢慢同步。')
            .fontSize(13)
            .fontColor($r('app.color.on_surface_variant'))
          Button('断开连接', { type: ButtonType.Capsule })
            .backgroundColor($r('app.color.surface_container'))
            .fontColor($r('app.color.on_surface'))
            .onClick(() => this.disconnect())
        }
        .alignItems(HorizontalAlign.Start)
        .padding(16)
        .borderRadius(20)
        .width('100%')
        .backgroundColor($r('app.color.surface_low'))
      } else {
        Column({ space: 10 }) {
          Text('在 notion.so/profile/integrations 新建一个内部集成（读取、更新、插入内容），把它加到你的随笔数据库，再把密钥贴到这里。')
            .fontSize(13)
            .fontColor($r('app.color.on_surface_variant'))
          TextInput({ text: this.token, placeholder: 'ntn_…' })
            .type(InputType.Password)
            .onChange((v: string) => {
              this.token = v;
            })
          Button(this.busy ? '正在看…' : '找数据库', { type: ButtonType.Capsule })
            .enabled(!this.busy && this.token.trim().length > 0)
            .backgroundColor($r('app.color.primary'))
            .onClick(() => this.discover())
          ForEach(this.sources, (s: DataSourceInfo) => {
            Row() {
              Text(s.name).fontSize(16).layoutWeight(1)
              Text('用这个').fontSize(14).fontColor($r('app.color.primary'))
            }
            .padding(14)
            .width('100%')
            .borderRadius(16)
            .backgroundColor($r('app.color.surface_container'))
            .onClick(() => this.connect(s))
          }, (s: DataSourceInfo) => s.id)
        }
        .alignItems(HorizontalAlign.Start)
        .padding(16)
        .borderRadius(20)
        .width('100%')
        .backgroundColor($r('app.color.surface_low'))
      }
      if (this.error.length > 0) {
        Text(this.error).fontSize(13).fontColor($r('app.color.tertiary'))
      }
      Blank()
    }
    .width('100%')
    .height('100%')
    .constraintSize({ maxWidth: 640 })
    .padding(20)
    .alignItems(HorizontalAlign.Start)
    .backgroundColor($r('app.color.surface'))
  }
}
