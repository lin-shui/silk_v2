/**
 * CC (Claude Code) 工作流面板
 * 显示工作目录、切换目录、浏览远程文件系统
 */
import { SilkColors } from '../common/Theme';
import {
  apiClient as globalApiClient,
  DirEntry
} from '../api/ApiClient';
import promptAction from '@ohos.promptAction';

@Component
export struct CcWorkflowPanel {
  @StorageProp('currentUserId') userId: string = '';
  @StorageProp('currentGroupId') groupId: string = '';

  @State workingDir: string = '';
  @State isCcActive: boolean = false;
  @State isCcRunning: boolean = false;
  @State bridgeConnected: boolean = false;
  @State panelLoading: boolean = true;

  // 目录浏览器
  @State showDirBrowser: boolean = false;
  @State browserPath: string = '';
  @State browserSegments: string[] = [];
  @State browserSeparator: string = '/';
  @State browserEntries: DirEntry[] = [];
  @State browserParent: string | null = null;
  @State browserLoading: boolean = false;

  private readonly apiClient = globalApiClient;

  aboutToAppear(): void {
    this.loadCcState();
  }

  private async loadCcState(): Promise<void> {
    this.panelLoading = true;
    try {
      const state = await this.apiClient.getCcState(this.userId, this.groupId);
      if (state) {
        this.workingDir = state.workingDir || '';
        this.isCcActive = state.active || false;
        this.isCcRunning = state.running || false;
        this.bridgeConnected = state.bridgeConnected || false;
      }
    } catch (_) {
      // 静默失败
    }
    this.panelLoading = false;
  }

  private async openDirBrowser(): Promise<void> {
    this.showDirBrowser = true;
    await this.loadDirList(this.workingDir || '');
  }

  private async loadDirList(path: string): Promise<void> {
    this.browserLoading = true;
    try {
      const listing = await this.apiClient.listCcDir(this.userId, path || undefined);
      if (listing && listing.success) {
        this.browserPath = listing.path || '';
        this.browserSegments = listing.segments || [];
        this.browserSeparator = listing.separator || '/';
        this.browserEntries = listing.entries || [];
        this.browserParent = listing.parent || null;
      }
    } catch (_) { /* 静默 */ }
    this.browserLoading = false;
  }

  private navigateToSegment(index: number): void {
    const parts: string[] = [];
    for (let i = 0; i <= index; i++) {
      parts.push(this.browserSegments[i]);
    }
    const path = parts.join(this.browserSeparator);
    this.loadDirList(path);
  }

  private onDirItemClick(entry: DirEntry): void {
    if (!entry.isDir) {
      return;
    }
    const sep = this.browserSeparator;
    const base = this.browserPath.endsWith(sep) ? this.browserPath : this.browserPath + sep;
    this.loadDirList(base + entry.name);
  }

  private goToParent(): void {
    if (this.browserParent) {
      this.loadDirList(this.browserParent);
    }
  }

  private onSelectDir(): void {
    const selectedPath = this.browserPath;
    if (!selectedPath) {
      return;
    }
    this.showDirBrowser = false;
    this.checkAndChangeDir(selectedPath);
  }

  private async checkAndChangeDir(dirPath: string): Promise<void> {
    try {
      const trustResult = await this.apiClient.checkTrustedDir(this.userId, dirPath);
      if (trustResult === null) {
        this.showToast('无法检查目录信任状态');
        return;
      }
      if (!trustResult.bridgeConnected) {
        this.showToast('Bridge 未连接');
        return;
      }
      if (!trustResult.trusted) {
        AlertDialog.show({
          title: '信任目录',
          message: '是否信任目录 "' + dirPath + '" 及其子目录的读写执行权限？',
          primaryButton: {
            value: '取消',
            action: () => {}
          },
          secondaryButton: {
            value: '信任并授权',
            action: () => {
              this.addTrustAndChangeDir(dirPath);
            }
          }
        });
      } else {
        await this.performCdDir(dirPath);
      }
    } catch (_) {
      this.showToast('检查信任失败');
    }
  }

  private async addTrustAndChangeDir(dirPath: string): Promise<void> {
    const added = await this.apiClient.addTrustedDir(this.userId, dirPath);
    if (!added) {
      this.showToast('添加信任失败');
      return;
    }
    await this.performCdDir(dirPath);
  }

  private async performCdDir(dirPath: string): Promise<void> {
    const result = await this.apiClient.cdCcDir(this.userId, this.groupId, dirPath);
    if (result && result.success) {
      this.workingDir = result.workingDir || dirPath;
      this.showToast('已切换目录');
    } else {
      this.showToast(result && result.error ? result.error : '切换目录失败');
    }
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({ message: message, duration: 1200 });
    } catch (_) { /* ignore */ }
  }

  build() {
    Column() {
      Row() {
        Text('●')
          .fontSize(10)
          .fontColor(this.bridgeConnected
            ? (this.isCcRunning ? '#52C41A' : '#8C8C8C')
            : '#FF4D4F')
          .margin({ right: 4 })

        Text('userId=' + this.userId + ' groupId=' + this.groupId)
          .fontSize(10)
          .fontColor(SilkColors.textSecondary)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        if (this.panelLoading) {
          Text('加载中')
            .fontSize(12)
            .fontColor(SilkColors.textLight)
        } else if (this.workingDir && this.workingDir.length > 0) {
          Text(this.workingDir)
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .layoutWeight(1)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        } else {
          Text('无目录')
            .fontSize(12)
            .fontColor(SilkColors.textLight)
            .layoutWeight(1)
        }

        Text('更改')
          .fontSize(12)
          .fontColor(SilkColors.primary)
          .fontWeight(FontWeight.Medium)
          .padding({ left: 8, right: 12 })
          .onClick(() => {
            this.openDirBrowser();
          })
      }
      .width('100%')
      .height(36)
      .alignItems(VerticalAlign.Center)
      .padding({ left: 12 })
      .backgroundColor('#F0F5FF')
    }
    .width('100%')
    .bindSheet($$this.showDirBrowser, this.dirBrowserSheet(), {
      height: 500,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  dirBrowserSheet() {
    Column() {
      // 标题
      Text('选择工作目录')
        .fontSize(17)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .width('100%')
        .padding(14)

      // 面包屑
      if (this.browserSegments.length > 0) {
        Scroll() {
          Row({ space: 2 }) {
            ForEach(this.browserSegments, (segment: string, index: number) => {
              if (index > 0) {
                Text('›')
                  .fontSize(13)
                  .fontColor(SilkColors.textLight)
              }
              Text(segment === '/' ? '/' : segment)
                .fontSize(13)
                .fontColor(index === this.browserSegments.length - 1 ? SilkColors.primary : SilkColors.textSecondary)
                .fontWeight(index === this.browserSegments.length - 1 ? FontWeight.Medium : FontWeight.Normal)
                .onClick(() => {
                  this.navigateToSegment(index);
                })
            }, (segment: string) => segment)
          }
          .width('100%')
          .padding({ left: 14, right: 14 })
        }
        .height(32)
        .scrollable(ScrollDirection.Horizontal)
      }

      Divider().color(SilkColors.border).margin({ top: 4, bottom: 4 })

      // 目录列表
      if (this.browserLoading) {
        Column() {
          LoadingProgress().width(24).height(24).color(SilkColors.primary)
          Text('加载中...')
            .fontSize(13)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 8 })
        }
        .width('100%')
        .height(300)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else {
        List({ space: 2 }) {
          // 返回上级
          if (this.browserParent) {
            ListItem() {
              Row() {
                Text('📁')
                  .fontSize(16)
                  .margin({ right: 8 })
                Text('..')
                  .fontSize(14)
                  .fontColor(SilkColors.textPrimary)
              }
              .width('100%')
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .onClick(() => {
                this.goToParent();
              })
            }
          }

          ForEach(this.browserEntries, (entry: DirEntry) => {
            ListItem() {
              Row() {
                Text('📁')
                  .fontSize(16)
                  .margin({ right: 8 })
                Text(entry.name)
                  .fontSize(14)
                  .fontColor(SilkColors.textPrimary)
              }
              .width('100%')
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .onClick(() => {
                this.onDirItemClick(entry);
              })
            }
          }, (entry: DirEntry) => entry.name)
        }
        .width('100%')
        .height(300)
      }

      Divider().color(SilkColors.border).margin({ top: 4, bottom: 4 })

      // 底部按钮
      Row() {
        Button('取消')
          .width('45%')
          .height(38)
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .fontSize(14)
          .onClick(() => {
            this.showDirBrowser = false;
          })

        Button('选择此目录')
          .width('45%')
          .height(38)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .fontSize(14)
          .onClick(() => {
            this.onSelectDir();
          })
      }
      .width('100%')
      .padding(14)
      .justifyContent(FlexAlign.SpaceBetween)
    }
    .width('100%')
    .backgroundColor(SilkColors.surface)
  }
}
