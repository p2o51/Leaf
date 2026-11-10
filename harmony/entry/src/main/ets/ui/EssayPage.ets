import { DESK_MODE_KEY } from '../data/LeafApp';
import { DeskEditor } from './DeskEditor';
import { TabletEditor } from './TabletEditor';

/**
 * One essay. 电脑模式 (keyboard attached) opens the 写作台, 平板模式 the block editor; either can hand this
 * essay to the other for this visit (「用平板模式编辑这一篇」 / 「Markdown」), as on Android.
 */
@Component
export struct EssayPage {
  essayId: string = '';
  onBack: () => void = () => {};
  /** Beside the essay list (wide windows), so the editor shows no back button. */
  embedded: boolean = false;
  @StorageProp(DESK_MODE_KEY) deskMode: boolean = false;
  /** null follows the keyboard; true / false is this visit's own choice. */
  @State override: boolean | null = null;

  /** A method, not a getter: the ArkUI transform drops getters declared in a struct. */
  private desk(): boolean {
    return this.override ?? this.deskMode;
  }

  build() {
    if (this.desk()) {
      DeskEditor({
        essayId: this.essayId,
        embedded: this.embedded,
        onBack: () => this.onBack(),
        onTabletMode: () => {
          this.override = false;
        },
      })
    } else {
      TabletEditor({
        essayId: this.essayId,
        embedded: this.embedded,
        onBack: () => this.onBack(),
        onDeskMode: () => {
          this.override = true;
        },
      })
    }
  }
}
