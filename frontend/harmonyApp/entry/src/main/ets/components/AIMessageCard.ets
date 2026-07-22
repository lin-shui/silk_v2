/**
 * AI 消息卡片组件
 * 支持结构化 contentBlocks 渲染（含思考计时器）与回退标记解析
 */
import { Message, ContentBlock } from '../models/Message';
import { Theme } from '../common/Theme';
import { ThinkingPanel } from './ThinkingPanel';

@Component
export struct AIMessageCard {
  @Prop message: Message;
  @Prop timeString: string = '';
  @Prop isTransient: boolean = false;
  @Prop isExpanded: boolean = false;
  @Prop onExpandChange: (isExpanded: boolean) => void = (isExpanded: boolean) => {};
  @Prop onCopy: (content: string) => void = (content: string) => {};
  @Prop onForward: (message: Message) => void = (message: Message) => {};

  private getSafeContent(): string {
    return this.message.content ? this.message.content : '';
  }

  private isLongContent(): boolean {
    return this.getSafeContent().length > 500;
  }

  private getPreviewContent(): string {
    const content = this.getSafeContent();
    return content.length > 220 ? content.substring(0, 220) + '...' : content;
  }

  private getDisplayName(): string {
    const raw = this.message.userName ? this.message.userName : '';
    const cleaned = raw.replace(/^[\s\u{1F900}-\u{1FAFF}\u{2600}-\u{27BF}\u{1F300}-\u{1F6FF}]+/u, '').trim();
    if (!cleaned || cleaned === 'Silk') {
      return 'Silk AI';
    }
    return cleaned;
  }

  build() {
    // ── 优先使用结构化 contentBlocks 渲染（与 Web/Android 一致）──
    const blocks = this.message.contentBlocks;
    if (blocks && blocks.length > 0) {
      Column() {
        // 思考过程（带计时器）
        ForEach(blocks.filter((b: ContentBlock) => b.type === 'thinking'), (block: ContentBlock) => {
          ThinkingPanel({ thinking: block.content, isComplete: block.isComplete, elapsedMs: block.elapsedMs })
        })

        // 工具调用
        ForEach(blocks.filter((b: ContentBlock) => b.type === 'tool_use'), (block: ContentBlock) => {
          Column() {
            Row() {
              Text('🔧 ' + (block.toolName ? block.toolName : '工具调用'))
                .fontSize(12)
                .fontColor('#666666')
                .fontWeight(FontWeight.Medium)
            }
            .width('100%')
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor('#F5F5F5')
            .borderRadius(4)
            .margin({ bottom: 4 })

            Text(block.content)
              .fontSize(11)
              .fontColor('#555555')
              .maxLines(20)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .width('100%')
          .padding({ left: 8, right: 8, top: 6, bottom: 6 })
          .backgroundColor('#FAFAFA')
          .borderRadius(8)
          .margin({ bottom: 6 })
        })

        // 正文内容
        ForEach(blocks.filter((b: ContentBlock) => b.type === 'text'), (block: ContentBlock) => {
          Text(block.content)
            .fontSize(14)
            .fontColor(Theme.colors.textPrimary)
        })
      }
      .width('95%')
      .padding(12)
      .borderRadius(12)
      .backgroundColor('#F8F6F0')
    } else {
      // ── 回退：无 contentBlocks 时显示原始内容 ──
      Row() {
        Column() {
          Row() {
            Text('🤖 ' + this.getDisplayName())
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(Theme.colors.primary)

            Text(' · ' + this.timeString)
              .fontSize(12)
              .fontColor(Theme.colors.textLight)
              .margin({ left: 6 })

            if (this.isLongContent()) {
              Blank()
              Text(this.isExpanded ? '收起' : '展开')
                .fontSize(12)
                .fontColor(Theme.colors.primary)
                .onClick(() => {
                  this.onExpandChange(!this.isExpanded);
                })
            }
          }
          .width('100%')

          Blank().height(6)

          Text(this.isExpanded || !this.isLongContent() ? this.getSafeContent() : this.getPreviewContent())
            .fontSize(14)
            .fontColor(Theme.colors.textPrimary)
            .maxLines(this.isExpanded || !this.isLongContent() ? 999 : 8)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('95%')
        .padding(12)
        .borderRadius(12)
        .backgroundColor('#F8F6F0')
      }
      .width('100%')
      .justifyContent(FlexAlign.Start)
      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
    }
  }
}
