import { SilkColors } from '../common/Theme';
import { apiClient as globalApiClient, KBTopicApiItem, KBEntryApiItem } from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import promptAction from '@ohos.promptAction';

const PERSONAL_SPACE_ID = '__personal__';

@Component
export struct KnowledgeBasePage {
  @State topics: KBTopicApiItem[] = [];
  @State groups = this.appStore.groups;
  @State selectedTopic: KBTopicApiItem | null = null;
  @State entries: KBEntryApiItem[] = [];
  @State selectedEntry: KBEntryApiItem | null = null;
  @State editorContent: string = '';
  @State isLoading: boolean = false;
  @State isSaving: boolean = false;
  @State selectedSpaceId: string = PERSONAL_SPACE_ID;
  @State entryFilter: 'ALL' | 'CANDIDATE' | 'PUBLISHED' | 'ARCHIVED' = 'ALL';

  @State subPage: string = 'topics';

  @State showCreateTopicSheet: boolean = false;
  @State newTopicName: string = '';
  @State newTopicProject: string = '';

  @State showCreateEntrySheet: boolean = false;
  @State newEntryTitle: string = '';
  @State showMeetingCaptureSheet: boolean = false;
  @State meetingCaptureTopicId: string = '';
  @State meetingCaptureTitle: string = '';
  @State meetingCaptureContent: string = '';
  @State meetingCaptureTagsText: string = 'meeting, minutes';
  @State meetingCaptureStatus: 'CANDIDATE' | 'PUBLISHED' = 'CANDIDATE';
  @State meetingCaptureConfidenceText: string = '0.90';
  @State meetingCaptureResultMessage: string = '';

  private readonly appStore = globalAppStore;
  private readonly apiClient = globalApiClient;

  async aboutToAppear(): Promise<void> {
    await this.appStore.loadGroups();
    this.groups = this.appStore.groups.filter((group) => !group.name.startsWith('wf_'));
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
    this.entryFilter = 'ALL';
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

  async updateEntryStatus(status: 'CANDIDATE' | 'PUBLISHED' | 'ARCHIVED' | 'DELETED'): Promise<void> {
    const userId = this.getUserId();
    if (!userId || !this.selectedEntry) return;
    try {
      const updated = await this.apiClient.updateKBEntry(
        this.selectedEntry.id,
        this.selectedEntry.title,
        this.editorContent,
        this.selectedEntry.tags,
        userId,
        status
      );
      if (!updated) {
        this.showToast('状态更新失败');
        return;
      }
      this.selectedEntry = updated;
      if (this.selectedTopic) {
        this.entries = await this.apiClient.getKBEntries(this.selectedTopic.id, userId);
      }
      this.entryFilter = status === 'DELETED' ? 'ALL' : status;
      this.showToast('条目状态已更新');
    } catch (_) {
      this.showToast('状态更新失败');
    }
  }

  async createTopic(): Promise<void> {
    const userId = this.getUserId();
    const name = this.newTopicName.trim();
    if (!userId || !name) return;
    const selectedSpace = this.selectedSpaceOption();
    try {
      await this.apiClient.createKBTopic(
        name,
        this.newTopicProject.trim(),
        userId,
        selectedSpace.type,
        selectedSpace.groupId
      );
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
      this.entryFilter = 'ALL';
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
    .bindSheet($$this.showMeetingCaptureSheet, this.buildMeetingCaptureSheet(), {
      height: 720,
      backgroundColor: SilkColors.surface
    })
  }

  private selectedSpaceOption(): { id: string; label: string; type: 'PERSONAL' | 'TEAM'; groupId?: string } {
    if (this.selectedSpaceId === PERSONAL_SPACE_ID) {
      return { id: PERSONAL_SPACE_ID, label: '个人', type: 'PERSONAL' };
    }
    const group = this.groups.find((item) => item.id === this.selectedSpaceId);
    if (group) {
      return { id: group.id, label: group.name, type: 'TEAM', groupId: group.id };
    }
    return { id: PERSONAL_SPACE_ID, label: '个人', type: 'PERSONAL' };
  }

  private topicSpaceLabel(topic: KBTopicApiItem): string {
    if (topic.spaceType === 'TEAM') {
      const group = this.groups.find((item) => item.id === topic.groupId);
      return group ? group.name : '团队';
    }
    return '个人';
  }

  private canWriteTopic(topic: KBTopicApiItem | null): boolean {
    if (!topic) return false;
    const userId = this.getUserId();
    if (!userId) return false;
    if (topic.ownerId === userId) return true;
    const policy = topic.accessPolicy;
    if (!policy) return false;
    if ((policy.manageUserIds ?? []).includes(userId)) return true;
    if (policy.writeLocked === true) return false;
    if ((policy.writeUserIds ?? []).includes(userId)) return true;
    return topic.spaceType === 'TEAM' && policy.teamMembersCanWrite !== false;
  }

  private topicPermissionLabel(topic: KBTopicApiItem): string {
    return this.canWriteTopic(topic) ? '可编辑' : '只读';
  }

  private entryStatusLabel(entry: KBEntryApiItem): string {
    switch (entry.status) {
      case 'CANDIDATE': return '候选';
      case 'ARCHIVED': return '已归档';
      case 'DELETED': return '已删除';
      default: return '已发布';
    }
  }

  private entrySourceLabel(entry: KBEntryApiItem): string {
    switch (entry.source?.sourceType) {
      case 'CHAT': return '聊天';
      case 'AI_RESPONSE': return 'AI';
      case 'WORKFLOW': return '工作流';
      case 'MEETING': return '会议';
      case 'FILE': return '文件';
      case 'URL': return 'URL';
      default: return '手动';
    }
  }

  private entrySourceDetails(entry: KBEntryApiItem): Array<{ label: string; value: string }> {
    const details: Array<{ label: string; value: string }> = [];
    const sourceGroupId = entry.source?.sourceGroupId;
    if (sourceGroupId) {
      const group = this.groups.find((item) => item.id === sourceGroupId);
      details.push({
        label: '来源群组',
        value: group ? `${group.name} (${sourceGroupId})` : sourceGroupId
      });
    }
    if (entry.source?.workflowId) {
      details.push({ label: '工作流', value: entry.source.workflowId });
    }
    const messageIds = entry.source?.messageIds ?? [];
    if (messageIds.length > 0) {
      const preview = messageIds.slice(0, 3).join(', ');
      details.push({
        label: '消息',
        value: messageIds.length > 3 ? `${preview} 等 ${messageIds.length} 条` : preview
      });
    }
    if (entry.source?.confidence !== undefined && entry.source?.confidence !== null) {
      details.push({
        label: '置信度',
        value: `${Math.round(entry.source.confidence * 100)}%`
      });
    }
    if (entry.createdBy) {
      details.push({ label: '创建人', value: entry.createdBy });
    }
    if (entry.updatedBy && entry.updatedBy !== entry.createdBy) {
      details.push({ label: '更新人', value: entry.updatedBy });
    }
    return details;
  }

  private filteredTopics(): KBTopicApiItem[] {
    return this.topics.filter((topic) => {
      if (this.selectedSpaceId === PERSONAL_SPACE_ID) {
        return (topic.spaceType ?? 'PERSONAL') === 'PERSONAL';
      }
      return (topic.spaceType ?? 'PERSONAL') === 'TEAM' && topic.groupId === this.selectedSpaceId;
    });
  }

  private filteredEntries(): KBEntryApiItem[] {
    switch (this.entryFilter) {
      case 'CANDIDATE':
        return this.entries.filter((entry) => entry.status === 'CANDIDATE');
      case 'PUBLISHED':
        return this.entries.filter((entry) => (entry.status ?? 'PUBLISHED') === 'PUBLISHED');
      case 'ARCHIVED':
        return this.entries.filter((entry) => entry.status === 'ARCHIVED');
      default:
        return this.entries;
    }
  }

  private entryStatusAction(entry: KBEntryApiItem | null): { label: string; status: 'PUBLISHED' | 'ARCHIVED' } | null {
    if (!entry) return null;
    switch (entry.status) {
      case 'CANDIDATE':
        return { label: '发布', status: 'PUBLISHED' };
      case 'ARCHIVED':
        return { label: '重新发布', status: 'PUBLISHED' };
      case 'PUBLISHED':
      default:
        return { label: '归档', status: 'ARCHIVED' };
    }
  }

  private writableTopics(): KBTopicApiItem[] {
    return this.filteredTopics().filter((topic) => this.canWriteTopic(topic));
  }

  private defaultMeetingCaptureTitle(topic?: KBTopicApiItem | null): string {
    if (topic?.name) {
      return `${topic.name} 会议纪要`;
    }
    return '会议纪要';
  }

  private parseKnowledgeCaptureTags(raw: string): string[] {
    return raw
      .split(/[，,]/)
      .map((item) => item.trim())
      .filter((item) => item.length > 0)
      .filter((item, index, arr) => arr.indexOf(item) === index);
  }

  private parseKnowledgeCaptureConfidence(raw: string): number | undefined {
    const trimmed = raw.trim();
    if (!trimmed) return undefined;
    const value = Number(trimmed);
    if (Number.isNaN(value)) return undefined;
    return Math.min(1, Math.max(0, value));
  }

  private meetingCaptureSource(): { sourceType: 'MEETING'; sourceGroupId?: string; confidence?: number } {
    const topic = this.topics.find((item) => item.id === this.meetingCaptureTopicId);
    return {
      sourceType: 'MEETING',
      sourceGroupId: topic?.spaceType === 'TEAM' ? topic.groupId : undefined,
      confidence: this.parseKnowledgeCaptureConfidence(this.meetingCaptureConfidenceText)
    };
  }

  private openMeetingCapture(): void {
    const topic = this.selectedTopic;
    if (!topic || !this.canWriteTopic(topic)) {
      return;
    }
    this.meetingCaptureTopicId = topic.id;
    this.meetingCaptureTitle = this.defaultMeetingCaptureTitle(topic);
    this.meetingCaptureContent = '';
    this.meetingCaptureTagsText = 'meeting, minutes';
    this.meetingCaptureStatus = 'CANDIDATE';
    this.meetingCaptureConfidenceText = '0.90';
    this.meetingCaptureResultMessage = '';
    this.showMeetingCaptureSheet = true;
  }

  async submitMeetingCapture(): Promise<void> {
    const userId = this.getUserId();
    if (!userId || !this.meetingCaptureTopicId || !this.meetingCaptureTitle.trim() || !this.meetingCaptureContent.trim()) {
      this.meetingCaptureResultMessage = '请补全目标主题、标题和内容';
      return;
    }
    this.isSaving = true;
    this.meetingCaptureResultMessage = '';
    try {
      const created = await this.apiClient.captureKBEntry(
        this.meetingCaptureTopicId,
        this.meetingCaptureTitle.trim(),
        this.meetingCaptureContent.trim(),
        this.parseKnowledgeCaptureTags(this.meetingCaptureTagsText),
        userId,
        this.meetingCaptureSource(),
        this.meetingCaptureStatus
      );
      if (!created) {
        this.meetingCaptureResultMessage = '会议纪要入库失败';
        return;
      }
      const topic = this.topics.find((item) => item.id === this.meetingCaptureTopicId) ?? this.selectedTopic;
      if (topic) {
        this.selectedTopic = topic;
        this.entries = await this.apiClient.getKBEntries(topic.id, userId);
      }
      this.selectedEntry = this.entries.find((item) => item.id === created.id) ?? created;
      this.editorContent = this.selectedEntry.content;
      this.entryFilter = this.selectedEntry.status === 'PUBLISHED' ? 'PUBLISHED' : 'CANDIDATE';
      this.subPage = 'editor';
      this.showMeetingCaptureSheet = false;
      this.showToast(this.meetingCaptureStatus === 'PUBLISHED' ? '会议纪要已发布到知识库' : '会议纪要已作为候选入库');
    } catch (_) {
      this.meetingCaptureResultMessage = '会议纪要入库失败';
    } finally {
      this.isSaving = false;
    }
  }

  @Builder
  private buildBadge(text: string, backgroundColor: string, textColor: string = SilkColors.textPrimary) {
    Text(text)
      .fontSize(11)
      .fontColor(textColor)
      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
      .backgroundColor(backgroundColor)
      .borderRadius(999)
  }

  @Builder
  buildTopicsView() {
    const spaceOptions = [
      { id: PERSONAL_SPACE_ID, label: '个人' },
      ...this.groups.map((group) => ({ id: group.id, label: group.name }))
    ];
    const filteredTopics = this.filteredTopics();
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

      Scroll() {
        Row() {
          ForEach(spaceOptions, (space: { id: string; label: string }) => {
            Button(space.label)
              .height(32)
              .fontSize(13)
              .backgroundColor(this.selectedSpaceId === space.id ? SilkColors.primary : SilkColors.surfaceVariant)
              .fontColor(this.selectedSpaceId === space.id ? Color.White : SilkColors.textPrimary)
              .margin({ right: 8 })
              .onClick(() => { this.selectedSpaceId = space.id; })
          }, (space: { id: string; label: string }) => space.id)
        }
        .padding({ left: 16, right: 16, bottom: 12 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')

      Divider().color(SilkColors.border)

      if (this.isLoading) {
        Column() {
          LoadingProgress().width(32).height(32)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else if (filteredTopics.length === 0) {
        Column() {
          Text('📚')
            .fontSize(48)
            .margin({ bottom: 16 })
          Text('当前空间暂无主题')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text(`点击右上角 + 在${this.selectedSpaceOption().label}空间创建主题`)
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
          ForEach(filteredTopics, (topic: KBTopicApiItem) => {
            ListItem() {
              Column() {
                Row() {
                  Text(topic.name)
                    .fontSize(15)
                    .fontColor(SilkColors.textPrimary)
                    .fontWeight(FontWeight.Medium)
                    .layoutWeight(1)
                  this.buildBadge(this.topicPermissionLabel(topic), SilkColors.background)
                }
                .width('100%')
                Row() {
                  this.buildBadge(this.topicSpaceLabel(topic), SilkColors.surface)
                }
                .margin({ top: 8 })
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
    const filteredEntries = this.filteredEntries();
    const filters: ('ALL' | 'CANDIDATE' | 'PUBLISHED' | 'ARCHIVED')[] = ['ALL', 'CANDIDATE', 'PUBLISHED', 'ARCHIVED'];
    const filterLabels = {
      ALL: '全部',
      CANDIDATE: '候选',
      PUBLISHED: '已发布',
      ARCHIVED: '已归档'
    };
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
        Button('会议入库')
          .height(32)
          .fontSize(13)
          .backgroundColor(this.canWriteTopic(this.selectedTopic) ? SilkColors.surface : SilkColors.surfaceVariant)
          .fontColor(SilkColors.textPrimary)
          .enabled(this.canWriteTopic(this.selectedTopic))
          .margin({ right: 8 })
          .onClick(() => { this.openMeetingCapture(); })
        Button('+ 创建')
          .height(32)
          .fontSize(13)
          .backgroundColor(this.canWriteTopic(this.selectedTopic) ? SilkColors.primary : SilkColors.surfaceVariant)
          .fontColor(Color.White)
          .enabled(this.canWriteTopic(this.selectedTopic))
          .onClick(() => { this.showCreateEntrySheet = true; })
      }
      .width('100%')
      .padding(16)

      if (this.selectedTopic) {
        Row() {
          this.buildBadge(this.topicSpaceLabel(this.selectedTopic), SilkColors.surface)
          this.buildBadge(this.topicPermissionLabel(this.selectedTopic), SilkColors.background)
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 8 })
      }

      Scroll() {
        Row() {
          ForEach(filters, (filter: 'ALL' | 'CANDIDATE' | 'PUBLISHED' | 'ARCHIVED') => {
            Button(filterLabels[filter])
              .height(32)
              .fontSize(13)
              .backgroundColor(this.entryFilter === filter ? SilkColors.primary : SilkColors.surfaceVariant)
              .fontColor(this.entryFilter === filter ? Color.White : SilkColors.textPrimary)
              .margin({ right: 8 })
              .onClick(() => { this.entryFilter = filter; })
          }, (filter: 'ALL' | 'CANDIDATE' | 'PUBLISHED' | 'ARCHIVED') => filter)
        }
        .padding({ left: 16, right: 16, bottom: 8 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')

      Divider().color(SilkColors.border)

      if (filteredEntries.length === 0) {
        Column() {
          Text('📝')
            .fontSize(48)
            .margin({ bottom: 16 })
          Text(this.entryFilter === 'ALL' ? '暂无条目' : '当前筛选下暂无条目')
            .fontSize(18)
            .fontColor(SilkColors.textSecondary)
          Text(
            this.entryFilter !== 'ALL'
              ? '切换筛选查看其它状态的条目'
              : (this.canWriteTopic(this.selectedTopic) ? '点击右上角 + 创建条目' : '当前主题为只读，仅支持浏览已有条目')
          )
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
          ForEach(filteredEntries, (entry: KBEntryApiItem) => {
            ListItem() {
              Column() {
                Text(entry.title)
                  .fontSize(15)
                  .fontColor(SilkColors.textPrimary)
                  .fontWeight(FontWeight.Medium)
                Row() {
                  this.buildBadge(this.entryStatusLabel(entry), SilkColors.surface)
                  this.buildBadge(this.entrySourceLabel(entry), SilkColors.background)
                }
                .margin({ top: 8 })
              }
              .width('100%')
              .padding(16)
              .backgroundColor(SilkColors.surfaceVariant)
              .borderRadius(12)
              .alignItems(HorizontalAlign.Start)
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
    const statusAction = this.entryStatusAction(this.selectedEntry);
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
        Button(this.canWriteTopic(this.selectedTopic) ? (this.isSaving ? '保存中...' : '保存') : '只读')
          .height(32)
          .fontSize(13)
          .backgroundColor(this.canWriteTopic(this.selectedTopic) ? SilkColors.primary : SilkColors.surfaceVariant)
          .fontColor(Color.White)
          .enabled(this.canWriteTopic(this.selectedTopic) && !this.isSaving)
          .onClick(() => { void this.saveEntry(); })
      }
      .width('100%')
      .padding(16)

      Divider().color(SilkColors.border)

      if (this.selectedTopic) {
        Column() {
          Row() {
            this.buildBadge(this.topicSpaceLabel(this.selectedTopic), SilkColors.surface)
            this.buildBadge(this.topicPermissionLabel(this.selectedTopic), SilkColors.background)
            if (this.selectedEntry) {
              this.buildBadge(this.entryStatusLabel(this.selectedEntry), SilkColors.surfaceVariant)
              this.buildBadge(this.entrySourceLabel(this.selectedEntry), SilkColors.background)
            }
          }
          .width('100%')

          if (this.selectedTopic.project) {
            Text(`项目：${this.selectedTopic.project}`)
              .fontSize(12)
              .fontColor(SilkColors.textSecondary)
              .margin({ top: 8 })
          }

          if (this.selectedEntry) {
            ForEach(this.entrySourceDetails(this.selectedEntry), (detail: { label: string; value: string }) => {
              Row() {
                Text(`${detail.label}：`)
                  .fontSize(12)
                  .fontColor(SilkColors.textLight)
                Text(detail.value)
                  .fontSize(12)
                  .fontColor(SilkColors.textSecondary)
                  .layoutWeight(1)
                  .maxLines(2)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .width('100%')
              .margin({ top: 8 })
            }, (detail: { label: string; value: string }) => `${detail.label}-${detail.value}`)
          }

          if (this.canWriteTopic(this.selectedTopic) && statusAction) {
            Button(statusAction.label)
              .height(32)
              .fontSize(13)
              .backgroundColor(SilkColors.primary)
              .fontColor(Color.White)
              .margin({ top: 8 })
              .onClick(() => { void this.updateEntryStatus(statusAction.status); })
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 4 })
      }

      if (!this.canWriteTopic(this.selectedTopic)) {
        Text('当前主题为只读，你可以浏览内容，但不能新建或保存修改。')
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .padding({ left: 16, right: 16, top: 12, bottom: 4 })
      }

      TextArea({ text: $$this.editorContent, placeholder: '在这里输入 Markdown 内容...' })
        .width('100%')
        .layoutWeight(1)
        .fontSize(14)
        .padding(12)
        .backgroundColor(SilkColors.background)
        .enabled(this.canWriteTopic(this.selectedTopic))
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
      Text(`当前空间：${this.selectedSpaceOption().label}`)
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 12 })
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
      Text(`条目会创建在「${this.selectedTopic?.name ?? ''}」主题下`)
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 12 })
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

  @Builder
  buildMeetingCaptureSheet() {
    const writableTopics = this.writableTopics();
    Column() {
      Text('会议纪要入库')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 8 })
      Text('通过统一 capture API 保存会议纪要，可直接存为候选或发布。')
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 12 })
      TextInput({ placeholder: '标题', text: $$this.meetingCaptureTitle })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .onChange((value: string) => this.meetingCaptureTitle = value)
      TextArea({ placeholder: '会议内容', text: $$this.meetingCaptureContent })
        .width('100%')
        .height(180)
        .backgroundColor(SilkColors.background)
        .margin({ top: 8 })
        .onChange((value: string) => this.meetingCaptureContent = value)
      TextInput({ placeholder: '标签（例如 meeting, minutes）', text: $$this.meetingCaptureTagsText })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .margin({ top: 8 })
        .onChange((value: string) => this.meetingCaptureTagsText = value)
      TextInput({ placeholder: '置信度（0.0 - 1.0）', text: $$this.meetingCaptureConfidenceText })
        .width('100%')
        .height(48)
        .backgroundColor(SilkColors.background)
        .margin({ top: 8 })
        .onChange((value: string) => this.meetingCaptureConfidenceText = value)
      Row() {
        Button('候选')
          .height(32)
          .fontSize(13)
          .backgroundColor(this.meetingCaptureStatus === 'CANDIDATE' ? SilkColors.primary : SilkColors.surfaceVariant)
          .fontColor(this.meetingCaptureStatus === 'CANDIDATE' ? Color.White : SilkColors.textPrimary)
          .margin({ right: 8 })
          .onClick(() => { this.meetingCaptureStatus = 'CANDIDATE'; })
        Button('发布')
          .height(32)
          .fontSize(13)
          .backgroundColor(this.meetingCaptureStatus === 'PUBLISHED' ? SilkColors.primary : SilkColors.surfaceVariant)
          .fontColor(this.meetingCaptureStatus === 'PUBLISHED' ? Color.White : SilkColors.textPrimary)
          .onClick(() => { this.meetingCaptureStatus = 'PUBLISHED'; })
      }
      .width('100%')
      .margin({ top: 8, bottom: 8 })
      Text('目标主题')
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 8 })
      if (writableTopics.length === 0) {
        Text('当前空间暂无可写主题，请先创建主题。')
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .margin({ bottom: 8 })
      } else {
        List({ space: 6 }) {
          ForEach(writableTopics, (topic: KBTopicApiItem) => {
            ListItem() {
              Column() {
                Text(topic.name)
                  .fontSize(14)
                  .fontColor(SilkColors.textPrimary)
                  .fontWeight(FontWeight.Medium)
                Row() {
                  this.buildBadge(
                    this.meetingCaptureTopicId === topic.id ? '目标主题' : '可写主题',
                    this.meetingCaptureTopicId === topic.id ? SilkColors.primary : SilkColors.surface,
                    this.meetingCaptureTopicId === topic.id ? Color.White : SilkColors.textPrimary
                  )
                }
                .margin({ top: 6 })
              }
              .width('100%')
              .padding(12)
              .backgroundColor(this.meetingCaptureTopicId === topic.id ? SilkColors.background : SilkColors.surfaceVariant)
              .borderRadius(12)
              .alignItems(HorizontalAlign.Start)
              .onClick(() => { this.meetingCaptureTopicId = topic.id; })
            }
          }, (topic: KBTopicApiItem) => topic.id)
        }
        .height(180)
        .width('100%')
      }
      if (this.meetingCaptureResultMessage) {
        Text(this.meetingCaptureResultMessage)
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .margin({ top: 8 })
      }
      Row() {
        Button('取消')
          .width('45%')
          .backgroundColor(SilkColors.surface)
          .fontColor(SilkColors.textSecondary)
          .onClick(() => { this.showMeetingCaptureSheet = false; })
        Button(this.isSaving ? '保存中...' : (this.meetingCaptureStatus === 'PUBLISHED' ? '发布纪要' : '存入候选'))
          .width('45%')
          .backgroundColor(SilkColors.primary)
          .fontColor(Color.White)
          .enabled(
            !this.isSaving &&
            this.meetingCaptureTopicId.length > 0 &&
            this.meetingCaptureTitle.trim().length > 0 &&
            this.meetingCaptureContent.trim().length > 0
          )
          .onClick(() => { void this.submitMeetingCapture(); })
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
      .margin({ top: 16 })
    }.width('100%').padding(16)
  }
}
