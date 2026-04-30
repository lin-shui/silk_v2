/**
 * 思考过程面板组件
 * 可折叠的 AI 思考过程展示卡片，类似元宝的"思考过程"
 */
import { Theme, SilkColors } from '../common/Theme';

@Component
export struct ThinkingPanel {
  @Prop thinking: string = '';
  @State isExpanded: boolean = false;

  build() {
    if (!this.thinking || this.thinking.trim().length === 0) {
      // 空内容不渲染
    } else {
      Column() {
        // 折叠/展开标题行
        Row() {
          Text('💭')
            .fontSize(14)
          Text(' 思考过程')
            .fontSize(13)
            .fontColor(SilkColors.textSecondary)
            .fontWeight(FontWeight.Medium)

          Blank()

          Row() {
            Text(this.isExpanded ? '收起 ▲' : '展开 ▼')
              .fontSize(12)
              .fontColor(SilkColors.primary)
          }
          .padding({ left: 10, right: 10, top: 4, bottom: 4 })
          .borderRadius(4)
          .onClick(() => {
            this.isExpanded = !this.isExpanded;
          })
        }
        .width('100%')
        .padding({ left: 4, right: 4, top: 6, bottom: 6 })

        // 展开的思考内容
        if (this.isExpanded) {
          Column() {
            Divider()
              .color('#E8E0D4')
              .strokeWidth(0.5)

            // 思考内容（引用块样式渲染）
            Text(this.thinking)
              .fontSize(13)
              .fontColor(SilkColors.textSecondary)
              .lineHeight(20)
              .width('100%')
              .padding({ left: 8, right: 8, top: 8, bottom: 8 })
              .constraintSize({ maxHeight: 300 })
          }
          .width('100%')
        }
      }
      .width('100%')
      .backgroundColor('#FFFBF0')
      .borderRadius(8)
      .border({
        width: 1,
        color: '#E8E0D4',
        style: BorderStyle.Solid
      })
      .margin({ top: 4, bottom: 4 })
    }
  }
}
