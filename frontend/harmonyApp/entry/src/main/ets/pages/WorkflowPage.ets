import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, WorkflowApiItem } from '../api/ApiClient';
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
    try {
      let initialDir: string | undefined;
      if (this.selectedAgentType === 'claude_code') {
        initialDir = this.newInitialDir.trim();
        if (initialDir === '') {
          this.showToast('请输入工作目录');
          return;
        }
      }
      const taskFocus = this.selectedAgentType === 'silk_chat' ? this.selectedTaskFocus : undefined;
      const result = await this.apiClient.createWorkflow(
        trimmed, '', user.id, this.selectedAgentType, taskFocus, initialDir
      );
      if (result) {
        this.showCreateSheet = false;
        this.newName = '';
        this.selectedAgentType = 'claude_code';
        this.selectedTaskFocus = 'general';
        this.newInitialDir = '';
        await this.loadWorkflows();
      } else {
        this.showToast('创建失败');
      }
    } catch (_) {
      this.showToast('创建失败');
    }
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

  private enterChat(groupId: string, groupName: string, taskFocus: string): void {
    AppStorage.setOrCreate<string>('currentGroupId', groupId);
    AppStorage.setOrCreate<string>('currentGroupName', groupName);
    AppStorage.setOrCreate<string>('currentGroupInvitationCode', '');
    AppStorage.setOrCreate<string>('currentWorkflowTaskFocus', taskFocus);
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
                  if (wf.agentType === 'silk_chat') {
                    Button('进入对话')
                      .height(28)
                      .fontSize(12)
                      .backgroundColor(SilkColors.primary)
                      .fontColor(Color.White)
                      .onClick(() => {
                        this.enterChat(wf.groupId, wf.name, wf.taskFocus);
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
    .bindSheet($$this.showCreateSheet, this.buildCreateSheet(), {
      height: 440,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  buildCreateSheet() {
    Scroll() {
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

        TextInput({ placeholder: '输入工作目录路径', text: $$this.newInitialDir })
          .width('100%')
          .height(48)
          .backgroundColor(SilkColors.background)
          .onChange((value: string) => this.newInitialDir = value)
          .margin({ bottom: 16 })
      }

      TextInput({ placeholder: '工作流名称', text: $$this.newName })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newName = value)
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
}
