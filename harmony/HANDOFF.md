# Leaf 鸿蒙版 · 转接文档

给 51 和接手的本地 Claude Code 线程。云端写好了第一版代码，但云端连不上华为的服务器（developer.huawei.com、ohpm、SDK 镜像都被网络拦了），所以**一次都没编译过**。本地线程的任务：在 51 的 Mac 上编过、签名、装到 MateBook（和平板/手机）上跑通，修掉第一次编译冒出来的错。

## 1. 这一版有什么

一套 ArkTS/ArkUI 代码，`deviceTypes` = phone、tablet、2in1（鸿蒙电脑），电脑优先。产品和安卓 Leaf 一样：随笔 + 标签，存在 51 的 Notion 数据库里，没有单独的「文稿」。

- **首页**：随笔卡片瀑布流（Notion 卡片颜色），左侧栏 全部 / 颜色 / #标签（宽窗口常驻，窄窗口抽屉）。右键或长按卡片：置顶、卡片颜色、删除（进 Notion 回收站）。右下角 ＋ 新建（Ctrl+N）。不显示任何数字、角标、连续天数。
- **电脑模式 = 写作台**（接了键盘；2in1 默认就是）：整篇 Markdown 一栏居中，照搬安卓 PR #15：专注（全文 / 句 / 段，其余淡到 0.28）、打字机、IBM Plex Serif/Sans/Mono、文风（中文填充词、程度副词、「的」过多、长句、半角标点，只标不改）、来源（粘贴的字换色，只存本机）。工具条打字时淡出，动鼠标回来，Esc 返回。⋯ 菜单里有文风规则开关和「用平板模式编辑这一篇」。
- **平板模式**（没键盘）：按块编辑，长按块左侧把手拖动排序（Notion 没有移动，外发队列在新位置重写一块再删旧块），点把手改块类型 / 加一段 / 删除。右上「Markdown」切到写作台。
- **本地优先**：打字只写本机库（RDB）；停笔 0.6 秒落库并排进外发队列，队列 1.5 秒后推；打开的那篇在停笔 5 秒后每 30 秒重拉一次，重拉时滤掉队列里还没发出的已删块；其余随笔 App 在前台时每 10 分钟后台拉一次。
- **连接 Notion**：设置里「使用 Notion 登录」→ 浏览器里授权（经 notion-oauth-relay，回调 `leaf://notion-oauth`，和安卓同一个）→ 选数据库 → 自动补「类型 / 卡片颜色 / 置顶」字段。令牌存 Asset Store（硬件加密，只本应用可读），过期自动刷新。以前贴的内部集成密钥照样能用。

**这一版没有**（之后再做）：涂鸦墙（只认出那一行、不当随笔显示）、照片和封面上传、用 Notion 登录（OAuth）、按住说、服务卡片、桌面多窗口。

## 2. 51 要准备的

1. 华为开发者账号（免费，要实名）。
2. Mac 上装 **DevEco Studio** 最新正式版（带 HarmonyOS SDK，API 20 以上：HDS 要 HarmonyOS 6.0），用账号登录一次，让它把 SDK 下全。
3. MateBook：设置 › 关于本机 › 连点版本号开开发者模式，再在开发者选项里打开 USB 调试 / 无线调试。
4. Notion：不用准备什么，App 里「使用 Notion 登录」，在授权页勾上随笔数据库。

## 3. 本地线程怎么做

仓库 p2o51/Leaf，分支 `claude/project-thread-rcguty`（PR 见 GitHub）。代码都在 `harmony/`。

```bash
# 0. 共用逻辑测试（不需要 DevEco，云端和 CI 已经跑过：40 个全过 + ArkTS 规则检查 0 问题）
cd harmony/tools/core-test && npm ci && npm test

# 1. DevEco 自带的工具（路径按实际安装位置改）
DEVECO="/Applications/DevEco-Studio.app/Contents"
export PATH="$DEVECO/tools/ohpm/bin:$DEVECO/tools/hvigor/bin:$DEVECO/tools/node/bin:$DEVECO/sdk/default/openharmony/toolchains:$PATH"
export DEVECO_SDK_HOME="$DEVECO/sdk"

# 2. 依赖和编译
cd harmony
ohpm install
hvigorw assembleHap --mode module -p module=entry@default -p product=default -p buildMode=debug --no-daemon
```

**签名**：第一次要在 DevEco 里打开 `harmony/` 工程，File › Project Structure › Signing Configs，勾 Automatically generate signature（登录状态下自动申请调试证书）。它会改 `build-profile.json5` 的 `signingConfigs` 并把证书放在 `~/.ohos/config/`。**这些签名改动不要提交**（`.gitignore` 已经挡住证书文件；build-profile.json5 的改动提交前要还原）。

**装机**：

```bash
hdc list targets                      # USB 连 MateBook；无线：hdc tconn <IP>:<端口>
hdc install -r entry/build/default/outputs/default/entry-default-signed.hap
hdc shell aa start -a EntryAbility -b dev.chenwuyi.leaf
hdc hilog | grep -i leaf              # 日志域 0x4C46，tag Leaf
```

不卸载重装（会丢本机库和 Asset Store 里的密钥）；需要换签名时先跟 51 说。

## 4. 第一次编译多半要修的地方

代码按 API 12 写，但没有 SDK 校验过。下面这些是我最没把握的，编译报错先看这里，按 SDK 实际签名改，别改行为：

- `data/NotionApi.ets` 用 **Remote Communication Kit**（`rcp.createSession`、`new rcp.Request(url, method, headers, body)`、`response.toString()`、`response.headers['retry-after']`）。换它是因为 `@kit.NetworkKit` 的 `http.RequestMethod` 没有 PATCH，而 Notion 改块、改页都用 PATCH。`rcp.RequestHeaders` 里写自定义的 `notion-version` 如果类型不让，用先建空对象再按键赋值的写法。
- `ui/DeskEditor.ets` 的 RichEditor：`onDidChange`、`onSelectionChange`、`onEditingChange`、`placeholder`、`getLayoutManager().getLineMetrics(i).topHeight`（打字机定位用）、`updateSpanStyle` 的 `lineHeight` / `decoration`。长句在安卓上是浅底色，这里先用淡下划线（RichEditor 的 span 在 API 12 没有背景色）。
- `ui/TabletEditor.ets` 的 `ForEach(...).onMove`（List 拖动排序，API 12）。
- `pages/Index.ets` 的 `MenuItem({ content, builder })` 二级菜单、`bindContextMenu` 同时绑长按和右键、`getHostContext()?.resourceManager.getConfigurationSync().colorMode`。
- `data/LeafDb.ets` 的 `querySqlSync` / `executeSync` / `insertSync`（API 12 的同步接口，用在事务里）。
- `data/LeafApp.ets` 的 `inputDevice.getKeyboardType` 和 `inputDevice.on('change')`。
- ArkTS 严格检查可能会抱怨个别 lambda 参数没写类型，补上即可。`core/` 下的文件已经过 ArkTS 规则检查，不用动。

## 5. 验收（在 MateBook 上，再在平板上各过一遍）

1. 首次打开：空白首页「写在这里，存进你的 Notion」；设置里贴密钥 → 找数据库 → 选随笔库 → 首页出现随笔卡片，涂鸦墙那一行不出现。
2. 左栏点颜色 / 标签能筛选；右键卡片能置顶、改颜色，几秒后 Notion 里跟着变。
3. MateBook 上打开一篇 → 写作台。打字流畅；`## ` 变标题（Notion 里变 heading_2）；空一行出新段落；`#东京` 保持正文。停笔几秒 Notion 里出现改动，块没有重复。
4. 专注 句 / 段 淡化正确；打字机让当前行停在窗口中间偏上；三种字体切换；文风划掉「其实」「非常」；粘贴一段文字后开来源，粘贴的字换色，退出再进还在。
5. 在 Notion 网页里改这篇的某一段，30 秒内写作台跟着变（光标所在的那段没被打断）。
6. 断网写几段，联网后自动推上去，顺序正确。
7. 平板（拔掉键盘）：同一篇变成按块编辑，拖动一块换位置，Notion 里位置跟着变；改块类型；「Markdown」切回写作台。
8. 性能：5 万字长文里打字不卡（Mac 小样 p99 3.4 ms；鸿蒙这边如果卡，先查 `DeskRun.restyle()`，它每次按键对整段重新上样式，可以改成只更新专注范围变化的部分）。

把截图（浅色、深色各一套）、哪些项过了、改了什么，回报到 Leaf PR 里。

## 6. 之后的事

- 涂鸦墙（只读 → 可写）、照片和封面上传（Notion File Upload，rcp 的 multipart）、按住说（ElevenLabs Scribe）。
- 服务卡片、快捷输入；2in1 上的多窗口和菜单栏。
- 和安卓共用的验收用例：`harmony/tools/core-test/test/DeskText.test.ts` 就是安卓 `DeskTextTest.kt` 一条对一条，两边改规则时一起改。

## 7. 仓库约定

- 只在 `claude/project-thread-*` 分支上提交，PR 合到 main；不 force push 别人的分支。
- 提交信息照仓库现有风格：`feat: …` / `fix: …`，英文小写。
- 密钥、证书、Notion token 不进仓库、日志和截图。
