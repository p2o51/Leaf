import { common } from '@kit.AbilityKit';
import { inputDevice } from '@kit.InputKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { Library } from './Library';
import { SyncEngine } from './SyncEngine';

/** AppStorage key: true while Leaf should use 电脑模式 (the 写作台), false for 平板模式. */
export const DESK_MODE_KEY: string = 'leaf.deskMode';

/**
 * The app's single library and sync engine, opened once by EntryAbility. Pages reach them through here
 * (as the Android screens reach LeafApplication.container).
 */
export class LeafApp {
  static readonly lib: Library = new Library();
  static readonly sync: SyncEngine = new SyncEngine(LeafApp.lib);
  private static opening: Promise<void> | null = null;

  static open(context: common.Context): Promise<void> {
    if (LeafApp.opening === null) {
      LeafApp.opening = LeafApp.lib.open(context);
    }
    return LeafApp.opening;
  }

  static ready(): Promise<void> {
    return LeafApp.opening ?? Promise.resolve();
  }
}

/**
 * 电脑模式 vs 平板模式 (51's rule, 2026-11-08): a hardware keyboard attached means the 写作台; none means
 * the touch-first block editor. A 2-in-1 (HarmonyOS PC such as the MateBook) always has its keyboard, so
 * it starts in 电脑模式; a tablet switches live when its keyboard cover is attached or taken off.
 */
export class KeyboardWatch {
  private static started: boolean = false;

  static async start(): Promise<void> {
    AppStorage.setOrCreate<boolean>(DESK_MODE_KEY, deviceInfo.deviceType === '2in1');
    await KeyboardWatch.refresh();
    if (!KeyboardWatch.started) {
      KeyboardWatch.started = true;
      inputDevice.on('change', () => {
        KeyboardWatch.refresh();
      });
    }
  }

  static stop(): void {
    if (KeyboardWatch.started) {
      inputDevice.off('change');
      KeyboardWatch.started = false;
    }
  }

  private static async refresh(): Promise<void> {
    let keyboard = deviceInfo.deviceType === '2in1';
    try {
      const ids: number[] = await inputDevice.getDeviceList();
      for (const id of ids) {
        try {
          const type = await inputDevice.getKeyboardType(id);
          if (type === inputDevice.KeyboardType.ALPHABETIC_KEYBOARD) {
            keyboard = true;
            break;
          }
        } catch (e) {
          // Not a keyboard, or gone between listing and asking.
        }
      }
    } catch (e) {
      // Leave the device-type default.
    }
    AppStorage.setOrCreate<boolean>(DESK_MODE_KEY, keyboard);
  }
}
