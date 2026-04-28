/**
 * AudioDuplexPage - 音频全双工 Tab 页面
 * 提供一键式语音对话界面，使用 MiniCPM-o 全双工能力
 */
import { AudioDuplexSession, DuplexState, DuplexResult, DuplexSessionListener } from '../api/AudioDuplexSession';
import { AudioDuplexCapturer } from '../common/AudioDuplexCapturer';
import { AudioDuplexPlayer } from '../common/AudioDuplexPlayer';
import { Logger } from '../common/Logger';
import { SilkColors } from '../common/Theme';
import { WS_BASE_URL } from '../common/Constants';
import { getGlobalContext } from '../entryability/EntryAbility';
import abilityAccessCtrl, { Permissions, PermissionRequestResult } from '@ohos.abilityAccessCtrl';
import promptAction from '@ohos.promptAction';

const TAG = 'AudioDuplexPage';

/** 对话记录条目 */
interface TranscriptItem {
  role: string;
  text: string;
  timestamp: number;
}

@Component
export struct AudioDuplexPage {
  @State private duplexState: DuplexState = DuplexState.IDLE;
  @State private statusText: string = '点击开始语音对话';
  @State private transcript: TranscriptItem[] = [];
  @State private elapsedTime: number = 0;

  private session: AudioDuplexSession = new AudioDuplexSession();
  private capturer: AudioDuplexCapturer = new AudioDuplexCapturer();
  private player: AudioDuplexPlayer = new AudioDuplexPlayer();
  private timerInterval: number = -1;
  private accumulatedText: string = '';
  private wsServer: string = WS_BASE_URL;
  private isCleaningUp: boolean = false;

  aboutToDisappear(): void {
    this.stopSession();
  }

  build() {
    Column() {
      // 对话记录列表
      if (this.transcript.length > 0) {
        List() {
          ForEach(this.transcript, (item: TranscriptItem, index: number) => {
            ListItem() {
              this.buildTranscriptItem(item);
            }
          }, (item: TranscriptItem, index: number) => {
            return `transcript_${item.timestamp}_${index}`;
          })
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 16, right: 16, top: 8 })
      } else {
        // 空状态提示
        Column() {
          Text('📞')
            .fontSize(48)
            .opacity(0.3)
          Text('点击下方按钮开始语音对话')
            .fontSize(14)
            .fontColor(SilkColors.textLight)
            .margin({ top: 8 })
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      }

      // 状态文字
      Text(this.statusText)
        .fontSize(14)
        .fontColor(this.getStatusColor())
        .margin({ bottom: 12 })

      // 计时（对话中）
      if (this.duplexState === DuplexState.STREAMING) {
        Text(this.formatTime(this.elapsedTime))
          .fontSize(12)
          .fontColor(SilkColors.textLight)
          .margin({ bottom: 8 })
      }

      // 大圆形按钮
      Button() {
        Text('📞')
          .fontSize(48)
      }
      .width(100)
      .height(100)
      .borderRadius(50)
      .backgroundColor(this.getButtonColor())
      .onClick(() => {
        this.handleButtonClick();
      })
      .margin({ bottom: 40 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
  }

  @Builder
  buildTranscriptItem(item: TranscriptItem): void {
    Row() {
      Text(item.role === 'ai' ? '🤖' : '🧑')
        .fontSize(20)
        .margin({ right: 8 })
      Text(item.text)
        .fontSize(14)
        .fontColor(SilkColors.textPrimary)
        .wordBreak(WordBreak.BREAK_WORD)
    }
    .width('100%')
    .padding(12)
    .margin({ bottom: 6 })
    .backgroundColor(item.role === 'ai'
      ? SilkColors.surfaceVariant
      : SilkColors.surface)
    .borderRadius(12)
    .alignItems(VerticalAlign.Top)
  }

  private getButtonColor(): ResourceColor {
    switch (this.duplexState) {
      case DuplexState.STREAMING:
        return '#FF4D4F';
      case DuplexState.CONNECTING:
      case DuplexState.PREPARING:
      case DuplexState.QUEUED:
        return '#FAAD14';
      case DuplexState.ERROR:
      case DuplexState.DISCONNECTED:
        return '#999999';
      default:
        return SilkColors.primary;
    }
  }

  private getStatusColor(): ResourceColor {
    if (this.duplexState === DuplexState.STREAMING) {
      return '#FF4D4F';
    }
    if (this.duplexState === DuplexState.ERROR) {
      return '#FF4D4F';
    }
    return SilkColors.textLight;
  }

  private formatTime(totalSeconds: number): string {
    const min = Math.floor(totalSeconds / 60);
    const sec = totalSeconds % 60;
    return `${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
  }

  private startTimer(): void {
    this.elapsedTime = 0;
    this.timerInterval = setInterval(() => {
      this.elapsedTime++;
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timerInterval !== -1) {
      clearInterval(this.timerInterval);
      this.timerInterval = -1;
    }
    this.elapsedTime = 0;
  }

  private async handleButtonClick(): Promise<void> {
    if (this.duplexState === DuplexState.STREAMING) {
      this.stopSession();
    } else if (this.duplexState === DuplexState.IDLE
      || this.duplexState === DuplexState.DISCONNECTED
      || this.duplexState === DuplexState.ERROR) {
      await this.startSession();
    }
  }

  private async startSession(): Promise<void> {
    try {
      const granted = await this.requestMicPermission();
      if (!granted) {
        promptAction.showToast({ message: '需要麦克风权限', duration: 2000 });
        return;
      }

      const sessionId = `adx_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
      this.transcript = [];
      this.accumulatedText = '';

      await this.player.init();

      this.capturer.setOnChunk((base64Pcm: string) => {
        this.session.sendAudioChunk(base64Pcm);
      });
      await this.capturer.start();

      this.session.setListener(this.createListener());
      this.session.connect(this.wsServer, sessionId);

      this.startTimer();
    } catch (e) {
      Logger.error(TAG, `启动双工失败: ${e}`);
      promptAction.showToast({ message: '启动失败，请重试', duration: 2000 });
    }
  }

  private createListener(): DuplexSessionListener {
    return {
      onStateChanged: (state: DuplexState): void => {
        this.duplexState = state;
        switch (state) {
          case DuplexState.CONNECTING:
            this.statusText = '连接中…';
            break;
          case DuplexState.QUEUED:
            this.statusText = '排队等待中…';
            break;
          case DuplexState.PREPARING:
            this.statusText = '准备中…';
            this.session.prepare('你是一个智能语音助手，请用中文回答用户问题。保持自然、友好的对话风格。');
            break;
          case DuplexState.STREAMING:
            this.statusText = '对话中，点击结束';
            break;
          case DuplexState.ERROR:
            this.statusText = '连接异常';
            this.cleanup();
            break;
          case DuplexState.DISCONNECTED:
            this.statusText = '已断开';
            this.cleanup();
            break;
        }
      },

      onPrepared: (): void => {
        Logger.info(TAG, '双工会话已就绪');
      },

      onResult: (result: DuplexResult): void => {
        if (result.text) {
          this.accumulatedText += result.text;
        }

        if (result.isListen && this.accumulatedText.length > 0) {
          this.addTranscript('ai', this.accumulatedText);
          this.accumulatedText = '';
        }

        if (result.audioData) {
          this.player.enqueueAudio(result.audioData);
        }
      },

      onAudioOnly: (audioBase64: string): void => {
        this.player.enqueueAudio(audioBase64);
      },

      onError: (message: string): void => {
        Logger.error(TAG, `双工错误: ${message}`);
        promptAction.showToast({ message: '连接已断开', duration: 2000 });
        this.cleanup();
      },

      onDisconnected: (): void => {
        if (this.accumulatedText.length > 0) {
          this.addTranscript('ai', this.accumulatedText);
          this.accumulatedText = '';
        }
        this.cleanup();
      }
    };
  }

  private addTranscript(role: string, text: string): void {
    const item: TranscriptItem = {
      role: role,
      text: text,
      timestamp: Date.now()
    };
    this.transcript.push(item);
  }

  private stopSession(): void {
    Logger.info(TAG, '用户结束双工会话');
    this.session.sendStop();
    this.cleanup();
  }

  private cleanup(): void {
    if (this.isCleaningUp) return;
    this.isCleaningUp = true;
    this.stopTimer();
    this.duplexState = DuplexState.IDLE;
    this.statusText = '点击开始语音对话';
    this.accumulatedText = '';

    this.capturer.stop().catch((e: Error) => {
      Logger.error(TAG, `cleanup capturer error: ${e.message}`);
    });
    this.session.disconnect();
    this.player.flush();
    this.isCleaningUp = false;
  }

  private async requestMicPermission(): Promise<boolean> {
    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const ctx = getGlobalContext();
      if (ctx === null) {
        return false;
      }
      const permList: Permissions[] = ['ohos.permission.MICROPHONE'];
      const result: PermissionRequestResult = await atManager.requestPermissionsFromUser(ctx, permList);
      return result.authResults[0] === 0;
    } catch (e) {
      Logger.error(TAG, `请求麦克风权限失败: ${e}`);
      return false;
    }
  }
}
