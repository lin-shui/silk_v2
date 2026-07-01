/**
 * SectionHeader - section title header
 */
@Component
export struct SectionHeader {
  @Prop title: string = '';
  @Prop color: ResourceStr = '#000';

  build() {
    Row() {
      Text(this.title)
        .fontSize(12)
        .fontColor(this.color)
        .fontWeight(FontWeight.Bold)
        .letterSpacing(1)
      Blank()
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ top: 6, bottom: 4, left: 4 })
  }
}
