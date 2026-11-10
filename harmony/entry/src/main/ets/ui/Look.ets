import { NotionColor } from '../core/Models';

/** The 写作台's three writing faces, all IBM Plex (iA's own fonts ask not to be built into editors). */
export enum DeskFont {
  Serif = 'serif',
  Sans = 'sans',
  Mono = 'mono',
}

export const PLEX_SERIF: string = 'IBM Plex Serif';
export const PLEX_SANS: string = 'IBM Plex Sans';
export const PLEX_MONO: string = 'IBM Plex Mono';
export const PLEX_SANS_SEMIBOLD: string = 'IBM Plex Sans SemiBold';
export const PLEX_SERIF_SEMIBOLD: string = 'IBM Plex Serif SemiBold';

/** Chinese falls back to the system's HarmonyOS Sans SC after the Plex face, as Android falls back to Noto CJK. */
export function familyOf(font: DeskFont): string {
  switch (font) {
    case DeskFont.Sans:
      return `${PLEX_SANS}, HarmonyOS Sans SC`;
    case DeskFont.Mono:
      return `${PLEX_MONO}, HarmonyOS Sans SC`;
    default:
      return `${PLEX_SERIF}, HarmonyOS Sans SC`;
  }
}

/** As the Mac desk names them in its font menu. */
export function fontLabel(font: DeskFont): string {
  switch (font) {
    case DeskFont.Sans:
      return '无衬线';
    case DeskFont.Mono:
      return '等宽';
    default:
      return '衬线';
  }
}

/** Font size and line height (vp) per face, matching Android's deskStyle. */
export function sizeOf(font: DeskFont): number {
  return font === DeskFont.Mono ? 17 : 18;
}

export function lineHeightOf(font: DeskFont): number {
  return font === DeskFont.Mono ? 31 : 32;
}

/** Registers the bundled Plex faces once per window, before any page uses them. */
export function registerFonts(ui: UIContext): void {
  const f = ui.getFont();
  f.registerFont({ familyName: PLEX_SERIF, familySrc: $rawfile('fonts/ibm_plex_serif_400.ttf') });
  f.registerFont({ familyName: PLEX_SANS, familySrc: $rawfile('fonts/ibm_plex_sans_400.ttf') });
  f.registerFont({ familyName: PLEX_MONO, familySrc: $rawfile('fonts/ibm_plex_mono_400.ttf') });
  f.registerFont({ familyName: PLEX_SANS_SEMIBOLD, familySrc: $rawfile('fonts/ibm_plex_sans_600.ttf') });
  f.registerFont({ familyName: PLEX_SERIF_SEMIBOLD, familySrc: $rawfile('fonts/ibm_plex_serif_600.ttf') });
}

/** Reading type for card titles and previews, as Android sets them: serif, Chinese in the system face. */
export function readingFamily(semibold: boolean): string {
  return semibold ? `${PLEX_SERIF_SEMIBOLD}, HarmonyOS Sans SC` : familyOf(DeskFont.Serif);
}

/** A card's date line, as on Android: 11月9日. */
export function dayLabel(ms: number): string {
  const d = new Date(ms);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

/** A card's colour: Notion's colour toned per theme, as on Android; a plain card is Leaf's own green-grey. */
export function cardColor(color: NotionColor): Resource {
  switch (color.apiName) {
    case 'gray':
      return $r('app.color.card_gray');
    case 'brown':
      return $r('app.color.card_brown');
    case 'orange':
      return $r('app.color.card_orange');
    case 'yellow':
      return $r('app.color.card_yellow');
    case 'green':
      return $r('app.color.card_green');
    case 'blue':
      return $r('app.color.card_blue');
    case 'purple':
      return $r('app.color.card_purple');
    case 'pink':
      return $r('app.color.card_pink');
    case 'red':
      return $r('app.color.card_red');
    default:
      return $r('app.color.leaf_card');
  }
}

/**
 * A colour as a small mark (the sidebar's dots, a list row's edge): Notion's own light-mode ink, the values the
 * Mac app uses, readable on both papers. Plain essays have no mark.
 */
export function swatchColor(color: NotionColor): ResourceColor {
  switch (color.apiName) {
    case 'gray':
      return '#78756F';
    case 'brown':
      return '#9E6B54';
    case 'orange':
      return '#D9730D';
    case 'yellow':
      return '#CC9121';
    case 'green':
      return '#458266';
    case 'blue':
      return '#337DAB';
    case 'purple':
      return '#8F66AB';
    case 'pink':
      return '#C2548A';
    case 'red':
      return '#D44D42';
    default:
      return Color.Transparent;
  }
}

/** One parameter object, so ArkUI passes it by reference and the button follows later changes. */
export interface IconButtonParams {
  icon: Resource;
  label: string;
  action: () => void;
}

/** A title-bar icon button as HarmonyOS draws them: a 40vp circle on the tertiary fill, a system symbol. */
@Builder
export function IconButton(p: IconButtonParams) {
  Button({ type: ButtonType.Circle, stateEffect: true }) {
    SymbolGlyph(p.icon).fontSize(20).fontColor([$r('sys.color.icon_primary')])
  }
  .width(40)
  .height(40)
  .backgroundColor($r('sys.color.comp_background_tertiary'))
  .accessibilityText(p.label)
  .onClick(() => p.action())
}

/** How faded the words outside the focus are, as iA does it: still legible, clearly not now. */
export const FADED_ALPHA: number = 0.28;

/** Edits are written to the library this long after the last keystroke (and so queued for Notion). */
export const WRITE_AFTER_MS: number = 600;
/** The open essay is fetched again this often while it is on screen and the pen has rested. */
export const REFRESH_EVERY_MS: number = 30000;
export const REFRESH_REST_MS: number = 5000;

/** The 写作台's ink in both colour modes (the theme resources cannot carry the faded alphas). */
class Palette {
  readonly text: string;
  readonly quiet: string;
  readonly pasted: string;

  constructor(text: string, quiet: string, pasted: string) {
    this.text = text;
    this.quiet = quiet;
    this.pasted = pasted;
  }
}

// The system's font_primary and font_secondary, flattened onto the page so the faded alphas can apply.
const LIGHT: Palette = new Palette('1A1A1A', '666666', '3A3FCB');
const DARK: Palette = new Palette('E5E5E5', '999999', 'BEC2FF');

function withAlpha(rgb: string, alpha: number): string {
  const a = Math.round(Math.min(Math.max(alpha, 0), 1) * 255).toString(16).padStart(2, '0').toUpperCase();
  return `#${a}${rgb}`;
}

/** The colour for one look segment: [role] is the core Ink value, [faded] outside the focus. */
export function inkColor(role: string, faded: boolean, dark: boolean): string {
  const p = dark ? DARK : LIGHT;
  const k = faded ? FADED_ALPHA : 1;
  switch (role) {
    case 'pasted':
      return withAlpha(p.pasted, k);
    case 'mark':
      return withAlpha(p.quiet, 0.55 * k);
    case 'struck':
      return withAlpha(p.quiet, 0.6 * k);
    default:
      return withAlpha(p.text, k);
  }
}

export function quietColor(dark: boolean, alpha: number): string {
  return withAlpha((dark ? DARK : LIGHT).quiet, alpha);
}
