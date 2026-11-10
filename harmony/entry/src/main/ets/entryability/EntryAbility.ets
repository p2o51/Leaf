import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { KeyboardWatch, LeafApp } from '../data/LeafApp';
import { WindowChrome } from '../ui/WindowChrome';
import { NotionOAuth } from '../data/NotionOAuth';
import { LocalFolder } from '../data/LocalFolder';

const DOMAIN: number = 0x4C46;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    LeafApp.open(this.context).catch((e: Error) => {
      hilog.error(DOMAIN, 'Leaf', 'open failed: %{public}s', e.message);
    });
    NotionOAuth.deliver(want.uri ?? '');
  }

  /** 用 Notion 登录 comes back here: the browser opens leaf://notion-oauth while Leaf is running. */
  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    NotionOAuth.deliver(want.uri ?? '');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    KeyboardWatch.start();
    WindowChrome.measure(windowStage);
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, 'Leaf', 'loadContent failed: %{public}s', JSON.stringify(err));
        return;
      }
      WindowChrome.apply(windowStage);
    });
  }

  onWindowStageDestroy(): void {
    KeyboardWatch.stop();
  }

  onForeground(): void {
    // The rest of the library syncs slowly in the background while Leaf is in front.
    LeafApp.ready().then(() => {
      LeafApp.sync.startBackground();
      LocalFolder.shared.resume();
    });
  }

  onBackground(): void {
    LeafApp.sync.stopBackground();
    LocalFolder.shared.pause();
    // Whatever is still queued goes out now rather than waiting for the next launch.
    LeafApp.sync.run(false);
  }
}
