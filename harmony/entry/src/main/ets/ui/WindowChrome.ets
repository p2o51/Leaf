import { display, window } from '@kit.ArkUI';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

/** AppStorage key: vp a page leaves free at its top for the window's caption row (0: the system draws one). */
export const CAPTION_TOP_KEY: string = 'leaf.captionTop';
/** AppStorage key: vp a page leaves free at the right end of its caption row for the window buttons. */
export const CAPTION_RIGHT_KEY: string = 'leaf.captionRight';
/** AppStorage key: the main window's width (vp) when it opens, so home lays out for it from its first frame. */
export const WINDOW_WIDTH_KEY: string = 'leaf.windowWidth';

/** As tall as an HDS title bar's top row, so the window buttons sit on its line. */
const CAPTION_HEIGHT: number = 56;
const DOMAIN: number = 0x4C46;

/**
 * On a 2-in-1 the window has no title bar of its own: Leaf's pages run up to the top edge and the system's
 * minimize / maximize / close buttons float on Leaf's own background, as native HarmonyOS PC apps do.
 * Each page keeps its caption row clear of the buttons and lets its empty part move the window.
 */
export class WindowChrome {
  private static win: window.Window | null = null;

  /** Call before the content loads: home's first frame then already knows whether its sidebar fits beside it. */
  static measure(stage: window.WindowStage): void {
    try {
      const px = stage.getMainWindowSync().getWindowProperties().windowRect.width;
      const density = display.getDefaultDisplaySync().densityPixels;
      AppStorage.setOrCreate<number>(WINDOW_WIDTH_KEY, density > 0 ? px / density : 0);
    } catch (e) {
      hilog.warn(DOMAIN, 'Leaf', 'window width unknown: %{public}s', JSON.stringify(e));
    }
  }

  /** Call once the main window's content is loaded (the title bar can only be hidden after that). */
  static apply(stage: window.WindowStage): void {
    AppStorage.setOrCreate<number>(CAPTION_TOP_KEY, 0);
    AppStorage.setOrCreate<number>(CAPTION_RIGHT_KEY, 0);
    if (deviceInfo.deviceType !== '2in1') {
      return;
    }
    try {
      const win = stage.getMainWindowSync();
      WindowChrome.win = win;
      win.setWindowDecorVisible(false);
      win.setWindowDecorHeight(CAPTION_HEIGHT);
      WindowChrome.readButtons(win.getTitleButtonRect());
      win.on('windowTitleButtonRectChange', (r: window.TitleButtonRect) => WindowChrome.readButtons(r));
      // The system's own maximize button makes the window immersive (status bar and dock hidden); a writing
      // app keeps them, so a full-screen window is turned into a plain maximized one.
      win.on('windowStatusChange', (status: window.WindowStatusType) => {
        if (status === window.WindowStatusType.FULL_SCREEN) {
          win.maximize(window.MaximizePresentation.EXIT_IMMERSIVE)
            .catch((err: Error) => WindowChrome.log('maximize', err));
        }
      });
      AppStorage.setOrCreate<number>(CAPTION_TOP_KEY, CAPTION_HEIGHT);
    } catch (e) {
      WindowChrome.log('apply', e as Error);
    }
  }

  /** For a caption row's empty part: pressing there moves the window, as a title bar would. */
  static drag(e: TouchEvent): void {
    if (WindowChrome.win !== null && e.type === TouchType.Down) {
      WindowChrome.win.startMoving().catch((err: Error) => WindowChrome.log('startMoving', err));
    }
  }

  /** Double-clicking a caption row maximizes the window, or puts it back. */
  static toggleMaximize(): void {
    const win = WindowChrome.win;
    if (win === null) {
      return;
    }
    try {
      // On the MateBook maximize() leaves the window FULL_SCREEN rather than MAXIMIZE; both go back.
      const status = win.getWindowStatus();
      if (status === window.WindowStatusType.MAXIMIZE || status === window.WindowStatusType.FULL_SCREEN) {
        win.recover().catch((err: Error) => WindowChrome.log('recover', err));
      } else {
        // Not immersive: the status bar and the dock stay, as a maximized window keeps them on HarmonyOS PC.
        win.maximize(window.MaximizePresentation.EXIT_IMMERSIVE).catch((err: Error) => WindowChrome.log('maximize', err));
      }
    } catch (e) {
      WindowChrome.log('toggleMaximize', e as Error);
    }
  }

  private static readButtons(r: window.TitleButtonRect): void {
    // The rect is measured from the window's top-right corner; keep a little air to its left too.
    AppStorage.setOrCreate<number>(CAPTION_RIGHT_KEY, r.right + r.width + 12);
  }

  private static log(what: string, e: Error): void {
    hilog.warn(DOMAIN, 'Leaf', 'window chrome %{public}s: %{public}s', what, e.message);
  }
}

/**
 * A see-through strip over the top of an HDS title bar, which takes no touch handlers of its own: pressing it
 * moves the window and a double-click maximizes it. [startInset] and [endInset] keep it off the bar's own
 * buttons (back on the left, the menu on the right).
 */
@Component
export struct CaptionStrip {
  @Prop startInset: number = 0;
  @Prop endInset: number = 0;
  @StorageProp(CAPTION_TOP_KEY) captionTop: number = 0;
  @StorageProp(CAPTION_RIGHT_KEY) captionRight: number = 0;

  build() {
    if (this.captionTop > 0) {
      Row() {
        Row()
          .width(this.startInset)
          .height('100%')
          .hitTestBehavior(HitTestMode.None)
        Row()
          .layoutWeight(1)
          .height('100%')
          .onTouch((e: TouchEvent) => WindowChrome.drag(e))
          .gesture(TapGesture({ count: 2 }).onAction(() => WindowChrome.toggleMaximize()))
        Row()
          .width(this.captionRight + this.endInset)
          .height('100%')
          .hitTestBehavior(HitTestMode.None)
      }
      .width('100%')
      .height(this.captionTop)
      .hitTestBehavior(HitTestMode.Transparent)
    }
  }
}
