/**
 * 参考资料列表组件
 * 底部展示引用的文章/链接，含标题和 URL，点击可用系统浏览器打开
 */
import { Reference } from '../models/Message';
import { Theme, SilkColors } from '../common/Theme';
import common from '@ohos.app.ability.common';
import promptAction from '@ohos.promptAction';

@Component
export struct ReferenceList {
  @Prop references: Reference[] = [];
  @State private isExpanded: boolean = false;

  aboutToAppear(): void {
    this.isExpanded = false;
  }

  private labelForReference(ref: Reference): string {
    return ref.kind === 'citation' ? `来源 ${ref.index}` : `资料 ${ref.index}`;
  }

  private openUrl(rawUrl: string): void {
    let url = rawUrl.trim();
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = `https://${url}`;
    }
    const displayUrl = url;
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        uri: url
      }).then(() => {
      }).catch((_err: Error) => {
        try {
          promptAction.showToast({ message: `打开链接失败: ${displayUrl}`, duration: 2000 });
        } catch (_e) {
        }
      });
    } catch (_) {
      try {
        promptAction.showToast({ message: displayUrl, duration: 2000 });
      } catch (_e) {
      }
    }
  }

  @Builder
  private buildRefItem(ref: Reference) {
    Column() {
      Row() {
        Text(this.labelForReference(ref))
          .fontSize(12)
          .fontColor(SilkColors.primary)
          .fontWeight(FontWeight.Medium)
          .margin({ right: 6 })

        Column() {
          Text(ref.title)
            .fontSize(13)
            .fontColor(ref.url ? '#2F80B7' : SilkColors.textPrimary)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
            .decoration({
              type: ref.url ? TextDecorationType.Underline : TextDecorationType.None,
              color: '#7BA8C9'
            })

          if (ref.url) {
            Text(ref.url)
              .fontSize(11)
              .fontColor('#7BA8C9')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
              .margin({ top: 2 })
          }

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
    .onClick(() => {
      const targetUrl: string | undefined = ref.url;
      if (targetUrl) {
        this.openUrl(targetUrl);
      }
    })
  }

  build() {
    if (this.references.length === 0) {
      Column().width(0).height(0)
    } else {
      Column() {
        Row() {
          Text('📚')
            .fontSize(14)
          Text(` 参考来源 (${this.references.length})`)
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

          ForEach(this.references, (ref: Reference, index?: number) => {
            this.buildRefItem(ref)

            if (index !== undefined && index < this.references.length - 1) {
              Divider()
                .color('#F0EBE0')
                .strokeWidth(0.5)
                .margin({ left: 8, right: 8 })
            }
          }, (ref: Reference, index?: number) => {
            const idx = index !== undefined ? index : 0;
            return `ref_${ref.kind}_${ref.index}_${idx}`;
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
      .margin({ top: 8, bottom: 4 })
    }
  }
}
