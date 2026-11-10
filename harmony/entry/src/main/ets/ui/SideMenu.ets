/** One sidebar entry: a label, and a system symbol, a text glyph (# for a tag) or, for a colour, a dot. */
export interface SideEntry {
  label: string;
  symbol: Resource;
  /** Drawn instead of [symbol]: the tags' #, as the Mac sidebar marks them. */
  glyph?: string;
  /** The colour this entry filters by; drawn as a dot instead of [symbol]. */
  dot?: ResourceColor;
  /** A heading drawn above this entry where a new group starts (颜色, 标签), as on the Mac and Android. */
  group?: string;
}

/**
 * The sidebar's filters, laid out as the Mac app's sidebar: 全部随笔, then 颜色 and 标签 under small headings,
 * 36vp rows, a quiet fill on the chosen one (the lime is kept for the open essay in the list). HdsSideMenu is
 * not used: on the MateBook it sizes its list to 40vp per item, so the last of them scrolls out of sight.
 */
@Component
export struct SideMenu {
  @Prop entries: SideEntry[] = [];
  @Prop selected: number = 0;
  onPick: (index: number) => void = () => {};
  @State private hovered: number = -1;

  @Builder
  Heading(text: string) {
    Text(text)
      .fontSize(12)
      .fontWeight(FontWeight.Medium)
      .fontColor($r('app.color.leaf_text_2'))
      .padding({ left: 10, top: 14, bottom: 4 })
      .accessibilityLevel('yes')
  }

  @Builder
  Item(e: SideEntry, index: number) {
    Row() {
      Row() {
        if (e.dot !== undefined) {
          Circle()
            .width(10)
            .height(10)
            .fill(e.dot)
        } else if (e.glyph !== undefined) {
          Text(e.glyph)
            .fontSize(17)
            .fontColor($r('app.color.leaf_text_2'))
        } else {
          SymbolGlyph(e.symbol)
            .fontSize(18)
            .fontColor([$r('app.color.leaf_text_2')])
        }
      }
      .width(32)
      .justifyContent(FlexAlign.Center)
      Text(e.label)
        .fontSize(15)
        .fontWeight(index === this.selected ? FontWeight.Medium : FontWeight.Regular)
        .fontColor($r('app.color.leaf_text'))
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
    }
    .width('100%')
    .height(36)
    .padding({ left: 4, right: 10 })
    .borderRadius(10)
    .backgroundColor(index === this.selected ? $r('app.color.leaf_card')
      : (index === this.hovered ? $r('sys.color.interactive_hover') : Color.Transparent))
    .onHover((on: boolean) => {
      this.hovered = on ? index : (this.hovered === index ? -1 : this.hovered);
    })
    .onClick(() => this.onPick(index))
    .accessibilityGroup(true)
    .accessibilityText(e.label)
    .accessibilitySelected(index === this.selected)
  }

  build() {
    List({ space: 2 }) {
      ForEach(this.entries, (e: SideEntry, index: number) => {
        ListItem() {
          Column() {
            if (e.group !== undefined && e.group.length > 0) {
              this.Heading(e.group)
            }
            this.Item(e, index)
          }
          .alignItems(HorizontalAlign.Start)
        }
      }, (e: SideEntry, index: number) => `${index}:${e.group ?? ''}:${e.label}`)
    }
    .width('100%')
    .height('100%')
    .padding({ left: 10, right: 10, bottom: 8 })
    .scrollBar(BarState.Auto)
    .edgeEffect(EdgeEffect.None)
  }
}
