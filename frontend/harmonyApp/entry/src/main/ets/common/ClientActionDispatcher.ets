/**
 * ClientActionDispatcher - 客户端动作分发器
 * 处理后端通过 clientActions 字段下发的设备侧操作指令
 */
import { AlarmService } from './AlarmService';
import { Logger } from './Logger';
import promptAction from '@ohos.promptAction';

const TAG = 'ClientActionDispatcher';

export class ClientActionDispatcher {
  /** 失败时的回调：往聊天中插入一条错误消息 */
  static onActionFailed: ((errorMsg: string) => void) | null = null;

  /**
   * 分发并执行一条 clientAction
   * @param actionJson - JSON 格式的动作指令字符串
   */
  static async dispatch(actionJson: string): Promise<void> {
    try {
      const action = JSON.parse(actionJson) as Record<string, Object>;
      const type = action.type as string;

      switch (type) {
        case 'SET_ALARM':
          await ClientActionDispatcher.handleSetAlarm(action);
          break;
        default:
          Logger.info(TAG, `未知的 clientAction 类型: ${type}`);
      }
    } catch (e) {
      Logger.error(TAG, `clientAction 解析失败: ${e}`);
    }
  }

  private static async handleSetAlarm(action: Record<string, Object>): Promise<void> {
    const hour = action.hour as number;
    const minute = action.minute as number;
    const labelVal = action.label;
    const label: string | undefined = labelVal ? labelVal as string : undefined;
    const result = await AlarmService.setAlarm(hour, minute, label);

    if (result.success) {
      try {
        promptAction.showToast({ message: result.message, duration: 3000 });
      } catch (e) {
        Logger.error(TAG, `Toast 失败: ${e}`);
      }
    } else {
      try {
        promptAction.showToast({ message: '闹钟设定失败', duration: 3000 });
      } catch (e) {
        Logger.error(TAG, `Toast 失败: ${e}`);
      }
      ClientActionDispatcher.onActionFailed?.(`闹钟设定失败: ${result.message}`);
    }
  }
}
