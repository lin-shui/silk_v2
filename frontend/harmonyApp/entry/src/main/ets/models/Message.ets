/**
 * 消息类型枚举
 */
export enum MessageType {
  TEXT = 'TEXT',
  JOIN = 'JOIN',
  LEAVE = 'LEAVE',
  SYSTEM = 'SYSTEM',
  FILE = 'FILE',
  RECALL = 'RECALL',
  STOP_GENERATE = 'STOP_GENERATE'
}

/**
 * 消息类别枚举（用于UI显示亮度）
 */
export enum MessageCategory {
  NORMAL = 'NORMAL',           // 普通聊天消息（正常亮度）
  TODO_LIST = 'TODO_LIST',     // 待办事项列表（低亮度）
  STEP_PROCESS = 'STEP_PROCESS', // 步骤执行过程（低亮度，可转发）
  FINAL_REPORT = 'FINAL_REPORT', // 最终诊断报告（高亮度）
  AGENT_STATUS = 'AGENT_STATUS',  // Agent 工作状态（灰色，低亮度）
  AGENT_QUESTION = 'AGENT_QUESTION', // Agent 向用户提问（需要用户回答）
  AGENT_PERMISSION = 'AGENT_PERMISSION' // Agent 工具权限确认（需要用户允许/拒绝）
}

/**
 * 引用来源（网络搜索 / 本地资源）
 */
export interface Reference {
  kind: string;       // "citation" = 网络搜索, "available" = 本地资源
  index: number;
  title: string;
  url?: string;
  snippet?: string;
  path?: string;
}

/**
 * 消息数据模型
 */
export interface Message {
  id: string;
  userId: string;
  userName: string;
  content: string;
  timestamp: number;
  type: MessageType;
  isTransient: boolean;      // false = 普通消息，true = 临时消息
  currentStep?: number;      // 当前执行的步骤编号
  totalSteps?: number;       // 总步骤数
  isIncremental: boolean;    // true = 增量消息（需拼接），false = 完整消息
  category: MessageCategory; // 消息类别
  references?: Reference[];  // 引用来源列表（可选）
  footerContent?: string;    // AI 消息的耗时/轮次等元信息（渲染在消息底部）
}

/** 判断某个 userId 是否属于 AI agent（所有 agent 的 userId 均以 _ai_agent 结尾）。 */
export function isAgentUserId(userId: string): boolean {
  return userId.endsWith('_ai_agent');
}

/**
 * 创建默认消息
 */
export function createMessage(
  id: string,
  userId: string,
  userName: string,
  content: string,
  type: MessageType = MessageType.TEXT
): Message {
  return {
    id,
    userId,
    userName,
    content,
    timestamp: Date.now(),
    type,
    isTransient: false,
    isIncremental: false,
    category: MessageCategory.NORMAL,
    references: []
  };
}

/**
 * 解析 JSON 为 Message 对象
 */
export function parseMessage(json: string): Message {
  const obj = JSON.parse(json) as Message;
  const parsedType: MessageType = obj.type ? obj.type as MessageType : MessageType.TEXT;
  const parsedCategory: MessageCategory = obj.category ? obj.category as MessageCategory : MessageCategory.NORMAL;
  const parsedIsTransient: boolean = obj.isTransient ? obj.isTransient : false;
  const parsedIsIncremental: boolean = obj.isIncremental ? obj.isIncremental : false;
  const parsedReferences: Reference[] = obj.references ? obj.references : [];
  return {
    id: obj.id,
    userId: obj.userId,
    userName: obj.userName,
    content: obj.content,
    timestamp: obj.timestamp,
    type: parsedType,
    isTransient: parsedIsTransient,
    currentStep: obj.currentStep,
    totalSteps: obj.totalSteps,
    isIncremental: parsedIsIncremental,
    category: parsedCategory,
    references: parsedReferences
  };
}

/**
 * 将 Message 对象序列化为 JSON
 */
export function stringifyMessage(message: Message): string {
  return JSON.stringify(message);
}
