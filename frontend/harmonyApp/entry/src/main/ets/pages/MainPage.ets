import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import { GroupListPage } from './GroupListPage';
import { WorkflowPage } from './WorkflowPage';
import { KnowledgeBasePage } from './KnowledgeBasePage';
import { AudioDuplexPage } from './AudioDuplexPage';
import common from '@ohos.app.ability.common';
import { BUILD_TIMESTAMP } from '../common/EnvConfig';
import { HapUpdater } from '../api/HapUpdater';

@Component
export struct MainPage {
  @State currentTab: number = this.restoreTab();

  private restoreTab(): number {
    const saved = AppStorage.get<number>('lastMainTab');
    return (saved !== undefined && saved >= 0 && saved <= 3) ? saved : 0;
  }

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
      if (!HapUpdater.isNewerVersion(info)) return;
      if (HapUpdater.isVersionSkipped(info.versionCode)) return;
      if (HapUpdater.isInRemindLaterPeriod()) return;

      AlertDialog.show({
        title: '发现新版本 ' + info.versionName,
        message: '版本: ' + info.versionName + '\n大小: ' + HapUpdater.formatFileSize(info.fileSize) + '\n是否立即下载更新？',
        primaryButton: {
          value: '下载',
          action: () => {
            const ctx = getContext(this);
            HapUpdater.downloadHap(ctx).then((path: string) => {
              if (path && path.length > 0) {
                // 尝试自动安装
                HapUpdater.installHap(ctx as common.UIAbilityContext, path).then((installed: boolean) => {
                  if (installed) {
                    AlertDialog.show({
                      title: '安装引导已启动',
                      message: '请在系统安装器中确认安装。完成后请重启应用。\n\n文件路径: ' + path,
                      confirm: { value: '知道了', action: () => {} }
                    });
                  } else {
                    AlertDialog.show({
                      title: '下载完成',
                      message: 'HAP 已保存到:\n' + path + '\n\n请使用文件管理器找到该文件并手动安装。',
                      confirm: { value: '知道了', action: () => {} }
                    });
                  }
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
      AppStorage.set<number>('lastMainTab', index);
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
