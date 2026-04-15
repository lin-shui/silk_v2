import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, KBTopicApiItem, KBEntryApiItem } from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import promptAction from '@ohos.promptAction';

@Component
export struct KnowledgeBasePage {
  @State topics: KBTopicApiItem[] = [];
  @State selectedTopic: KBTopicApiItem | null = null;
  @State entries: KBEntryApiItem[] = [];
  @State selectedEntry: KBEntryApiItem | null = null;
  @State editorContent: string = '';
  @State isLoading: boolean = false;
  @State isSaving: boolean = false;

  @State subPage: string = 'topics';

  @State showCreateTopicSheet: boolean = false;
  @State newTopicName: string = '';
  @State newTopicProject: string = '';

  @State showCreateEntrySheet: boolean = false;
  @State newEntryTitle: string = '';

  private readonly appStore = globalAppStore;
  private readonly apiClient = globalApiClient;

  async aboutToAppear(): Promise<void> {
    await this.loadTopics();
  }

  private getUserId(): string {
    return this.appStore.currentUser?.id ?? '';
  }

  async loadTopics(): Promise<void> {
    const userId = this.getUserId();
    if (!userId) return;
    this.isLoading = true;
    try {
      this.topics = await this.apiClient.getKBTopics(userId);
    } catch (_) { /* silent */ }
    finally { this.isLoading = false; }
  }

  async onSelectTopic(topic: KBTopicApiItem): Promise<void> {
    this.selectedTopic = topic;
    this.selectedEntry = null;
    this.editorContent = '';
    const userId = this.getUserId();
    if (!userId) return;
    try {
      this.entries = await this.apiClient.getKBEntries(topic.id, userId);
    } catch (_) { /* silent */ }
    this.subPage = 'entries';
  }

  onSelectEntry(entry: KBEntryApiItem): void {
    this.selectedEntry = entry;
    this.editorContent = entry.content;
    this.subPage = 'editor';
  }

  async saveEntry(): Promise<void> {
    const userId = this.getUserId();
    if (!userId || !this.selectedEntry) return;
    this.isSaving = true;
    try {
      await this.apiClient.updateKBEntry(this.selectedEntry.id, undefined, this.editorContent, undefined, userId);
      if (this.selectedTopic) {
        this.entries = await this.apiClient.getKBEntries(this.selectedTopic.id, userId);
      }
      this.showToast('已保存');
    } catch (_) {
      this.showToast('保存失败');
    } finally {
      this.isSaving = false;
    }
  }

  async createTopic(): Promise<void> {
    const userId = this.getUserId();
    const name = this.newTopicName.trim();
    if (!userId || !name) return;
    try {
      await this.apiClient.createKBTopic(name, this.newTopicProject.trim(), userId);
      this.showCreateTopicSheet = false;
      this.newTopicName = '';
      this.newTopicProject = '';
      await this.loadTopics();
    } catch (_) {
      this.showToast('创建失败');
    }
  }

  async createEntry(): Promise<void> {
    const userId = this.getUserId();
    const title = this.newEntryTitle.trim();
    if (!userId || !this.selectedTopic || !title) return;
    try {
      const entry: KBEntryApiItem = await this.apiClient.createKBEntry(this.selectedTopic.id, title, '', [], userId);
      this.showCreateEntrySheet = false;
      this.newEntryTitle = '';
      this.entries = await this.apiClient.getKBEntries(this.selectedTopic.id, userId);
      this.onSelectEntry(entry);
    } catch (_) {
      this.showToast('创建失败');
    }
  }

  goBack(): void {
    if (this.subPage === 'editor') {
      this.subPage = 'entries';
      this.selectedEntry = null;
      this.editorContent = '';
    } else if (this.subPage === 'entries') {
      this.subPage = 'topics';
      this.selectedTopic = null;
      this.entries = [];
    }
  }

  private showToast(message: string): void {
    try { promptAction.showToast({ message, duration: 1200 }); } catch (_) { /* ignore */ }
  }

  build() {
    Column() {
      if (this.subPage === 'topics') {
        this.buildTopicsView()
      } else if (this.subPage === 'entries') {
        this.buildEntriesView()
      } else {
        this.buildEditorView()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showCreateTopicSheet, this.buildCreateTopicSheet(), {
      height: 280,
      backgroundColor: SilkColors.surface
    })
    .bindSheet($$this.showCreateEntrySheet, this.buildCreateEntrySheet(), {
      height: 200,
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  buildTopicsView() {
    Column() {
      Row() {
        Text('知识库')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)
        Button('+ 创建')
          .height(32)
          .fontSize(13)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => { this.showCreateTopicSheet = true; })
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
      } else if (this.topics.length === 0) {
        Column() {
          Text('📚')
            .fontSize(48)
            .margin({ bottom: 16 })
          Text('暂无主题')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text('点击右上角 + 创建第一个主题')
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
          ForEach(this.topics, (topic: KBTopicApiItem) => {
            ListItem() {
              Column() {
                Text(topic.name)
                  .fontSize(15)
                  .fontColor(SilkColors.textPrimary)
                  .fontWeight(FontWeight.Medium)
                if (topic.project) {
                  Text(topic.project)
                    .fontSize(12)
                    .fontColor(SilkColors.textLight)
                    .margin({ top: 4 })
                }
              }
              .width('100%')
              .padding(16)
              .backgroundColor(SilkColors.surfaceVariant)
              .borderRadius(12)
              .alignItems(HorizontalAlign.Start)
              .onClick(() => { void this.onSelectTopic(topic); })
            }
          }, (topic: KBTopicApiItem) => topic.id)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12, top: 8 })
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildEntriesView() {
    Column() {
      Row() {
        Text('←')
          .fontSize(22)
          .fontColor(SilkColors.textPrimary)
          .padding({ right: 12 })
          .onClick(() => { this.goBack(); })
        Text(this.selectedTopic?.name ?? '条目')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)
        Button('+ 创建')
          .height(32)
          .fontSize(13)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .onClick(() => { this.showCreateEntrySheet = true; })
      }
      .width('100%')
      .padding(16)

      Divider().color(SilkColors.border)

      if (this.entries.length === 0) {
        Column() {
          Text('📝')
            .fontSize(48)
            .margin({ bottom: 16 })
          Text('暂无条目')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text('点击右上角 + 创建条目')
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
          ForEach(this.entries, (entry: KBEntryApiItem) => {
            ListItem() {
              Text(entry.title)
                .fontSize(15)
                .fontColor(SilkColors.textPrimary)
                .width('100%')
                .padding(16)
                .backgroundColor(SilkColors.surfaceVariant)
                .borderRadius(12)
                .onClick(() => { this.onSelectEntry(entry); })
            }
          }, (entry: KBEntryApiItem) => entry.id)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12, top: 8 })
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildEditorView() {
    Column() {
      Row() {
        Text('←')
          .fontSize(22)
          .fontColor(SilkColors.textPrimary)
          .padding({ right: 12 })
          .onClick(() => { this.goBack(); })
        Text(this.selectedEntry?.title ?? '编辑')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)
        Button(this.isSaving ? '保存中...' : '保存')
          .height(32)
          .fontSize(13)
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .enabled(!this.isSaving)
          .onClick(() => { void this.saveEntry(); })
      }
      .width('100%')
      .padding(16)

      Divider().color(SilkColors.border)

      TextArea({ text: $$this.editorContent, placeholder: '在这里输入 Markdown 内容...' })
        .width('100%')
        .layoutWeight(1)
        .fontSize(14)
        .padding(12)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => { this.editorContent = value; })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildCreateTopicSheet() {
    Column() {
      Text('创建主题')
        .fontSize(18).fontWeight(FontWeight.Bold).fontColor(SilkColors.textPrimary).margin({ bottom: 16 })
      TextInput({ placeholder: '主题名称', text: $$this.newTopicName })
        .width('100%').height(48).backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newTopicName = value)
      TextInput({ placeholder: '所属项目（可选）', text: $$this.newTopicProject })
        .width('100%').height(48).backgroundColor(SilkColors.background).margin({ top: 8 })
        .onChange((value: string) => this.newTopicProject = value)
      Row() {
        Button('取消').width('45%').backgroundColor(SilkColors.surface).fontColor(SilkColors.textSecondary)
          .onClick(() => this.showCreateTopicSheet = false)
        Button('创建').width('45%').backgroundColor(SilkColors.primary).fontColor(Color.White)
          .enabled(this.newTopicName.trim().length > 0)
          .onClick(() => { void this.createTopic(); })
      }.width('100%').justifyContent(FlexAlign.SpaceBetween).margin({ top: 16 })
    }.width('100%').padding(16)
  }

  @Builder
  buildCreateEntrySheet() {
    Column() {
      Text('创建条目')
        .fontSize(18).fontWeight(FontWeight.Bold).fontColor(SilkColors.textPrimary).margin({ bottom: 16 })
      TextInput({ placeholder: '条目标题', text: $$this.newEntryTitle })
        .width('100%').height(48).backgroundColor(SilkColors.background)
        .onChange((value: string) => this.newEntryTitle = value)
      Row() {
        Button('取消').width('45%').backgroundColor(SilkColors.surface).fontColor(SilkColors.textSecondary)
          .onClick(() => this.showCreateEntrySheet = false)
        Button('创建').width('45%').backgroundColor(SilkColors.primary).fontColor(Color.White)
          .enabled(this.newEntryTitle.trim().length > 0)
          .onClick(() => { void this.createEntry(); })
      }.width('100%').justifyContent(FlexAlign.SpaceBetween).margin({ top: 16 })
    }.width('100%').padding(16)
  }
}
