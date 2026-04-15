import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, WorkflowApiItem } from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import promptAction from '@ohos.promptAction';

@Component
export struct WorkflowPage {
  @State workflows: WorkflowApiItem[] = [];
  @State isLoading: boolean = false;
  @State showCreateSheet: boolean = false;
  @State newName: string = '';

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
      await this.apiClient.createWorkflow(trimmed, '', user.id);
      this.showCreateSheet = false;
      this.newName = '';
      await this.loadWorkflows();
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

  private showToast(message: string): void {
    try {
      promptAction.showToast({ message, duration: 1200 });
    } catch (_) { /* ignore */ }
  }

  build() {
    Row() {
      // Left: list
      Column() {
        Row() {
          Text('工作流')
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.textPrimary)
            .layoutWeight(1)
          Button('+ 创建')
            .height(32)
            .fontSize(12)
            .backgroundColor(SilkColors.primary)
            .fontColor(Color.White)
            .onClick(() => { this.showCreateSheet = true; })
        }
        .width('100%')
        .padding(12)

        Divider().color(SilkColors.border)

        if (this.isLoading) {
          Column() {
            Text('加载中...')
              .fontColor(SilkColors.textSecondary)
          }
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else if (this.workflows.length === 0) {
          Column() {
            Text('暂无工作流')
              .fontColor(SilkColors.textSecondary)
          }
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else {
          List({ space: 0 }) {
            ForEach(this.workflows, (wf: WorkflowApiItem) => {
              ListItem() {
                Row() {
                  Text(wf.name)
                    .fontSize(14)
                    .fontColor(SilkColors.textPrimary)
                    .layoutWeight(1)
                  Text('删除')
                    .fontSize(12)
                    .fontColor(SilkColors.error)
                    .onClick(() => { void this.deleteWorkflow(wf.id); })
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              }
            }, (wf: WorkflowApiItem) => wf.id)
          }
          .layoutWeight(1)
          .divider({ strokeWidth: 1, color: SilkColors.border })
        }
      }
      .width(320)
      .height('100%')
      .backgroundColor(SilkColors.surface)

      Divider().vertical(true).color(SilkColors.border)

      // Right: placeholder
      Column() {
        Text('🔗')
          .fontSize(48)
          .margin({ bottom: 16 })
        Text('工作流功能开发中')
          .fontSize(18)
          .fontColor(SilkColors.textSecondary)
        Text('可在左侧创建工作流名称，后续将支持多机器人编排')
          .fontSize(14)
          .fontColor(SilkColors.textLight)
          .margin({ top: 8 })
      }
      .layoutWeight(1)
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showCreateSheet, this.buildCreateSheet(), {
      height: 200,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  buildCreateSheet() {
    Column() {
      Text('创建工作流')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 16 })

      TextInput({ placeholder: '工作流名称', text: $$this.newName })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newName = value)

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
      .margin({ top: 16 })
    }
    .width('100%')
    .padding(16)
  }
}
