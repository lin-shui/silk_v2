/**
 * ChatStore - 聊天状态管理
 * 管理消息列表、连接状态、当前群组等
 */
import { Message, MessageType, MessageCategory, parseMessage } from '../models/Message';
import { Group } from '../models/Group';
import { WebSocketClient, ConnectionState } from '../api/WebSocketClient';
import { Constants } from '../common/Constants';
import { ConnectionListener } from '../models/ConnectionState';

class ChatStoreConnectionListener implements ConnectionListener {
  private readonly owner: ChatStore;

  constructor(owner: ChatStore) {
    this.owner = owner;
  }

  onMessageReceived(message: Message): void {
    this.owner.handleSocketMessage(message);
  }

  onConnectionStateChanged(state: string): void {
    this.owner.handleSocketState(state);
  }
}

export class ChatStore {
  // 当前群组
  private _currentGroup: Group | null = null;
  get currentGroup(): Group | null {
    return this._currentGroup;
  }
  set currentGroup(group: Group | null) {
    this._currentGroup = group;
  }

  // 消息列表
  private _messages: Message[] = [];
  get messages(): Message[] {
    return this._messages;
  }

  // 临时消息（AI 正在输入）
  private _transientMessage: Message | null = null;
  get transientMessage(): Message | null {
    return this._transientMessage;
  }

  // 状态消息（Agent 工作状态）
  private _statusMessages: Message[] = [];
  get statusMessages(): Message[] {
    return this._statusMessages;
  }

  // 连接状态
  private _connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  get connectionState(): ConnectionState {
    return this._connectionState;
  }

  // AI 正在生成回复
  private _isGenerating: boolean = false;
  get isGenerating(): boolean {
    return this._isGenerating;
  }

  // 停止后抑制残余流式消息
  private _suppressTransient: boolean = false;

  // WebSocket 客户端
  private wsClient: WebSocketClient | null = null;
  private wsListener: ConnectionListener | null = null;

  // 当前用户信息
  private currentUserId: string = '';
  private currentUserName: string = '';

  // 回调函数
  public onMessagesChanged: (() => void) | null = null;
  public onConnectionStateChanged: (() => void) | null = null;
  public onError: ((error: string) => void) | null = null;

  /**
   * 连接到聊天服务器
   */
  connect(userId: string, userName: string, groupId: string): void {
    this.currentUserId = userId;
    this.currentUserName = userName;

    // 断开现有连接
    this.disconnect();

    // 创建新的 WebSocket 客户端
    const wsUrl: string = Constants.WS_BASE_URL;
    this.wsClient = new WebSocketClient();

    const listener: ConnectionListener = new ChatStoreConnectionListener(this);
    this.wsListener = listener;
    this.wsClient.addListener(listener);

    // 开始连接
    this._connectionState = ConnectionState.CONNECTING;
    this.onConnectionStateChanged?.();
    this.wsClient.connect(wsUrl, userId, userName, groupId)
      .catch((error: Error) => {
        this.onError?.(error.message);
      });
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.wsClient) {
      if (this.wsListener) {
        this.wsClient.removeListener(this.wsListener);
      }
      this.wsClient.disconnect();
      this.wsClient = null;
      this.wsListener = null;
    }
    this._connectionState = ConnectionState.DISCONNECTED;
    this._isGenerating = false;
    this._suppressTransient = false;
    this.onConnectionStateChanged?.();
  }

  /**
   * 发送消息
   */
  sendMessage(content: string): Message | null {
    if (!this.wsClient || this._connectionState !== ConnectionState.CONNECTED) {
      this.onError?.('未连接到服务器');
      return null;
    }

    this._suppressTransient = false;

    const message: Message = {
      id: this.generateId(),
      userId: this.currentUserId,
      userName: this.currentUserName,
      content: content,
      timestamp: Date.now(),
      type: MessageType.TEXT,
      isTransient: false,
      isIncremental: false,
      category: MessageCategory.NORMAL
    };

    // 乐观更新：立即显示消息
    this._messages = this._messages.concat([message]);
    this.onMessagesChanged?.();

    // 发送到服务器
    const sent = this.wsClient.send(message);
    if (!sent) {
      this.onError?.('消息发送失败');
      return null;
    }
    return message;
  }

  /**
   * 停止 AI 生成：保留已流式输出的内容，发送停止信号给后端
   */
  stopGeneration(): void {
    if (!this.wsClient || this._connectionState !== ConnectionState.CONNECTED) {
      return;
    }

    // 保留已流式输出的部分内容为正式消息
    const transient = this._transientMessage;
    if (transient && transient.content.length > 0) {
      const partialMessage: Message = {
        id: transient.id,
        userId: transient.userId,
        userName: transient.userName,
        content: transient.content + '\n\n*(已停止生成)*',
        timestamp: transient.timestamp,
        type: transient.type,
        isTransient: false,
        isIncremental: false,
        category: transient.category
      };
      this._messages = this._messages.concat([partialMessage]);
    }

    // 发送停止信号给后端
    const stopMessage: Message = {
      id: this.generateId(),
      userId: this.currentUserId,
      userName: this.currentUserName,
      content: '',
      timestamp: Date.now(),
      type: MessageType.STOP_GENERATE,
      isTransient: true,
      isIncremental: false,
      category: MessageCategory.NORMAL
    };
    this.wsClient.send(stopMessage);

    this._suppressTransient = true;
    this._isGenerating = false;
    this._transientMessage = null;
    this._statusMessages = [];
    this.onMessagesChanged?.();
  }

  /**
   * 重试发送已存在的消息
   */
  retryMessage(message: Message): boolean {
    if (!this.wsClient || this._connectionState !== ConnectionState.CONNECTED) {
      this.onError?.('未连接到服务器');
      return false;
    }
    return this.wsClient.send(message);
  }

  /**
   * 处理收到的消息
   */
  private handleMessage(data: string | Message): void {
    try {
      const message: Message = typeof data === 'string' ? parseMessage(data) : data;
      const isSilkAi: boolean = message.userId === 'silk_ai_agent';

      // 停止后抑制残余流式消息（允许 CLEAR_STATUS 通过）
      if (this._suppressTransient && isSilkAi && message.isTransient) {
        if (message.category === MessageCategory.AGENT_STATUS &&
          message.content.startsWith('CLEAR_STATUS')) {
          this._statusMessages = [];
          this.onMessagesChanged?.();
        }
        return;
      }

      // 撤回消息
      if (message.type === MessageType.RECALL) {
        const idsToRemove = message.content.split(',').map(id => id.trim());
        this._messages = this._messages.filter(msg => !idsToRemove.includes(msg.id));
        const transient = this._transientMessage;
        if (transient && idsToRemove.includes(transient.id)) {
          this._transientMessage = null;
        }
        this.onMessagesChanged?.();
        return;
      }

      // Agent 状态消息
      if (message.category === MessageCategory.AGENT_STATUS) {
        if (message.content.startsWith('CLEAR_STATUS')) {
          this._statusMessages = [];
        } else {
          if (isSilkAi) {
            this._isGenerating = true;
          }
          const withoutExisting: Message[] = this._statusMessages.filter(msg => msg.id !== message.id);
          const nextStatusMessages: Message[] = withoutExisting.concat([message]);
          if (nextStatusMessages.length > 10) {
            const startIndex = nextStatusMessages.length - 10;
            const result: Message[] = [];
            for (let i = startIndex; i < nextStatusMessages.length; i++) {
              result.push(nextStatusMessages[i]);
            }
            this._statusMessages = result;
          } else {
            this._statusMessages = nextStatusMessages;
          }
        }
        this.onMessagesChanged?.();
        return;
      }

      // 增量临时消息
      if (message.isTransient && message.isIncremental) {
        if (isSilkAi) {
          this._isGenerating = true;
        }
        const existing = this._transientMessage;
        if (existing && existing.userId === message.userId && existing.type === message.type) {
          const mergedMessage: Message = {
            id: existing.id,
            userId: existing.userId,
            userName: existing.userName,
            content: `${existing.content}${message.content}`,
            timestamp: message.timestamp,
            type: existing.type,
            isTransient: true,
            isIncremental: true,
            category: existing.category,
            currentStep: message.currentStep,
            totalSteps: message.totalSteps
          };
          this._transientMessage = mergedMessage;
        } else {
          this._transientMessage = message;
        }
        this.onMessagesChanged?.();
        return;
      }

      // 完整临时消息
      if (message.isTransient) {
        this._transientMessage = message;
        this.onMessagesChanged?.();
        return;
      }

      // 普通消息（非临时）
      const exists = this._messages.some(msg => msg.id === message.id);
      if (!exists) {
        this._messages = this._messages.concat([message]);
      }
      this._transientMessage = null;
      this._statusMessages = [];
      this._isGenerating = false;
      this._suppressTransient = false;
      this.onMessagesChanged?.();

    } catch (e) {
      console.error('解析消息失败:', e);
    }
  }

  handleSocketMessage(message: Message): void {
    this.handleMessage(message);
  }

  handleSocketState(state: string): void {
    if (state === ConnectionState.CONNECTED) {
      this._connectionState = ConnectionState.CONNECTED;
    } else if (state === ConnectionState.CONNECTING) {
      this._connectionState = ConnectionState.CONNECTING;
    } else {
      this._connectionState = ConnectionState.DISCONNECTED;
    }
    this.onConnectionStateChanged?.();
  }

  /**
   * 清空消息
   */
  clearMessages(): void {
    this._messages = [];
    this._transientMessage = null;
    this._statusMessages = [];
    this._isGenerating = false;
    this._suppressTransient = false;
    this.onMessagesChanged?.();
  }

  /**
   * 生成唯一 ID
   */
  private generateId(): string {
    return `${Date.now()}_${Math.floor(Math.random() * 10000)}`;
  }

  addMessage(message: Message): void {
    const exists = this._messages.some(item => item.id === message.id);
    if (!exists) {
      this._messages = this._messages.concat([message]);
      this.onMessagesChanged?.();
    }
  }

  setConnectionState(state: ConnectionState): void {
    this._connectionState = state;
    this.onConnectionStateChanged?.();
  }

  static getInstance(): ChatStore {
    return chatStore;
  }
}

// 全局单例
export const chatStore = new ChatStore();
export { ConnectionState };
