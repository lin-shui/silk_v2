/**
 * AudioDuplexSession - 音频全双工 WebSocket 会话管理器
 * 连接 Silk 后端代理，与 MiniCPM-o 全双工语音服务通信
 */
import webSocket from '@ohos.net.webSocket';
import { BusinessError } from '@ohos.base';
import { Logger } from '../common/Logger';

const TAG = 'AudioDuplexSession';

export enum DuplexState {
  IDLE = 'idle',
  CONNECTING = 'connecting',
  QUEUED = 'queued',
  PREPARING = 'preparing',
  STREAMING = 'streaming',
  DISCONNECTED = 'disconnected',
  ERROR = 'error'
}

/** 服务器发来的 result 消息 */
export interface DuplexResult {
  audioData: string;
  text: string;
  isListen: boolean;
  endOfTurn: boolean;
}

export interface DuplexSessionListener {
  onStateChanged: (state: DuplexState) => void;
  onPrepared: () => void;
  onResult: (result: DuplexResult) => void;
  onAudioOnly: (audioBase64: string) => void;
  onError: (message: string) => void;
  onDisconnected: () => void;
}

export class AudioDuplexSession {
  private ws: webSocket.WebSocket | null = null;
  private state: DuplexState = DuplexState.IDLE;
  private sessionId: string = '';
  private serverUrl: string = '';
  private listener: DuplexSessionListener | null = null;

  get currentState(): DuplexState {
    return this.state;
  }

  get currentSessionId(): string {
    return this.sessionId;
  }

  setListener(listener: DuplexSessionListener): void {
    this.listener = listener;
  }

  private setState(newState: DuplexState): void {
    this.state = newState;
    if (this.listener) {
      this.listener.onStateChanged(newState);
    }
  }

  /**
   * 连接到后端代理服务
   */
  connect(serverUrl: string, sessionId: string): void {
    if (this.ws) {
      this.disconnect();
    }

    this.sessionId = sessionId;
    this.serverUrl = serverUrl;
    const wsUrl = `${serverUrl}/ws/audio-duplex?sessionId=${sessionId}`;

    this.setState(DuplexState.CONNECTING);
    Logger.info(TAG, `连接音频双工: ${wsUrl}`);

    try {
      this.ws = webSocket.createWebSocket();

      this.ws.on('open', (_err: BusinessError | null, _data: Object): void => {
        Logger.info(TAG, '双工 WebSocket 已连接');
        this.setState(DuplexState.PREPARING);
      });

      this.ws.on('message', (_err: BusinessError | null, data: string | ArrayBuffer): void => {
        this.handleMessage(data);
      });

      this.ws.on('close', (_err: BusinessError | null, _data: webSocket.CloseResult): void => {
        Logger.info(TAG, '双工 WebSocket 已关闭');
        this.setState(DuplexState.DISCONNECTED);
        if (this.listener) {
          this.listener.onDisconnected();
        }
      });

      this.ws.on('error', (err: BusinessError): void => {
        Logger.error(TAG, `双工 WebSocket 错误: ${err.message}`);
        this.setState(DuplexState.ERROR);
        if (this.listener) {
          this.listener.onError(err.message);
        }
      });

      this.ws.connect(wsUrl).catch((err: BusinessError) => {
        Logger.error(TAG, `双工 WebSocket 连接失败: ${err.message}`);
        this.setState(DuplexState.ERROR);
        if (this.listener) {
          this.listener.onError('连接失败: ' + err.message);
        }
      });
    } catch (e) {
      Logger.error(TAG, `创建双工 WebSocket 失败: ${JSON.stringify(e)}`);
      this.setState(DuplexState.ERROR);
      if (this.listener) {
        this.listener.onError('创建连接失败');
      }
    }
  }

  private handleMessage(data: string | ArrayBuffer): void {
    if (typeof data !== 'string') {
      return;
    }

    try {
      const msg: Record<string, Object> = JSON.parse(data);
      const typeValue: Object | undefined = msg['type'];
      const typeStr: string | undefined = typeof typeValue === 'string' ? typeValue : undefined;

      if (!typeStr) {
        return;
      }

      switch (typeStr) {
        case 'queued':
        case 'queue_update':
          this.setState(DuplexState.QUEUED);
          break;

        case 'queue_done':
          Logger.info(TAG, '排队完成，等待 prepare');
          break;

        case 'prepared':
          Logger.info(TAG, '双工会话已准备就绪');
          this.setState(DuplexState.STREAMING);
          if (this.listener) {
            this.listener.onPrepared();
          }
          break;

        case 'result':
          this.handleResult(msg);
          break;

        case 'audio_only':
          if (this.listener) {
            const ad: Object | undefined = msg['audio_data'];
            if (typeof ad === 'string') {
              this.listener.onAudioOnly(ad);
            }
          }
          break;

        case 'error':
          {
            const errMsg: Object | undefined = msg['error'];
            const errText: string = typeof errMsg === 'string' ? errMsg : '服务错误';
            Logger.error(TAG, `双工服务错误: ${errText}`);
            if (this.listener) {
              this.listener.onError(errText);
            }
          }
          break;

        case 'paused':
          Logger.info(TAG, '双工已暂停');
          break;

        case 'resumed':
          Logger.info(TAG, '双工已恢复');
          break;

        case 'stopped':
          Logger.info(TAG, '双工已停止');
          this.disconnect();
          break;
      }
    } catch (e) {
      Logger.error(TAG, `解析消息失败: ${e}`);
    }
  }

  private handleResult(msg: Record<string, Object>): void {
    if (!this.listener) {
      return;
    }

    const audioDataValue: Object | undefined = msg['audio_data'];
    const textValue: Object | undefined = msg['text'];
    const audioData: string = typeof audioDataValue === 'string' ? audioDataValue : '';
    const text: string = typeof textValue === 'string' ? textValue : '';
    const isListen: boolean = msg['is_listen'] === true;
    const endOfTurn: boolean = msg['end_of_turn'] === true;

    const result: DuplexResult = {
      audioData: audioData,
      text: text,
      isListen: isListen,
      endOfTurn: endOfTurn
    };

    this.listener.onResult(result);
  }

  /**
   * 发送 prepare 消息启动会话
   */
  prepare(systemPrompt: string): void {
    if (!this.ws) {
      return;
    }
    try {
      this.ws.send(JSON.stringify({ type: 'prepare', system_prompt: systemPrompt })).catch((err: Error) => {
        Logger.error(TAG, `发送 prepare 失败: ${err.message}`);
      });
    } catch (e) {
      Logger.error(TAG, `发送 prepare 序列化失败: ${e}`);
    }
  }

  /**
   * 发送音频块（1s 16kHz float32 PCM base64）
   */
  sendAudioChunk(audioBase64: string): void {
    if (!this.ws) {
      return;
    }
    try {
      this.ws.send(JSON.stringify({ type: 'audio_chunk', audio_base64: audioBase64 })).catch((err: Error) => {
        Logger.error(TAG, `发送音频块失败: ${err.message}`);
      });
    } catch (e) {
      Logger.error(TAG, `发送音频块序列化失败: ${e}`);
    }
  }

  /**
   * 停止双工会话
   */
  sendStop(): void {
    if (!this.ws) {
      return;
    }
    try {
      this.ws.send(JSON.stringify({ type: 'stop' })).catch((err: Error) => {
        Logger.error(TAG, `发送 stop 失败: ${err.message}`);
      });
    } catch (e) {
      Logger.error(TAG, `发送 stop 序列化失败: ${e}`);
    }
  }

  /**
   * 断开连接并清理
   */
  disconnect(): void {
    try {
      if (this.ws) {
        this.ws.off('open');
        this.ws.off('message');
        this.ws.off('close');
        this.ws.off('error');
        this.ws.close().catch((err: Error) => {
          Logger.error(TAG, `断开连接 close 异常: ${err.message}`);
        });
        this.ws = null;
      }
    } catch (e) {
      Logger.error(TAG, `断开连接异常: ${e}`);
    }
    this.setState(DuplexState.DISCONNECTED);
    this.sessionId = '';
  }
}
