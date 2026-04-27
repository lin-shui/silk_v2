/**
 * 参考资料列表组件
 * 底部展示引用的文章/链接，含标题和 URL
 */
import { Reference } from '../models/Message';
import { Theme, SilkColors } from '../common/Theme';

@Component
export struct ReferenceList {
  @Prop references: Reference[] = [];
  @State private isExpanded: boolean = false;

  private labelForReference(ref: Reference): string {
    return ref.kind === 'citation' ? `来源 ${ref.index}` : `资料 ${ref.index}`;
  }

  build() {
    if (this.references.length === 0) {
      // 空内容不渲染
    } else {
      Column() {
        // 标题行
        Row() {
          Text('📚')
            .fontSize(14)
          Text(` 参考资料 (${this.references.length})`)
            .fontSize(13)
            .fontColor(SilkColors.textSecondary)
            .fontWeight(FontWeight.Medium)
            .layoutWeight(1)
          Text(this.isExpanded ? '收起 ▲' : '展开 ▼')
            .fontSize(12)
            .fontColor(SilkColors.primary)
            .fontWeight(FontWeight.Medium)
        }
        .width('100%')
        .padding({ left: 4, right: 4, top: 6, bottom: 4 })
        .onClick(() => {
          this.isExpanded = !this.isExpanded;
        })

        if (this.isExpanded) {
          Divider()
            .color('#E8E0D4')
            .strokeWidth(0.5)

          // 参考资料列表
          ForEach(this.references, (ref: Reference, index: number) => {
            Column() {
              Row() {
                Text(this.labelForReference(ref))
                  .fontSize(12)
                  .fontColor(SilkColors.primary)
                  .fontWeight(FontWeight.Medium)
                  .margin({ right: 6 })

                Column() {
                  // 标题
                  Text(ref.title)
                    .fontSize(13)
                    .fontColor(ref.url ? '#2F80B7' : SilkColors.textPrimary)
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .width('100%')

                  // URL
                  if (ref.url) {
                    Text(ref.url)
                      .fontSize(11)
                      .fontColor('#7BA8C9')
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                      .margin({ top: 2 })
                  }

                  // 摘要
                  if (ref.snippet) {
                    Text(ref.snippet)
                      .fontSize(12)
                      .fontColor(SilkColors.textSecondary)
                      .maxLines(2)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                      .margin({ top: 2 })
                      .lineHeight(18)
                  }
                }
                .layoutWeight(1)
              }
              .width('100%')
              .padding({ left: 4, right: 4, top: 6, bottom: 6 })
            }
            .width('100%')

            // 分隔线（非最后一项）
            if (index < this.references.length - 1) {
              Divider()
                .color('#F0EBE0')
                .strokeWidth(0.5)
                .margin({ left: 8, right: 8 })
            }
          })
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
      .padding({ left: 4, right: 4, top: 4, bottom: 4 })
      .margin({ top: 4, bottom: 4 })
    }
  }
}
