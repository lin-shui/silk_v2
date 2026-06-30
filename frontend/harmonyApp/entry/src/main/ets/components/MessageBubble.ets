/**
 * 消息气泡组件
 * 用于在聊天界面显示单条消息
 * 严格对标 Android 端 ChatScreen.kt 的 MessageItem 逻辑
 */
import { Message, MessageType, MessageCategory, Reference, ContentBlock, isAgentUserId } from '../models/Message';
import { Theme, SilkColors } from '../common/Theme';
import { MarkdownLite } from './MarkdownLite';
import { ReferenceList } from './ReferenceList';
import { ThinkingPanel } from './ThinkingPanel';
import { MetaServiceIntent, LaunchResult } from '../models/MetaServiceDef';
import { MetaServiceLauncher } from '../common/MetaServiceLauncher';
import { MetaServiceConfig } from '../common/MetaServiceConfig';
import promptAction from '@ohos.promptAction';
import {
  parseForwardedMessageContent,
  ForwardedMessageParts,
  parseBatchForwardMarkdownBody,
  BatchForwardItem
} from '../common/ForwardedMessageParse';

@Component
export struct MessageBubble {
  @Prop message: Message;
  @Prop isOwn: boolean = false;
  @Prop currentUserId: string = '';
  @Prop actionsEnabled: boolean = true;
  onCopy?: (content: string) => void;
  onForward?: (message: Message) => void;
  onShare?: (message: Message) => void;
  onOpenActions?: (message: Message) => void;
  @Prop messageStatus: string = 'sent';
  onRetry?: (message: Message) => void;
  /** 长文本/转发全文从展开收起到折叠后，由列表滚回本条，避免高度突变导致视口跑偏 */
  onLongContentCollapsed?: () => void;
  /** 长文本展开后滚动到本条头部 */
  onLongContentExpanded?: () => void;
  @Prop suggestedAction: MetaServiceIntent | null = null;
  @Prop isLastMessage: boolean = false;
  @State private isAIExpanded: boolean = false;
  @State private isForwardExpanded: boolean = false;
  @State private metaParamPickup: string = '';
  @State private metaParamDestination: string = '';

  aboutToAppear() {
    this.isAIExpanded = this.isLastMessage;
  }

  private notifyLongContentCollapsed(): void {
    if (this.onLongContentCollapsed) {
      this.onLongContentCollapsed();
    }
  }

  build() {
    Column() {
      // 1. AI 消息特殊处理
      if (this.isAIMessage()) {
        this.buildInlineAIMessageCard()
      } else if (this.message.type === MessageType.SYSTEM) {
        // 2. 系统消息
        this.buildSystemMessage()
      } else {
        // 3. 普通消息
        this.buildNormalMessage()
      }
    }
    .width('100%')
  }

  private isAIMessage(): boolean {
    return isAgentUserId(this.message.userId) &&
           this.message.category !== MessageCategory.AGENT_STATUS;
  }

  private getSafeAIContent(): string {
    const raw = this.message.content ? this.message.content : '';
    return this.renderCitationMarkersAsLinks(raw);
  }

  private renderCitationMarkersAsLinks(content: string): string {
    const refs: Reference[] = this.message.references ? this.message.references : [];
    if (refs.length === 0 || content.length === 0) {
      return content;
    }
    return content.replace(/\[(citation|available):(\d+)\]/g, (_m: string, kind: string, idxText: string) => {
      const idx = Number(idxText);
      const ref = refs.find((r: Reference) => r.kind === kind && r.index === idx);
      const label = kind === 'citation' ? `来源 ${idx}` : `资料 ${idx}`;
      if (!ref || !ref.url || ref.url.trim().length === 0) {
        return `[${label}]`;
      }
      return `[${label}](${ref.url.trim()})`;
    });
  }

  private isLongAIContent(): boolean {
    return this.getSafeAIContent().length > 500;
  }

  private getAIPreview(): string {
    const content = this.getSafeAIContent();
    return content.length > 220 ? `${content.substring(0, 220)}...` : content;
  }

  private canShowAIActions(): boolean {
    return this.actionsEnabled && !this.message.isTransient;
  }

  private canShowNormalActions(): boolean {
    return this.actionsEnabled && this.message.type === MessageType.TEXT && !this.message.isTransient;
  }

  private isForwardedContent(): boolean {
    return parseForwardedMessageContent(this.message.content ? this.message.content : '') !== null;
  }

  private forwardedParts(): ForwardedMessageParts | null {
    return parseForwardedMessageContent(this.message.content ? this.message.content : '');
  }

  private forwardBodyPreviewText(rawBody: string): string {
    const b = rawBody ? rawBody.replace(/\r/g, '').trim() : '';
    if (b.length === 0) {
      return '（无正文）';
    }
    const singleLine = b.split('\n')[0];
    const line = singleLine.length > 0 ? singleLine : b;
    const maxLen = 96;
    if (line.length <= maxLen) {
      return line;
    }
    return `${line.substring(0, maxLen)}…`;
  }

  /** 折叠预览用的正文：批量已拆分时不带 ### 标题行 */
  private forwardPreviewSource(): string {
    const fp = this.forwardedParts();
    if (!fp) {
      return '';
    }
    if (fp.isBatch) {
      const items = parseBatchForwardMarkdownBody(fp.body);
      if (items.length > 0) {
        return items[0].content;
      }
    }
    return fp.body;
  }

  private getBatchForwardItems(): BatchForwardItem[] {
    const fp = this.forwardedParts();
    if (!fp || !fp.isBatch) {
      return [];
    }
    return parseBatchForwardMarkdownBody(fp.body);
  }

  private useBatchItemLayout(): boolean {
    return this.getBatchForwardItems().length > 0;
  }

  @Builder
  private buildForwardedBubbleBody() {
    Column() {
      Row() {
        Text('📨')
          .fontSize(14)
          .margin({ right: 6 })
        Text(this.forwardedParts()!.isBatch ? '批量转发' : '转发')
          .fontSize(12)
          .fontColor('#7A6B5A')
        Text(' · 来自')
          .fontSize(12)
          .fontColor(Theme.colors.textLight)
          .margin({ left: 4, right: 4 })
        Text(this.forwardedParts()!.sourceName.length > 0 ? this.forwardedParts()!.sourceName : '未命名会话')
          .fontSize(12)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.primary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (this.forwardedParts()!.senderName.length > 0) {
        Row() {
          Text(this.forwardedParts()!.senderName)
            .fontSize(12)
            .fontWeight(FontWeight.Medium)
            .fontColor(SilkColors.primary)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('100%')
        .margin({ top: 8, bottom: 2, left: 4 })
      }

      if (!this.isForwardExpanded) {
        Column() {
          Text(this.forwardBodyPreviewText(this.forwardPreviewSource()))
            .fontSize(13)
            .fontColor(Theme.colors.textSecondary)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .lineHeight(20)
            .width('100%')

          Row() {
            Text('查看转发全文')
              .fontSize(13)
              .fontColor(SilkColors.primary)
              .fontWeight(FontWeight.Medium)
            Text('  ▼')
              .fontSize(12)
              .fontColor(SilkColors.primary)
          }
          .margin({ top: 10 })
          .width('100%')
          .justifyContent(FlexAlign.Center)
          .padding({ top: 6, bottom: 6 })
          .onClick(() => {
            this.isForwardExpanded = true;
          })
        }
        .width('100%')
        .margin({ top: 10 })
      } else {
        if (this.useBatchItemLayout()) {
          ForEach(this.getBatchForwardItems(), (item: BatchForwardItem, index?: number) => {
            Column() {
              Text(item.senderName.length > 0 ? item.senderName : '用户')
                .fontSize(12)
                .fontWeight(FontWeight.Medium)
                .fontColor(SilkColors.primary)
                .width('100%')
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .margin({
                  top: (index !== undefined && index === 0) ? 10 : 14,
                  bottom: 6,
                  left: 4
                })
              MarkdownLite({
                content: item.content
              })
              if (index !== undefined && index < this.getBatchForwardItems().length - 1) {
                Row()
                  .width('100%')
                  .height(1)
                  .backgroundColor('#E0D8CC')
                  .margin({ top: 14, bottom: 2 })
              }
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
          }, (item: BatchForwardItem, index?: number) => {
            const idx = index !== undefined ? index : 0;
            return `bf_${idx}_${item.senderName}_${item.content.length}`;
          })
        } else {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor('#E0D8CC')
            .margin({ top: this.forwardedParts()!.senderName.length > 0 ? 10 : 10, bottom: 10 })

          MarkdownLite({
            content: this.forwardedParts()!.body
          })
        }

        Row() {
          Text('收起')
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
          Text('  ▲')
            .fontSize(11)
            .fontColor(SilkColors.textSecondary)
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .margin({ top: 10 })
        .padding({ top: 4, bottom: 4 })
        .onClick(() => {
          this.isForwardExpanded = false;
          this.notifyLongContentCollapsed();
        })
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  private buildQuickAction(label: string, onTap: () => void) {
    Row() {
      Text(label)
        .fontSize(11)
        .fontColor(Theme.colors.textLight)
    }
    .padding({ left: 8, right: 8, top: 4, bottom: 4 })
    .backgroundColor('#14000000')
    .borderRadius(10)
    .onClick(() => onTap())
  }

  @Builder
  private buildInlineAIMessageCard() {
    Row() {
      Column() {
        Row() {
          Text('🤖')
            .fontSize(16)
          Text(' Silk AI')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .fontColor('#C9A86C')
            .margin({ left: 4 })

          Text(' · ' + this.formatTime(this.message.timestamp))
            .fontSize(12)
            .fontColor(Theme.colors.textSecondary)
            .margin({ left: 8 })

          if (this.isLongAIContent()) {
            Blank()
            Text(this.isAIExpanded ? '▼ 收起' : '▶ 展开')
              .fontSize(12)
              .fontColor(Theme.colors.primary)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor(this.isAIExpanded ? Color.Transparent : '#1AC9A86C')
              .borderRadius(4)
              .onClick(() => {
                const wasExpanded = this.isAIExpanded;
                this.isAIExpanded = !this.isAIExpanded;
                if (wasExpanded) {
                  this.notifyLongContentCollapsed();
                } else if (this.onLongContentExpanded) {
                  this.onLongContentExpanded();
                }
              })
          }
        }
        .width('100%')

        Blank().height(10)

        // 元服务快捷按钮（置顶）
        if (this.suggestedAction) {
          if (this.suggestedAction.serviceType === 'parameter') {
            TextInput({ placeholder: '出发地', text: this.metaParamPickup })
              .height(40)
              .backgroundColor('#FFFFFF')
              .borderRadius(8)
              .padding({ left: 12, right: 12 })
              .onChange((v: string) => { this.metaParamPickup = v; })
            Blank().height(8)
            TextInput({ placeholder: '目的地', text: this.metaParamDestination })
              .height(40)
              .backgroundColor('#FFFFFF')
              .borderRadius(8)
              .padding({ left: 12, right: 12 })
              .onChange((v: string) => { this.metaParamDestination = v; })
            Blank().height(12)
          }
          Row() {
            Row() {
              Text(this.getActionButtonIcon())
                .fontSize(13)
              Blank().width(6)
              Text(this.getActionButtonLabel())
                .fontSize(13)
                .fontWeight(FontWeight.Medium)
                .fontColor('#FFFFFF')
            }
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })
            .onClick(() => {
              this.handleSuggestedAction();
            })
          }
          .width('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor(this.getActionButtonColor())
          .borderRadius(8)
          Blank().height(10)
        }

        Row()
          .width('100%')
          .height(1)
          .backgroundColor('#E8E0D4')

        Blank().height(10)

        // ── 优先使用结构化 contentBlocks 渲染（与 Web/Android 一致）──
        if (this.message.contentBlocks && this.message.contentBlocks.length > 0) {
          // 思考过程（带计时器）
          ForEach(this.message.contentBlocks.filter((b: ContentBlock) => b.type === 'thinking'),
            (block: ContentBlock) => {
              ThinkingPanel({ thinking: block.content, isComplete: block.isComplete })
            })

          // 工具调用
          ForEach(this.message.contentBlocks.filter((b: ContentBlock) => b.type === 'tool_use'),
            (block: ContentBlock) => {
              Column() {
                Row() {
                  Text('🔧 ' + (block.toolName ? block.toolName : '工具调用'))
                    .fontSize(12)
                    .fontColor('#666666')
                    .fontWeight(FontWeight.Medium)
                }
                .width('100%')
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .backgroundColor('#F5F5F5')
                .borderRadius(4)
                .margin({ bottom: 4 })

                Text(block.content)
                  .fontSize(11)
                  .fontColor('#555555')
                  .maxLines(20)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .width('100%')
              .padding({ left: 8, right: 8, top: 6, bottom: 6 })
              .backgroundColor('#FAFAFA')
              .borderRadius(8)
              .margin({ bottom: 6 })
            })

          // 正文
          ForEach(this.message.contentBlocks.filter((b: ContentBlock) => b.type === 'text'),
            (block: ContentBlock) => {
              MarkdownLite({ content: block.content })
            })
        } else {
          // ── 回退：无 contentBlocks 时解析标记并分区显示（与 Android 端一致）──
          // 支持两种标记格式：
          // 1. Claude PTY: <!--THINKING_END--> (单标记) + <!--TOOLS_END-->
          // 2. Claude Native: <!--THINKING-->...<!--END_THINKING--> + <!--TOOL name="..."-->...<!--END_TOOL-->
          const ccClean = this.getSafeAIContent()
            .replace('<!--CC_TURN-->\n', '')
            .replace('<!--CC_TURN-->', '');

          const hasPairedThinking = ccClean.includes('<!--THINKING-->') || ccClean.includes('<!--END_THINKING-->');
          const hasPairedTools = ccClean.includes('<!--TOOL ') || ccClean.includes('<!--END_TOOL-->');

          let thinkingText = '';
          let toolsText = '';
          let bodyText = '';

          if (hasPairedThinking || hasPairedTools) {
            // Claude Native format: extract thinking blocks, tool blocks, and body text
            const thinkingBlocks: string[] = [];
            const toolBlocks: string[] = [];
            let remaining = ccClean;

            while (remaining.length > 0) {
              const ti = remaining.indexOf('<!--THINKING-->');
              const te = remaining.indexOf('<!--TOOL ');
              const et = remaining.indexOf('<!--END_THINKING-->');
              const eto = remaining.indexOf('<!--END_TOOL-->');

              if (ti < 0 && te < 0 && et < 0 && eto < 0) {
                const trimmed = remaining.trim();
                if (trimmed.length > 0) {
                  if (toolBlocks.length > 0 && thinkingBlocks.length === 0) {
                    toolBlocks[toolBlocks.length - 1] = toolBlocks[toolBlocks.length - 1] + '\n\n' + trimmed;
                  } else {
                    bodyText = trimmed;
                  }
                }
                break;
              }

              const candidates: [number, string][] = [];
              if (ti >= 0) candidates.push([ti, 'THINKING_OPEN']);
              if (te >= 0) candidates.push([te, 'TOOL_OPEN']);
              if (et >= 0) candidates.push([et, 'THINKING_CLOSE']);
              if (eto >= 0) candidates.push([eto, 'TOOL_CLOSE']);
              candidates.sort((a, b) => a[0] - b[0]);

              const [pos, marker] = candidates[0];

              if (marker === 'THINKING_OPEN') {
                const endPos = remaining.indexOf('<!--END_THINKING-->', pos);
                if (endPos >= 0) {
                  const content = remaining.substring(pos + '<!--THINKING-->'.length, endPos).trim();
                  if (content.length > 0) thinkingBlocks.push(content);
                  remaining = remaining.substring(endPos + '<!--END_THINKING-->'.length);
                } else {
                  remaining = remaining.substring(pos + '<!--THINKING-->'.length);
                }
              } else if (marker === 'THINKING_CLOSE') {
                const content = remaining.substring(0, pos).trim();
                if (content.length > 0) thinkingBlocks.push(content);
                remaining = remaining.substring(pos + '<!--END_THINKING-->'.length);
              } else if (marker === 'TOOL_OPEN') {
                const closeTag = '-->';
                const closeIdx = remaining.indexOf(closeTag, pos + '<!--TOOL'.length);
                if (closeIdx >= 0) {
                  const endTool = remaining.indexOf('<!--END_TOOL-->', closeIdx);
                  if (endTool >= 0) {
                    let content = remaining.substring(closeIdx + closeTag.length, endTool).trim();
                    const tsRegex = /<!--TOOL_SUMMARY\s*:\s*([^>]+)-->/;
                    const tsMatch = tsRegex.exec(content);
                    if (tsMatch) content = content.replace(tsRegex, '').trim();
                    if (content.length > 0) toolBlocks.push(content);
                    remaining = remaining.substring(endTool + '<!--END_TOOL-->'.length);
                  } else {
                    remaining = remaining.substring(closeIdx + closeTag.length);
                  }
                } else {
                  remaining = remaining.substring(pos + '<!--TOOL'.length);
                }
              } else {
                remaining = remaining.substring(pos + '<!--END_TOOL-->'.length);
              }
            }

            thinkingText = thinkingBlocks.join('\n\n');
            toolsText = toolBlocks.join('\n\n');
            if (bodyText.length === 0 && thinkingText.length === 0 && toolsText.length === 0) {
              bodyText = ccClean.trim();
            }
          } else {
            // Claude PTY / cc-connect format: single marker per section
            const thinkingMarker = '<!--THINKING_END-->';
            const hasThinking = ccClean.includes(thinkingMarker);
            thinkingText = hasThinking ? ccClean.substringBefore(thinkingMarker).trim() : '';

            const afterThinking = hasThinking ? ccClean.substringAfter(thinkingMarker).trim() : ccClean;

            const toolsMarker = '<!--TOOLS_END-->';
            const hasTools = afterThinking.includes(toolsMarker);
            let rawToolsText = hasTools ? afterThinking.substringBefore(toolsMarker).trim() : '';
            bodyText = hasTools ? afterThinking.substringAfter(toolsMarker).trim() : afterThinking;

            const toolsSep = '\n\n---\n\n';
            if (hasTools && rawToolsText.length > 0 && bodyText.length === 0 && rawToolsText.includes(toolsSep)) {
              toolsText = rawToolsText.substringBefore(toolsSep).trim();
              bodyText = rawToolsText.substringAfter(toolsSep).trim();
            } else {
              toolsText = rawToolsText;
            }
          }

          const hasThinking = thinkingText.length > 0;
          const hasTools = toolsText.length > 0;

          // 思考过程（折叠区）
          if (hasThinking && thinkingText.length > 0) {
            Column() {
              Row() {
                Text('💭 思考过程')
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#8B7355')
              }
              .width('100%')
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .backgroundColor('#FAF8F4')
              .border({ width: 1, color: '#E8E0D4', style: BorderStyle.Solid })
              .borderRadius({ topLeft: 8, topRight: 8 })

              Text(thinkingText)
                .fontSize(12)
                .fontColor('#8B7355')
                .lineHeight(18)
                .width('100%')
                .padding(12)
                .backgroundColor('#FAF8F4')
                .border({ width: { left: 1, right: 1, bottom: 1 }, color: '#E8E0D4', style: BorderStyle.Solid })
                .borderRadius({ bottomLeft: 8, bottomRight: 8 })
                .margin({ bottom: 8 })
            }
            .width('100%')
          }

          // 工具调用过程（折叠区）
          if (hasTools && toolsText.length > 0) {
            Column() {
              Row() {
                Text('🔧 工具调用过程')
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#666666')
              }
              .width('100%')
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .backgroundColor('#F5F5F5')
              .border({ width: 1, color: '#E0E0E0', style: BorderStyle.Solid })
              .borderRadius({ topLeft: 8, topRight: 8 })

              MarkdownLite({ content: toolsText })
                .width('100%')
                .padding(12)
                .backgroundColor('#F5F5F5')
                .border({ width: { left: 1, right: 1, bottom: 1 }, color: '#E0E0E0', style: BorderStyle.Solid })
                .borderRadius({ bottomLeft: 8, bottomRight: 8 })
                .margin({ bottom: 8 })
            }
            .width('100%')
          }

          // 正文
          if (bodyText.length > 0) {
            const bodyIsLong = bodyText.length > 500;
            const bodyPreview = bodyText.length > 220 ? bodyText.substring(0, 220) + '...' : bodyText;

            if (!bodyIsLong || this.isAIExpanded) {
              MarkdownLite({ content: bodyText })
              ReferenceList({
                references: this.message.references ? this.message.references : []
              })
            } else if (!this.isAIExpanded) {
              Column() {
                Text(bodyPreview)
                  .fontSize(14)
                  .fontColor(Theme.colors.textPrimary)
                  .maxLines(8)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .width('100%')

                Row() {
                  Text('查看全文')
                    .fontSize(13)
                    .fontColor(SilkColors.primary)
                    .fontWeight(FontWeight.Medium)
                  Text('  ▼')
                    .fontSize(12)
                    .fontColor(SilkColors.primary)
                }
                .margin({ top: 10 })
                .width('100%')
                .justifyContent(FlexAlign.Center)
                .padding({ top: 6, bottom: 6 })
                .onClick(() => {
                  this.isAIExpanded = true;
                  if (this.onLongContentExpanded) {
                    this.onLongContentExpanded();
                  }
                })
              }
              .width('100%')
            } else {
              Column() {
                MarkdownLite({ content: bodyText })
                ReferenceList({
                  references: this.message.references ? this.message.references : []
                })
                Row() {
                  Text('收起')
                    .fontSize(12)
                    .fontColor(SilkColors.textSecondary)
                  Text('  ▲')
                    .fontSize(11)
                    .fontColor(SilkColors.textSecondary)
                }
                .width('100%')
                .justifyContent(FlexAlign.Center)
                .margin({ top: 10 })
                .padding({ top: 4, bottom: 4 })
                .onClick(() => {
                  this.isAIExpanded = false;
                  this.notifyLongContentCollapsed();
                })
              }
              .width('100%')
            }
          }
        }

        // CC metadata footer（耗时/轮次）
        if (this.message.footerContent) {
          Blank().height(8)
          Row() {
            Text(this.message.footerContent)
              .fontSize(12)
              .fontColor(Theme.colors.textSecondary)
          }
          .width('100%')
        }

        if (this.canShowAIActions()) {
          Blank().height(10)

          Row()
            .width('100%')
            .height(0.5)
            .backgroundColor('#E8E0D4')

          Blank().height(6)

          Row() {
            Blank()

            Row() {
              Text('📋')
                .fontSize(14)
              Blank().width(4)
              Text('复制')
                .fontSize(12)
                .fontColor(Theme.colors.textSecondary)
            }
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .onClick(() => {
              if (this.onCopy) {
                this.onCopy(this.getSafeAIContent());
              }
            })

            Blank().width(8)

            Row() {
              Text('⋯')
                .fontSize(15)
              Blank().width(4)
              Text('更多')
                .fontSize(12)
                .fontColor(Theme.colors.textSecondary)
            }
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .onClick(() => {
              if (this.onOpenActions) {
                this.onOpenActions(this.message);
              }
            })
          }
          .width('100%')
        }

        if (this.message.isTransient) {
          Blank().height(8)
          Row() {
            Text('⏳ 生成中...')
              .fontSize(12)
              .fontColor('#E8B86C')
          }
        }
      }
      .width('95%')
      .padding(14)
      .borderRadius(14)
      .backgroundColor('#F8F6F0')
    }
    .width('100%')
    .justifyContent(FlexAlign.Start)
    .padding({ left: 8, right: 8, top: 4, bottom: 4 })
  }

  @Builder
  buildSystemMessage() {
    Row() {
      Text(this.message.content ? this.message.content : '')
        .fontSize(12)
        .fontColor(Theme.colors.textSecondary)
        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        .backgroundColor('#F5F5F5')
        .borderRadius(4)
    }
    .width('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ top: 4, bottom: 4 })
  }

  @Builder
  buildNormalMessage() {
    Column() {
      // 用户名和时间
      if (!this.isOwn) {
        Row() {
          Text(this.message.userName ? this.message.userName : '用户')
            .fontSize(12)
            .fontWeight(FontWeight.Medium)
            .fontColor(SilkColors.primary)
          
          Text(' · ' + this.formatTime(this.message.timestamp))
            .fontSize(12)
            .fontColor(Theme.colors.textLight)
        }
        .width('100%')
        .margin({ bottom: 4, left: 4 })
      }

      // 消息内容（转发：卡片式标题 + Markdown 正文）
      Row() {
        if (this.isForwardedContent()) {
          Column() {
            this.buildForwardedBubbleBody()
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 12, bottom: 12 })
          .borderRadius({
            topLeft: 12,
            topRight: 12,
            bottomLeft: this.isOwn ? 12 : 4,
            bottomRight: this.isOwn ? 4 : 12
          })
          .border({
            width: { left: 3 },
            color: SilkColors.primary
          })
          .backgroundColor(this.getForwardedBubbleBackground())
        } else {
          Column() {
            Text(this.message.content ? this.message.content : '')
              .fontSize(14)
              .fontColor(this.getMessageTextColor())
              .maxLines(100)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .padding(12)
          .borderRadius({
            topLeft: 12,
            topRight: 12,
            bottomLeft: this.isOwn ? 12 : 4,
            bottomRight: this.isOwn ? 4 : 12
          })
          .backgroundColor(this.getMessageBackground())
        }
      }
      .width('100%')
      .justifyContent(this.isOwn ? FlexAlign.End : FlexAlign.Start)

      if (this.isOwn) {
        Row() {
          if (this.messageStatus === 'sending') {
            Text('发送中...')
              .fontSize(11)
              .fontColor(Theme.colors.textLight)
          } else if (this.messageStatus === 'failed') {
            Text('发送失败')
              .fontSize(11)
              .fontColor('#D9534F')
            Text(' · 重试')
              .fontSize(11)
              .fontColor(Theme.colors.primary)
              .onClick(() => {
                if (this.onRetry) {
                  this.onRetry(this.message);
                }
              })
          } else {
            Text(this.formatTime(this.message.timestamp))
              .fontSize(11)
              .fontColor(Theme.colors.textLight)
          }
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 4, right: 4 })
      }

      if (this.canShowNormalActions() && this.messageStatus !== 'sending') {
        Row() {
          Blank()
          this.buildQuickAction('复制', () => {
            if (this.onCopy) {
              this.onCopy(this.message.content ? this.message.content : '');
            }
          })
          Blank().width(6)
          this.buildQuickAction('更多', () => {
            if (this.onOpenActions) {
              this.onOpenActions(this.message);
            }
          })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 2, right: 4 })
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 4, bottom: 4 })
    .alignItems(this.isOwn ? HorizontalAlign.End : HorizontalAlign.Start)
  }

  private getForwardedBubbleBackground(): ResourceColor {
    if (this.isOwn) {
      return '#EDE4D4';
    }
    return '#FAF7F2';
  }

  private getMessageBackground(): ResourceColor {
    if (this.message.category === MessageCategory.FINAL_REPORT) {
      return this.isOwn ? Theme.colors.primary : Theme.colors.cardBackground;
    }
    if (this.message.category === MessageCategory.STEP_PROCESS ||
        this.message.category === MessageCategory.TODO_LIST) {
      return this.isOwn ? '#E8D5B5' : '#F5F5F5';
    }
    return this.isOwn ? Theme.colors.primaryLight : Theme.colors.cardBackground;
  }

  private getMessageTextColor(): ResourceColor {
    if (this.message.category === MessageCategory.AGENT_STATUS) {
      return Theme.colors.textLight;
    }
    if (this.message.category === MessageCategory.FINAL_REPORT) {
      return Theme.colors.primary;
    }
    return Theme.colors.textPrimary;
  }

  // -- 元服务快捷按钮 --

  private getActionButtonIcon(): string {
    if (!this.suggestedAction) {
      return '';
    }
    if (this.suggestedAction.serviceType === 'parameter') {
      return '🚗';
    }
    return '🛒';
  }

  private getActionButtonLabel(): string {
    if (!this.suggestedAction) {
      return '';
    }
    if (this.suggestedAction.serviceType === 'parameter') {
      const dest = this.suggestedAction.params
        ? this.suggestedAction.params['destination'] || ''
        : '';
      if (dest) {
        return `打开 ${this.suggestedAction.serviceName} · 去${dest}`;
      }
    }
    const kw = this.suggestedAction.keywords
      ? this.suggestedAction.keywords.join(' ')
      : '';
    if (kw) {
      return `${this.suggestedAction.serviceName} · ${kw}`;
    }
    return `打开 ${this.suggestedAction.serviceName}`;
  }

  private getActionButtonColor(): ResourceColor {
    if (!this.suggestedAction) {
      return '#C9A86C';
    }
    if (this.suggestedAction.serviceType === 'parameter') {
      return '#4A90D9';
    }
    return '#C9A86C';
  }

  private handleSuggestedAction(): void {
    if (!this.suggestedAction) {
      return;
    }
    if (this.suggestedAction.serviceType === 'parameter') {
      const params: Record<string, string> = {};
      if (this.metaParamPickup.length > 0) {
        params['pickup'] = this.metaParamPickup;
      }
      if (this.metaParamDestination.length > 0) {
        params['destination'] = this.metaParamDestination;
      }
      const intent: MetaServiceIntent = {
        serviceId: this.suggestedAction.serviceId,
        serviceName: this.suggestedAction.serviceName,
        serviceType: 'parameter',
        params: params
      };
      MetaServiceLauncher.launch(intent).then((result: LaunchResult) => {
        if (!result.success) {
          promptAction.showToast({
            message: result.message,
            duration: 3000
          });
        }
      });
      return;
    }
    MetaServiceLauncher.launch(this.suggestedAction).then((result: LaunchResult) => {
      if (!result.success) {
        promptAction.showToast({
          message: result.message,
          duration: 3000
        });
      }
    });
  }

  private formatTime(timestamp: number): string {
    const date = new Date(timestamp);
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${hours}:${minutes}`;
  }
}
