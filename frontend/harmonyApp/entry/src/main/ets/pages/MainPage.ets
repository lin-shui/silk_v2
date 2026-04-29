import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import { GroupListPage } from './GroupListPage';
import { WorkflowPage } from './WorkflowPage';
import { KnowledgeBasePage } from './KnowledgeBasePage';
import { AudioDuplexPage } from './AudioDuplexPage';
import { BUILD_TIMESTAMP } from '../common/EnvConfig';
import { HapUpdater } from '../api/HapUpdater';

@Component
export struct MainPage {
  @State currentTab: number = 0;

  aboutToAppear() {
    this.delayedUpdateCheck();
  }

  private async delayedUpdateCheck(): Promise<void> {
    // 延迟 3 秒后检查更新，避免影响页面启动速度
    await new Promise<void>((resolve) => {
      setTimeout(() => { resolve(); }, 3000);
    });
    try {
      const info = await HapUpdater.getVersionInfo();
      if (info === null) return;
      if (info.versionCode <= BUILD_TIMESTAMP) return;
      if (HapUpdater.isVersionSkipped(info.versionCode)) return;
      if (HapUpdater.isInRemindLaterPeriod()) return;

      AlertDialog.show({
        title: '发现新版本 ' + info.versionName,
        message: '大小: ' + HapUpdater.formatFileSize(info.fileSize) + '\n是否立即下载更新？',
        primaryButton: {
          value: '下载',
          action: () => {
            const ctx = getContext(this);
            HapUpdater.downloadHap(ctx).then((path: string) => {
              if (path && path.length > 0) {
                AlertDialog.show({
                  title: '下载完成',
                  message: 'HAP 已保存到: ' + path + '\n请在文件管理器中打开安装。',
                  confirm: { value: '知道了', action: () => {} }
                });
              } else {
                AlertDialog.show({
                  title: '下载失败',
                  message: '请稍后在设置中重试。',
                  confirm: { value: '知道了', action: () => {} }
                });
              }
            });
          }
        },
        secondaryButton: {
          value: '跳过此版本',
          action: () => { HapUpdater.markSkippedVersion(info.versionCode); }
        },
        cancel: () => { HapUpdater.setRemindLater(); }
      });
    } catch (_) {
      // 静默失败，不影响启动
    }
  }

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.currentTab }) {
      TabContent() {
        GroupListPage()
      }
      .tabBar(this.buildTabItem(0, '💬', 'Silk'))

      TabContent() {
        WorkflowPage()
      }
      .tabBar(this.buildTabItem(1, '🔗', '工作流'))

      TabContent() {
        KnowledgeBasePage()
      }
      .tabBar(this.buildTabItem(2, '📚', '知识库'))

      TabContent() {
        AudioDuplexPage()
      }
      .tabBar(this.buildTabItem(3, '📞', '音频双工'))
    }
    .width('100%')
    .height('100%')
    .barMode(BarMode.Fixed)
    .onChange((index: number) => {
      this.currentTab = index;
    })
  }

  @Builder
  buildTabItem(index: number, icon: string, label: string) {
    Column() {
      Text(icon)
        .fontSize(22)
      Text(label)
        .fontSize(11)
        .fontColor(this.currentTab === index ? SilkColors.primary : SilkColors.textLight)
        .margin({ top: 2 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }
}
