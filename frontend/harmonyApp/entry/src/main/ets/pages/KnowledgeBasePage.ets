import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, KBTopicApiItem, KBEntryApiItem } from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import promptAction from '@ohos.promptAction';

@Component
export struct KnowledgeBasePage {
  @State topics: KBTopicApiItem[] = [];
  @State selectedTopicId: string = '';
  @State entries: KBEntryApiItem[] = [];
  @State selectedEntryId: string = '';
  @State editorContent: string = '';
  @State isLoading: boolean = false;
  @State isSaving: boolean = false;

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

  async selectTopic(topic: KBTopicApiItem): Promise<void> {
    this.selectedTopicId = topic.id;
    this.selectedEntryId = '';
    this.editorContent = '';
    const userId = this.getUserId();
    if (!userId) return;
    try {
      this.entries = await this.apiClient.getKBEntries(topic.id, userId);
    } catch (_) { /* silent */ }
  }

  selectEntry(entry: KBEntryApiItem): void {
    this.selectedEntryId = entry.id;
    this.editorContent = entry.content;
  }

  async saveEntry(): Promise<void> {
    const userId = this.getUserId();
    if (!userId || !this.selectedEntryId) return;
    this.isSaving = true;
    try {
      await this.apiClient.updateKBEntry(this.selectedEntryId, undefined, this.editorContent, undefined, userId);
      this.entries = await this.apiClient.getKBEntries(this.selectedTopicId, userId);
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
    if (!userId || !this.selectedTopicId || !title) return;
    try {
      const entry: KBEntryApiItem = await this.apiClient.createKBEntry(this.selectedTopicId, title, '', [], userId);
      this.showCreateEntrySheet = false;
      this.newEntryTitle = '';
      this.entries = await this.apiClient.getKBEntries(this.selectedTopicId, userId);
      this.selectEntry(entry);
    } catch (_) {
      this.showToast('创建失败');
    }
  }

  private showToast(message: string): void {
    try { promptAction.showToast({ message, duration: 1200 }); } catch (_) { /* ignore */ }
  }

  private getSelectedEntry(): KBEntryApiItem | undefined {
    for (let i = 0; i < this.entries.length; i++) {
      if (this.entries[i].id === this.selectedEntryId) return this.entries[i];
    }
    return undefined;
  }

  build() {
    Row() {
      // Left: topics
      Column() {
        Row() {
          Text('主题')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.textPrimary)
            .layoutWeight(1)
          Button('+')
            .height(28)
            .fontSize(12)
            .backgroundColor(SilkColors.primary)
            .fontColor(Color.White)
            .onClick(() => { this.showCreateTopicSheet = true; })
        }
        .width('100%')
        .padding(10)

        Divider().color(SilkColors.border)

        if (this.isLoading) {
          Column() {
            Text('加载中...').fontColor(SilkColors.textSecondary)
          }.layoutWeight(1).justifyContent(FlexAlign.Center)
        } else {
          List({ space: 0 }) {
            ForEach(this.topics, (topic: KBTopicApiItem) => {
              ListItem() {
                Column() {
                  Text(topic.name)
                    .fontSize(14)
                    .fontColor(SilkColors.textPrimary)
                    .fontWeight(this.selectedTopicId === topic.id ? FontWeight.Bold : FontWeight.Normal)
                  if (topic.project) {
                    Text(topic.project)
                      .fontSize(11)
                      .fontColor(SilkColors.textLight)
                      .margin({ top: 2 })
                  }
                }
                .width('100%')
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .backgroundColor(this.selectedTopicId === topic.id ? '#1AC9A86C' : '#00000000')
                .onClick(() => { void this.selectTopic(topic); })
              }
            }, (topic: KBTopicApiItem) => topic.id)
          }
          .layoutWeight(1)
          .divider({ strokeWidth: 1, color: SilkColors.border })
        }
      }
      .width(180)
      .height('100%')
      .backgroundColor(SilkColors.surface)

      Divider().vertical(true).color(SilkColors.border)

      // Middle: entries
      Column() {
        Row() {
          Text(this.getSelectedTopicName())
            .fontSize(14)
            .fontWeight(FontWeight.Bold)
            .fontColor(SilkColors.textPrimary)
            .layoutWeight(1)
          if (this.selectedTopicId) {
            Button('+')
              .height(28)
              .fontSize(12)
              .backgroundColor(SilkColors.primary)
              .fontColor(Color.White)
              .onClick(() => { this.showCreateEntrySheet = true; })
          }
        }
        .width('100%')
        .padding(10)

        Divider().color(SilkColors.border)

        if (!this.selectedTopicId) {
          Column() {
            Text('请先选择主题').fontColor(SilkColors.textLight).fontSize(13)
          }.layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.entries.length === 0) {
          Column() {
            Text('暂无条目').fontColor(SilkColors.textLight).fontSize(13)
          }.layoutWeight(1).justifyContent(FlexAlign.Center)
        } else {
          List({ space: 0 }) {
            ForEach(this.entries, (entry: KBEntryApiItem) => {
              ListItem() {
                Text(entry.title)
                  .fontSize(13)
                  .fontColor(SilkColors.textPrimary)
                  .fontWeight(this.selectedEntryId === entry.id ? FontWeight.Bold : FontWeight.Normal)
                  .width('100%')
                  .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                  .backgroundColor(this.selectedEntryId === entry.id ? '#14C9A86C' : '#00000000')
                  .onClick(() => { this.selectEntry(entry); })
              }
            }, (entry: KBEntryApiItem) => entry.id)
          }
          .layoutWeight(1)
          .divider({ strokeWidth: 1, color: SilkColors.border })
        }
      }
      .width(200)
      .height('100%')
      .backgroundColor(SilkColors.surfaceVariant)

      Divider().vertical(true).color(SilkColors.border)

      // Right: editor
      Column() {
        if (this.selectedEntryId) {
          Row() {
            Text(this.getSelectedEntry()?.title ?? '')
              .fontSize(16)
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
          .padding(12)

          Divider().color(SilkColors.border)

          TextArea({ text: $$this.editorContent, placeholder: '在这里输入 Markdown 内容...' })
            .width('100%')
            .layoutWeight(1)
            .fontSize(14)
            .backgroundColor(SilkColors.background)
            .onChange((value: string) => { this.editorContent = value; })
        } else {
          Column() {
            Text('📚')
              .fontSize(48)
              .margin({ bottom: 16 })
            Text('选择或创建条目开始编辑')
              .fontSize(18)
              .fontColor(SilkColors.textSecondary)
            Text('内容将自动归类到 Obsidian 知识库')
              .fontSize(14)
              .fontColor(SilkColors.textLight)
              .margin({ top: 8 })
          }
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }
      }
      .layoutWeight(1)
      .height('100%')
      .backgroundColor(SilkColors.background)
    }
    .width('100%')
    .height('100%')
    .bindSheet($$this.showCreateTopicSheet, this.buildCreateTopicSheet(), {
      height: 280,
      backgroundColor: SilkColors.surface
    })
    .bindSheet($$this.showCreateEntrySheet, this.buildCreateEntrySheet(), {
      height: 200,
      backgroundColor: SilkColors.surface
    })
  }

  private getSelectedTopicName(): string {
    for (let i = 0; i < this.topics.length; i++) {
      if (this.topics[i].id === this.selectedTopicId) return this.topics[i].name;
    }
    return '条目';
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
