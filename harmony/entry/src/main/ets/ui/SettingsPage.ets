import { common } from '@kit.AbilityKit';
import { TextModifier } from '@kit.ArkUI';
import { HdsListItemCard, SuffixArrow } from '@kit.UIDesignKit';
import { DataSourceInfo } from '../core/NotionJson';
import { LeafApp } from '../data/LeafApp';
import { NotionError } from '../data/NotionApi';
import { NotionOAuth } from '../data/NotionOAuth';
import { SpeechKey } from '../data/Voice';
import { FolderPicker, LocalFolder } from '../data/LocalFolder';

/**
 * Settings, laid out as HarmonyOS settings are: white list cards on the page, a small grey heading over each
 * group. Three groups: where the essays are kept on this computer (a folder of Markdown files), Notion (signed
 * in through notion-oauth-relay; nothing to paste), and the ElevenLabs key 语音输入 uses. The title bar and its
 * back button belong to the page around this.
 */
@Component
export struct SettingsPage {
  /** The HDS title bar floats over the page; this much is left free under it (vp). */
  top: number = 0;
  @State busy: boolean = false;
  @State waiting: boolean = false;
  @State error: string = '';
  @State sources: DataSourceInfo[] = [];
  @State connected: boolean = false;
  @State workspace: string = '';
  @State database: string = '';
  @State keyInput: string = '';
  @State keyMasked: string = '';
  @State keyError: string = '';
  @State folder: string = '';
  @State folderError: string = '';
  private token: string = '';

  aboutToAppear(): void {
    this.readConnection();
    this.readKey();
    this.folder = LocalFolder.shared.displayPath();
    const missed = NotionOAuth.takeMessage();
    if (missed.length > 0) {
      this.error = missed;
    }
  }

  aboutToDisappear(): void {
    NotionOAuth.cancel();
  }

  private context(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private readConnection(): void {
    const c = LeafApp.lib.settings.connection();
    this.connected = c !== null;
    this.workspace = c === null ? '' : c.workspace;
    this.database = c === null ? '' : c.databaseName;
  }

  private async readKey(): Promise<void> {
    this.keyMasked = await SpeechKey.maskedSaved();
  }

  private async signIn(): Promise<void> {
    this.error = '';
    this.sources = [];
    this.waiting = true;
    try {
      const token = await NotionOAuth.signIn(this.context());
      this.waiting = false;
      if (token.length === 0) {
        return;
      }
      this.token = token;
      this.busy = true;
      this.sources = await LeafApp.sync.discover(token);
      if (this.sources.length === 0) {
        this.error = '此次授权未包含数据库。请重新登录，并在 Notion 授权页中勾选随笔数据库。';
      }
    } catch (e) {
      this.error = e instanceof NotionError ? e.userMessage : '无法连接 Notion。请检查网络连接。';
    }
    this.waiting = false;
    this.busy = false;
  }

  private cancelSignIn(): void {
    NotionOAuth.cancel();
    this.waiting = false;
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
      this.error = e instanceof NotionError ? e.userMessage : '连接失败。请重试。';
    }
    this.busy = false;
  }

  private async disconnect(): Promise<void> {
    await LeafApp.sync.disconnect();
    this.readConnection();
  }

  private async saveKey(): Promise<void> {
    const key = SpeechKey.parse(this.keyInput);
    if (key === null) {
      this.keyError = '密钥格式不正确。请从 ElevenLabs 复制完整的 API 密钥。';
      return;
    }
    this.keyError = '';
    await SpeechKey.save(key);
    this.keyInput = '';
    await this.readKey();
  }

  private async removeKey(): Promise<void> {
    await SpeechKey.clear();
    await this.readKey();
  }

  private async pickFolder(): Promise<void> {
    this.folderError = '';
    try {
      await LocalFolder.shared.choose(this.context());
      this.folder = LocalFolder.shared.displayPath();
    } catch (e) {
      this.folderError = (e as Error).message;
    }
  }

  private async forgetFolder(): Promise<void> {
    await LocalFolder.shared.forget();
    this.folder = LocalFolder.shared.displayPath();
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
  Problem(text: string) {
    if (text.length > 0) {
      Text(text)
        .fontSize(13)
        .fontColor($r('sys.color.warning'))
        .padding({ left: 20, right: 20, top: 8 })
    }
  }

  @Builder
  Folder() {
    this.Heading('文件夹')
    if (FolderPicker.supported()) {
      HdsListItemCard({
        cardBackgroundColor: $r('app.color.leaf_raised'),
        textItem: {
          primaryText: { text: this.folder.length > 0 ? this.folder : '未选择文件夹' },
          secondaryText: { text: this.folder.length > 0 ? '更改…' : '选择文件夹…' },
        },
        suffixItem: new SuffixArrow(),
        onClick: () => {
          this.pickFolder();
        },
      })
      this.Footnote('每篇随笔保存为一个 Markdown 文件，可用其他编辑器打开。在别处改动后，Leaf 会读回来。')
      this.Problem(this.folderError)
      if (this.folder.length > 0) {
        Column() {
          HdsListItemCard({
            cardBackgroundColor: $r('app.color.leaf_raised'),
            textItem: {
              primaryText: { text: '停止保存到文件夹', modifier: new TextModifier().fontColor($r('sys.color.warning')) },
            },
            onClick: () => {
              this.forgetFolder();
            },
          })
        }
        .margin({ top: 12 })
      }
    } else {
      this.Footnote('仅在电脑上可选择文件夹。随笔保存在本机，可同步到 Notion。')
    }
  }

  @Builder
  Notion() {
    this.Heading('Notion')
    if (this.connected) {
      HdsListItemCard({
        cardBackgroundColor: $r('app.color.leaf_raised'),
        textItem: {
          primaryText: { text: this.database },
          secondaryText: { text: this.workspace },
        },
      })
      this.Footnote('随笔始终先保存在本机。打开的随笔会随时上传，其他随笔在后台同步。')
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
      .margin({ top: 12 })
    } else if (this.sources.length > 0) {
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
      this.Footnote('选择随笔数据库。')
    } else {
      Column({ space: 12 }) {
        Button(this.waiting ? '正在等待 Notion…' : (this.busy ? '正在查找数据库…' : '使用 Notion 登录'))
          .width('100%')
          .backgroundColor($r('app.color.leaf_green'))
          .fontColor($r('app.color.leaf_on_green'))
          .enabled(!this.waiting && !this.busy)
          .onClick(() => this.signIn())
        if (this.waiting) {
          Button('取消', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .onClick(() => this.cancelSignIn())
        }
      }
      .padding(16)
      .borderRadius(20)
      .backgroundColor($r('app.color.leaf_raised'))
      // As wide as the HDS cards around it, which keep a margin of their own.
      .margin({ left: 24, right: 24 })
      this.Footnote('在浏览器中登录 Notion，并允许 Leaf 访问随笔数据库。未连接时，随笔只保存在本机。')
    }
    this.Problem(this.error)
  }

  @Builder
  Voice() {
    this.Heading('语音输入')
    if (this.keyMasked.length > 0) {
      HdsListItemCard({
        cardBackgroundColor: $r('app.color.leaf_raised'),
        textItem: {
          primaryText: { text: 'ElevenLabs 密钥' },
          secondaryText: { text: this.keyMasked },
        },
      })
      Column() {
        HdsListItemCard({
          cardBackgroundColor: $r('app.color.leaf_raised'),
          textItem: {
            primaryText: { text: '移除密钥', modifier: new TextModifier().fontColor($r('sys.color.warning')) },
          },
          onClick: () => {
            this.removeKey();
          },
        })
      }
      .margin({ top: 12 })
    } else {
      Column({ space: 12 }) {
        TextInput({ text: this.keyInput, placeholder: 'ElevenLabs API 密钥' })
          .type(InputType.Password)
          .caretColor($r('app.color.leaf_ink'))
          .onChange((v: string) => {
            this.keyInput = v;
          })
        Button('保存')
          .width('100%')
          .backgroundColor($r('app.color.leaf_green'))
          .fontColor($r('app.color.leaf_on_green'))
          .enabled(this.keyInput.trim().length > 0)
          .onClick(() => this.saveKey())
      }
      .padding(16)
      .borderRadius(20)
      .backgroundColor($r('app.color.leaf_raised'))
      // As wide as the HDS cards around it, which keep a margin of their own.
      .margin({ left: 24, right: 24 })
    }
    this.Footnote('语音会发送到 ElevenLabs 转写成文字。密钥只保存在本机。')
    this.Problem(this.keyError)
  }

  build() {
    Scroll() {
      Column() {
        this.Folder()
        this.Notion()
        this.Voice()
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
