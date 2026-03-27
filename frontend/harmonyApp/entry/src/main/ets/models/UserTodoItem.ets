/**
 * 跨群同步的待办项（后端从各群聊天记录提取）
 */
export interface UserTodoItem {
  id: string;
  title: string;
  sourceGroupId?: string;
  sourceGroupName?: string;
  /** none | alarm | calendar */
  actionType?: string;
  /** 如 07:00，或带日期的可解析片段 */
  actionDetail?: string;
  createdAt: number;
  updatedAt: number;
  done: boolean;
}
