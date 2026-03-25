/**
 * 轻量 Markdown 渲染组件（Harmony 稳定版）
 * 纯 ArkUI 渲染，避免 WebView 带来的闪退与加载不确定性。
 */
import { Theme } from '../common/Theme';

interface MarkdownBlock {
  kind: string; // heading | bullet | ordered | quote | code | divider | text | empty
  text: string;
  level: number;
}

/**
 * 检测内容是否包含需 KaTeX/Web 渲染的数学公式（与 MarkdownWeb 搭配使用）。
 */
export function contentNeedsMathWebRender(content: string): boolean {
  const s = content ? content : '';
  if (s.indexOf('$$') >= 0) {
    return true;
  }
  if (s.indexOf('\\[') >= 0 || s.indexOf('\\(') >= 0) {
    return true;
  }
  let dollarCount = 0;
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '$') {
      dollarCount++;
    }
  }
  return dollarCount >= 2;
}

@Component
export struct MarkdownLite {
  @Prop content: string = '';

  private getBlocks(): MarkdownBlock[] {
    const source = this.content ? this.content.replace(/\r/g, '') : '';
    const lines = source.split('\n');
    const blocks: MarkdownBlock[] = [];
    let inCodeBlock = false;
    let codeBuffer = '';

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const trimmed = line.trim();
      if (trimmed.startsWith('```')) {
        if (inCodeBlock) {
          blocks.push({ kind: 'code', text: codeBuffer, level: 0 });
          codeBuffer = '';
          inCodeBlock = false;
        } else {
          inCodeBlock = true;
        }
        continue;
      }

      if (inCodeBlock) {
        if (codeBuffer.length > 0) {
          codeBuffer += '\n';
        }
        codeBuffer += line;
        continue;
      }

      if (trimmed.length === 0) {
        if (blocks.length > 0 && blocks[blocks.length - 1].kind === 'empty') {
          continue;
        }
        blocks.push({ kind: 'empty', text: '', level: 0 });
        continue;
      }

      const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
      if (headingMatch) {
        const marks = headingMatch[1];
        const title = headingMatch[2];
        blocks.push({ kind: 'heading', text: this.cleanInlineMarkdown(title), level: marks.length });
        continue;
      }

      if (trimmed.match(/^(-{3,}|\*{3,}|_{3,})$/)) {
        blocks.push({ kind: 'divider', text: '', level: 0 });
        continue;
      }

      const quoteMatch = trimmed.match(/^>\s?(.*)$/);
      if (quoteMatch) {
        const quoteText = quoteMatch[1] ? quoteMatch[1] : '';
        const cleaned = this.cleanInlineMarkdown(quoteText);
        if (blocks.length > 0 && blocks[blocks.length - 1].kind === 'quote') {
          const last = blocks[blocks.length - 1];
          last.text = last.text + '\n' + cleaned;
        } else {
          blocks.push({ kind: 'quote', text: cleaned, level: 0 });
        }
        continue;
      }

      const bulletMatch = trimmed.match(/^[-*+]\s+(.+)$/);
      if (bulletMatch) {
        const itemText = bulletMatch[1];
        blocks.push({ kind: 'bullet', text: this.cleanInlineMarkdown(itemText), level: 0 });
        continue;
      }

      const orderedMatch = trimmed.match(/^(\d+)\.\s+(.+)$/);
      if (orderedMatch) {
        const orderedText = `${orderedMatch[1]}. ${orderedMatch[2]}`;
        blocks.push({ kind: 'ordered', text: this.cleanInlineMarkdown(orderedText), level: 0 });
        continue;
      }

      blocks.push({ kind: 'text', text: this.cleanInlineMarkdown(line), level: 0 });
    }

    if (inCodeBlock && codeBuffer.length > 0) {
      blocks.push({ kind: 'code', text: codeBuffer, level: 0 });
    }

    return blocks;
  }

  private cleanInlineMarkdown(text: string): string {
    let cleaned = text ? text : '';
    cleaned = cleaned.replace(/!\[([^\]]*)\]\([^)]+\)/g, '[图片]');
    cleaned = cleaned.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '$1');
    cleaned = cleaned.replace(/\*\*(.*?)\*\*/g, '$1');
    cleaned = cleaned.replace(/__(.*?)__/g, '$1');
    cleaned = cleaned.replace(/\*(.*?)\*/g, '$1');
    cleaned = cleaned.replace(/_(.*?)_/g, '$1');
    cleaned = cleaned.replace(/`([^`]+)`/g, '$1');
    return cleaned;
  }

  private headingFontSize(level: number): number {
    if (level <= 1) {
      return 19;
    }
    if (level === 2) {
      return 17;
    }
    if (level === 3) {
      return 16;
    }
    return 15;
  }

  @Builder
  private renderBlock(block: MarkdownBlock) {
    if (block.kind === 'empty') {
      Blank().height(4);
    } else if (block.kind === 'divider') {
      Row()
        .width('100%')
        .height(1)
        .backgroundColor('#22000000')
        .margin({ top: 6, bottom: 6 });
    } else if (block.kind === 'heading') {
      Text(block.text)
        .fontSize(this.headingFontSize(block.level))
        .fontWeight(FontWeight.Medium)
        .fontColor(Theme.colors.textPrimary)
        .lineHeight(24)
        .margin({ top: 4, bottom: 2 });
    } else if (block.kind === 'quote') {
      Text(block.text)
        .width('100%')
        .fontSize(14)
        .fontColor(Theme.colors.textSecondary)
        .lineHeight(22)
        .padding({ left: 10, top: 6, bottom: 6, right: 8 })
        .border({ width: { left: 3 }, color: '#BFA16A' })
        .backgroundColor('#0FBFA16A')
        .borderRadius(6)
        .margin({ top: 2, bottom: 2 });
    } else if (block.kind === 'code') {
      Text(block.text)
        .width('100%')
        .fontSize(13)
        .fontColor('#E8EDF4')
        .fontFamily('monospace')
        .lineHeight(20)
        .padding(10)
        .backgroundColor('#1F2937')
        .borderRadius(8)
        .margin({ top: 2, bottom: 2 });
    } else if (block.kind === 'bullet') {
      Row() {
        Text('• ')
          .fontSize(14)
          .fontColor('#BFA16A')
          .margin({ top: 1 });
        Text(block.text)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .lineHeight(22)
      }
      .width('100%')
      .margin({ top: 1, bottom: 1 });
    } else if (block.kind === 'ordered') {
      Text(block.text)
        .fontSize(14)
        .fontColor(Theme.colors.textPrimary)
        .lineHeight(22)
        .margin({ top: 1, bottom: 1 });
    } else {
      Text(block.text)
        .fontSize(14)
        .fontColor(Theme.colors.textPrimary)
        .lineHeight(22)
        .margin({ top: 1, bottom: 1 });
    }
  }

  build() {
    Column() {
      ForEach(this.getBlocks(), (block: MarkdownBlock, index?: number) => {
        this.renderBlock(block);
      }, (block: MarkdownBlock, index?: number) => `${block.kind}_${index !== undefined ? index : 0}`);
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
