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

export function fontLabel(font: DeskFont): string {
  switch (font) {
    case DeskFont.Sans:
      return 'Sans';
    case DeskFont.Mono:
      return 'Mono';
    default:
      return 'Serif';
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
}

/** A card's paper colour, from the theme resources (light and dark sets). */
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
      return $r('app.color.card_default');
  }
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

const LIGHT: Palette = new Palette('17200E', '56624A', '3A3FCB');
const DARK: Palette = new Palette('E2E6D6', 'C2C9B4', 'BEC2FF');

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
