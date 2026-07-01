/**
 * 思考过程面板组件（含计时器）
 * 可折叠的 AI 思考过程展示卡片，类似元宝的"思考过程"
 * 在思考进行中显示 "Thinking Xs..."，完成后显示 "Thought for Xs"。
 */
import { Theme, SilkColors } from '../common/Theme';

@Component
export struct ThinkingPanel {
  @Prop thinking: string = '';
  @Prop isComplete: boolean = false;
  @State isExpanded: boolean = false;
  @State elapsedSeconds: number = 0;
  private startTime: number = Date.now();
  private timerId: number = -1;

  aboutToAppear(): void {
    this.startTime = Date.now();
    if (!this.isComplete) {
      this.timerId = setInterval(() => {
        this.elapsedSeconds = Math.floor((Date.now() - this.startTime) / 1000);
      }, 1000);
    }
  }

  aboutToDisappear(): void {
    if (this.timerId !== -1) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  /**
   * 当 isComplete 变为 true 时停止计时器并记录最终时间
   */
  onIsCompleteChange(): void {
    if (this.isComplete) {
      if (this.timerId !== -1) {
        clearInterval(this.timerId);
        this.timerId = -1;
      }
      this.elapsedSeconds = Math.floor((Date.now() - this.startTime) / 1000);
    }
  }

  build() {
    // 监听 isComplete 变化
    if (this.isComplete && this.timerId !== -1) {
      clearInterval(this.timerId);
      this.timerId = -1;
      this.elapsedSeconds = Math.floor((Date.now() - this.startTime) / 1000);
    }

    if (!this.thinking || this.thinking.trim().length === 0) {
      // 空内容不渲染
    } else {
      Column() {
        // 标题行（含计时器标签）
        Row() {
          Text('💭')
            .fontSize(14)
          Text(this.isComplete
            ? (this.elapsedSeconds < 1 ? ' Thought for <1s' : ` Thought for ${this.elapsedSeconds}s`)
            : ` Thinking ${this.elapsedSeconds}s...`)
            .fontSize(13)
            .fontColor(this.isComplete ? '#8A7B6A' : '#C9A86C')
            .fontWeight(this.isComplete ? FontWeight.Normal : FontWeight.Medium)

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

        // 展开的思考内容（自动展开：进行中或已展开）
        if (this.isExpanded || !this.isComplete) {
          Column() {
            Divider()
              .color('#E8E0D4')
              .strokeWidth(0.5)

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
