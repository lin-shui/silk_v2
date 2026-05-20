/**
 * 群组卡片组件
 */
import { Group } from '../models/Group';
import { CcConnectTokenInfo } from '../api/ApiClient';
import { SilkTheme } from '../common/Theme';

@Component
export struct GroupCard {
  @Prop group: Group | null = null;
  @Prop isActive: boolean = false;
  @Prop ccConnectInfo: CcConnectTokenInfo | null = null;
  onGroupClick?: (group: Group) => void;

  build() {
    Column() {
      if (this.group) {
        Row() {
          // 群组头像
          Column() {
            Text(this.group.name.charAt(0).toUpperCase())
              .fontSize(24)
              .fontColor(Color.White)
              .fontWeight(FontWeight.Bold)
          }
          .width(56)
          .height(56)
          .borderRadius(28)
          .backgroundColor(SilkTheme.colors.primary)
          .justifyContent(FlexAlign.Center)

          // 群组信息
          Column() {
            Row() {
              Text(this.group.name)
                .fontSize(16)
                .fontColor(SilkTheme.colors.textPrimary)
                .fontWeight(FontWeight.Medium)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)

              // cc-connect badge
              if (this.ccConnectInfo !== null) {
                Text(this.getCcBadgeLabel())
                  .fontSize(10)
                  .fontColor(this.getCcBadgeColor())
                  .backgroundColor(this.getCcBadgeBg())
                  .borderRadius(4)
                  .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                  .margin({ left: 8 })
              }
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)

            Blank().height(4)

            if (this.group.lastMessage) {
              Text(this.group.lastMessage)
                .fontSize(14)
                .fontColor(SilkTheme.colors.textSecondary)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            } else {
              Text(this.group.unreadCount && this.group.unreadCount > 0 ? '有新消息' : '点击进入项目')
                .fontSize(14)
                .fontColor(SilkTheme.colors.textLight)
            }

            // cc-connect 工作目录
            if (this.ccConnectInfo !== null && this.ccConnectInfo.connected === true && this.ccConnectInfo.cwd) {
              Blank().height(4)
              Row() {
                Text('📁')
                  .fontSize(11)
                Text(this.ccConnectInfo.cwd)
                  .fontSize(11)
                  .fontColor(SilkTheme.colors.textLight)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .margin({ left: 4 })
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
            }
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 12 })

          // 时间和未读数
          Column() {
            if (this.group.lastMessageTime) {
              Text(this.formatTime(this.group.lastMessageTime))
                .fontSize(12)
                .fontColor(SilkTheme.colors.textLight)
            }

            if (this.group.unreadCount && this.group.unreadCount > 0) {
              Blank().height(4)

              Text(this.group.unreadCount.toString())
                .fontSize(12)
                .fontColor(Color.White)
                .backgroundColor(SilkTheme.colors.error)
                .borderRadius(10)
                .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            }
          }
          .alignItems(HorizontalAlign.End)
        }
        .width('100%')
        .padding(16)
        .backgroundColor(this.isActive ? SilkTheme.colors.surfaceVariant : SilkTheme.colors.surface)
        .borderRadius(12)
        .onClick(() => {
          if (this.group && this.onGroupClick) {
            this.onGroupClick(this.group);
          }
        })
      }
    }
  }

  private getCcBadgeLabel(): string {
    if (!this.ccConnectInfo) return '';
    const raw = (this.ccConnectInfo.agentType || '').toLowerCase().trim();
    let name = 'cc';
    if (raw.startsWith('claude')) name = 'claude';
    else if (raw.startsWith('cursor')) name = 'cursor';
    else if (raw.startsWith('gemini')) name = 'gemini';
    else if (raw.startsWith('codex')) name = 'codex';
    else if (raw.startsWith('copilot')) name = 'copilot';
    else if (raw.length > 0) name = raw;
    return this.ccConnectInfo.connected === true ? name : `${name} (offline)`;
  }

  private getCcBadgeColor(): ResourceStr {
    return this.ccConnectInfo?.connected === true ? '#2E7D32' : '#E65100';
  }

  private getCcBadgeBg(): ResourceStr {
    return this.ccConnectInfo?.connected === true ? '#E8F5E9' : '#FFF3E0';
  }

  private formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now.getTime() - date.getTime();

    if (diff < 60000) { // 1分钟内
      return '刚刚';
    } else if (diff < 3600000) { // 1小时内
      return Math.floor(diff / 60000) + '分钟前';
    } else if (diff < 86400000) { // 24小时内
      return Math.floor(diff / 3600000) + '小时前';
    } else {
      return (date.getMonth() + 1) + '/' + date.getDate();
    }
  }
}
