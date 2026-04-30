import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, WorkflowApiItem, DirEntry } from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import { router as appRouter } from '../common/Router';
import promptAction from '@ohos.promptAction';

@Component
export struct WorkflowPage {
  @State workflows: WorkflowApiItem[] = [];
  @State isLoading: boolean = false;
  @State showCreateSheet: boolean = false;
  @State newName: string = '';
  @State selectedAgentType: string = 'claude_code';
  @State selectedTaskFocus: string = 'general';
  @State newInitialDir: string = '';
  @State showDirBrowser: boolean = false;
  @State browserPath: string = '';
  @State browserSegments: string[] = [];
  @State browserSeparator: string = '/';
  @State browserEntries: DirEntry[] = [];
  @State browserParent: string | null = null;
  @State browserLoading: boolean = false;

  private readonly appStore = globalAppStore;
  private readonly apiClient = globalApiClient;

  async aboutToAppear(): Promise<void> {
    await this.loadWorkflows();
  }

  async loadWorkflows(): Promise<void> {
    const user = this.appStore.currentUser;
    if (!user) return;
    this.isLoading = true;
    try {
      this.workflows = await this.apiClient.getWorkflows(user.id);
    } catch (_) {
      // silent
    } finally {
      this.isLoading = false;
    }
  }

  async createWorkflow(): Promise<void> {
    const user = this.appStore.currentUser;
    const trimmed = this.newName.trim();
    if (!user || !trimmed) return;
    let initialDir: string | undefined;
    if (this.selectedAgentType === 'claude_code') {
      initialDir = this.newInitialDir.trim();
      if (initialDir === '') {
        this.showToast('请输入工作目录');
        return;
      }
    }
    const taskFocus = this.selectedAgentType === 'silk_chat' ? this.selectedTaskFocus : undefined;
    try {
      const result = await this.apiClient.createWorkflow(
        trimmed, '', user.id, this.selectedAgentType, taskFocus, initialDir
      );
      this.showCreateSheet = false;
      this.newName = '';
      this.selectedAgentType = 'claude_code';
      this.selectedTaskFocus = 'general';
      this.newInitialDir = '';
      await this.loadWorkflows();
    } catch (e) {
      const msg = (e as Error).message || '创建失败';
      if (msg.includes('未被信任') && initialDir) {
        this.showTrustConfirm(user.id, initialDir);
      } else {
        this.showToast(msg);
      }
    }
  }

  private showTrustConfirm(userId: string, dir: string): void {
    AlertDialog.show({
      title: '信任目录',
      message: '目录 "' + dir + '" 未被信任，是否将其添加到信任列表？',
      primaryButton: {
        value: '取消',
        action: () => {}
      },
      secondaryButton: {
        value: '信任并重试',
        action: () => {
          this.addTrustAndRetry(userId, dir);
        }
      }
    });
  }

  private async addTrustAndRetry(userId: string, dir: string): Promise<void> {
    try {
      const added = await this.apiClient.addTrustedDir(userId, dir);
      if (added) {
        this.showToast('目录已信任，正在重试...');
        await this.createWorkflow();
      } else {
        this.showToast('添加信任失败，请重试');
      }
    } catch (_) {
      this.showToast('添加信任失败');
    }
  }

  private async openDirBrowser(): Promise<void> {
    const user = this.appStore.currentUser;
    if (!user) return;
    this.showDirBrowser = true;
    await this.loadDirList('', user.id);
  }

  private async loadDirList(path: string, userId?: string): Promise<void> {
    const uid = userId || this.appStore.currentUser?.id;
    if (!uid) return;
    this.browserLoading = true;
    try {
      const listing = await this.apiClient.listCcDir(uid, path || undefined);
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
    const sep = this.browserSeparator;
    const path = parts.join(sep);
    this.loadDirList(path);
  }

  private onBrowserDirClick(entry: DirEntry): void {
    if (!entry.isDir) return;
    const sep = this.browserSeparator;
    const base = this.browserPath.endsWith(sep) ? this.browserPath : this.browserPath + sep;
    this.loadDirList(base + entry.name);
  }

  private goToBrowserParent(): void {
    if (this.browserParent) {
      this.loadDirList(this.browserParent);
    }
  }

  private selectBrowserDir(): void {
    if (this.browserPath) {
      this.newInitialDir = this.browserPath;
    }
    this.showDirBrowser = false;
  }

  async deleteWorkflow(id: string): Promise<void> {
    const user = this.appStore.currentUser;
    if (!user) return;
    try {
      await this.apiClient.deleteWorkflow(id, user.id);
      await this.loadWorkflows();
    } catch (_) {
      this.showToast('删除失败');
    }
  }

  private enterChat(groupId: string, groupName: string, taskFocus: string, agentType?: string): void {
    AppStorage.setOrCreate<string>('currentGroupId', groupId);
    AppStorage.setOrCreate<string>('currentGroupName', groupName);
    AppStorage.setOrCreate<string>('currentGroupInvitationCode', '');
    AppStorage.setOrCreate<string>('currentWorkflowTaskFocus', taskFocus);
    AppStorage.setOrCreate<string>('currentWorkflowAgentType', agentType || '');
    AppStorage.setOrCreate<boolean>('currentIsCcWorkflow', agentType === 'claude_code');
    appRouter.workflowAgentType = agentType || '';
    appRouter.goToChat(groupId, groupName);
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({ message, duration: 1200 });
    } catch (_) { /* ignore */ }
  }

  private agentTypeOptions: string[] = ['claude_code', 'silk_chat'];

  private taskFocusOptions: string[] = ['shopping', 'ride', 'general'];

  private getAgentTypeLabel(type: string): string {
    if (type === 'claude_code') {
      return 'Claude Code';
    }
    return 'Silk Chat';
  }

  private getTaskFocusLabel(focus: string): string {
    if (focus === 'shopping') {
      return '🛒 购物';
    } else if (focus === 'ride') {
      return '🚗 出行';
    }
    return '💬 通用';
  }

  build() {
    Stack() {
      // 主内容区域
      Column() {
        Row() {
        Text('工作流')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)
        Button('+ 创建')
          .height(32)
          .fontSize(13)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => { this.showCreateSheet = true; })
      }
      .width('100%')
      .padding(16)

      Divider().color(SilkColors.border)

      if (this.isLoading) {
        Column() {
          LoadingProgress().width(32).height(32)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else if (this.workflows.length === 0) {
        Column() {
          Text('🔗')
            .fontSize(48)
            .margin({ bottom: 16 })
          Text('尚无工作流')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text('点击右上角 + 创建')
            .fontSize(14)
            .fontColor(SilkColors.textLight)
            .margin({ top: 8 })
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else {
        List({ space: 8 }) {
          ForEach(this.workflows, (wf: WorkflowApiItem) => {
            ListItem() {
              Column() {
                Row() {
                  Text(wf.name)
                    .fontSize(15)
                    .fontColor(SilkColors.textPrimary)
                    .layoutWeight(1)
                }
                .width('100%')
                Row() {
                  if (wf.agentType === 'silk_chat') {
                    Text('Silk Chat')
                      .fontSize(11)
                      .fontColor(Color.White)
                      .backgroundColor('#4A90D9')
                      .borderRadius(4)
                      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                    if (wf.taskFocus === 'shopping') {
                      Text('🛒')
                        .fontSize(12)
                        .margin({ left: 4 })
                    } else if (wf.taskFocus === 'ride') {
                      Text('🚗')
                        .fontSize(12)
                        .margin({ left: 4 })
                    } else {
                      Text('💬')
                        .fontSize(12)
                        .margin({ left: 4 })
                    }
                  } else {
                    Text('Claude Code')
                      .fontSize(11)
                      .fontColor(Color.White)
                      .backgroundColor('#7C3AED')
                      .borderRadius(4)
                      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                  }
                  Text('')
                    .layoutWeight(1)
                  if (wf.agentType === 'silk_chat' || wf.agentType === 'claude_code') {
                    Button('进入对话')
                      .height(28)
                      .fontSize(12)
                      .backgroundColor(SilkColors.primary)
                      .fontColor(Color.White)
                      .onClick(() => {
                        this.enterChat(wf.groupId, wf.name, wf.taskFocus, wf.agentType);
                      })
                      .margin({ right: 8 })
                  }
                  Button('删除')
                    .height(28)
                    .fontSize(12)
                    .fontColor(SilkColors.error)
                    .backgroundColor('#2D2D2D')
                    .onClick(() => { void this.deleteWorkflow(wf.id); })
                }
                .width('100%')
                .margin({ top: 4 })
              }
              .width('100%')
              .padding(16)
              .backgroundColor(SilkColors.surfaceVariant)
              .borderRadius(12)
              .onClick(() => {
                this.enterChat(wf.groupId, wf.name, wf.taskFocus, wf.agentType);
              })
            }
          }, (wf: WorkflowApiItem) => wf.id)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12, top: 8 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)

      if (this.showCreateSheet) {
        if (this.showDirBrowser) {
          // 目录浏览器
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
                      .onClick(() => { this.navigateToSegment(index); })
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
                      Text('📁').fontSize(16).margin({ right: 8 })
                      Text('..').fontSize(14).fontColor(SilkColors.textPrimary)
                    }
                    .width('100%')
                    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                    .onClick(() => { this.goToBrowserParent(); })
                  }
                }

                ForEach(this.browserEntries, (entry: DirEntry) => {
                  ListItem() {
                    Row() {
                      Text('📁').fontSize(16).margin({ right: 8 })
                      Text(entry.name).fontSize(14).fontColor(SilkColors.textPrimary)
                    }
                    .width('100%')
                    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                    .onClick(() => { this.onBrowserDirClick(entry); })
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
                .width('45%').height(38)
                .backgroundColor(SilkColors.surface)
                .fontColor(SilkColors.textSecondary)
                .fontSize(14)
                .onClick(() => { this.showDirBrowser = false; })

              Button('选择此目录')
                .width('45%').height(38)
                .backgroundColor(SilkColors.primary)
                .fontColor(Color.White)
                .fontSize(14)
                .onClick(() => { this.selectBrowserDir(); })
            }
            .width('100%')
            .padding(14)
            .justifyContent(FlexAlign.SpaceBetween)
          }
          .width('100%')
          .height('100%')
          .backgroundColor(SilkColors.surface)
        } else {
          // 创建面板浮层
          Column() {
            Column()
              .width('100%')
              .layoutWeight(1)
              .onClick(() => { this.showCreateSheet = false; })

            this.buildCreateSheet()
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#66000000')
        }
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildCreateSheet() {
    Column() {
      Text('创建工作流')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 16 })

      Text('AI 类型')
        .fontSize(13)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 6 })

      Row({ space: 8 }) {
        ForEach(this.agentTypeOptions, (type: string) => {
          Button(this.getAgentTypeLabel(type))
            .height(36)
            .fontSize(13)
            .backgroundColor(this.selectedAgentType === type ? SilkColors.primary : SilkColors.surface)
            .fontColor(this.selectedAgentType === type ? Color.White : SilkColors.textSecondary)
            .onClick(() => {
              this.selectedAgentType = type;
            })
        }, (type: string) => type)
      }
      .width('100%')
      .margin({ bottom: 16 })

      if (this.selectedAgentType === 'silk_chat') {
        Text('任务焦点')
          .fontSize(13)
          .fontColor(SilkColors.textSecondary)
          .margin({ bottom: 6 })

        Row({ space: 8 }) {
          ForEach(this.taskFocusOptions, (focus: string) => {
            Button(this.getTaskFocusLabel(focus))
              .height(36)
              .fontSize(13)
              .backgroundColor(this.selectedTaskFocus === focus ? SilkColors.primary : SilkColors.surface)
              .fontColor(this.selectedTaskFocus === focus ? Color.White : SilkColors.textSecondary)
              .onClick(() => {
                this.selectedTaskFocus = focus;
              })
          }, (focus: string) => focus)
        }
        .width('100%')
        .margin({ bottom: 16 })
      }

      if (this.selectedAgentType === 'claude_code') {
        Text('工作目录')
          .fontSize(13)
          .fontColor(SilkColors.textSecondary)
          .margin({ bottom: 6 })

        Row({ space: 8 }) {
          TextInput({ placeholder: '输入工作目录路径（如 /home/user/project）', text: $$this.newInitialDir })
            .layoutWeight(1)
            .height(48)
            .backgroundColor(SilkColors.background)

          Button('浏览')
            .height(48)
            .fontSize(13)
            .backgroundColor(SilkColors.primary)
            .fontColor(Color.White)
            .onClick(() => { void this.openDirBrowser(); })
        }
        .width('100%')
        .margin({ bottom: 16 })
      }

      TextInput({ placeholder: '工作流名称', text: $$this.newName })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .margin({ bottom: 16 })

      Row() {
        Button('取消')
          .width('45%')
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => this.showCreateSheet = false)

        Button('创建')
          .width('45%')
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .enabled(this.newName.trim().length > 0)
          .onClick(() => { void this.createWorkflow(); })
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
      .margin({ top: 8 })
    }
    .width('100%')
    .padding(16)
  }
}
