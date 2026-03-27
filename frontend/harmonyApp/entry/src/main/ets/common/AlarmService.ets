/**
 * AlarmService - 系统闹钟/提醒设定服务
 * 方案1: reminderAgentManager（模拟器配额为0）
 * 方案2: calendarManager 写日历事件 + 提醒
 */
import reminderAgentManager from '@ohos.reminderAgentManager';
import calendarManager from '@ohos.calendarManager';
import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import { Permissions } from '@ohos.abilityAccessCtrl';
import { getGlobalContext } from '../entryability/EntryAbility';
import { Logger } from './Logger';

const TAG = 'AlarmService';

export interface AlarmResult {
  success: boolean;
  reminderId: number;
  message: string;
}

export class AlarmService {
  /**
   * 设定闹钟 — 自动选择可用方案（兼容旧调用，不清理已有系统闹钟）
   */
  static async setAlarm(hour: number, minute: number): Promise<AlarmResult> {
    return AlarmService.setAlarmWithTitle(hour, minute, 'Silk 闹钟');
  }

  /**
   * 待办「执行」：使用用户可见标题发布闹钟/日历提醒
   */
  static async setAlarmForTodo(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const t = title.trim().length > 0 ? title.trim() : 'Silk 待办提醒';
    return AlarmService.setAlarmWithTitle(hour, minute, t);
  }

  /** 仅写入日历提醒（待办 actionType=calendar） */
  static async setCalendarOnlyForTodo(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const t = title.trim().length > 0 ? title.trim() : 'Silk 日程';
    return AlarmService.tryCalendarEvent(hour, minute, t);
  }

  private static async setAlarmWithTitle(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;
    Logger.info(TAG, `===== 设定闹钟 ${hour}:${minuteStr} ${title} =====`);

    Logger.info(TAG, '尝试方案1: reminderAgentManager...');
    const result1 = await AlarmService.tryReminderAgent(hour, minute, title);
    if (result1.success) {
      return result1;
    }
    Logger.info(TAG, `方案1 失败: ${result1.message}`);

    Logger.info(TAG, '尝试方案2: calendarManager...');
    const result2 = await AlarmService.tryCalendarEvent(hour, minute, title);
    if (result2.success) {
      return result2;
    }
    Logger.info(TAG, `方案2 失败: ${result2.message}`);

    return {
      success: false,
      reminderId: -1,
      message: `方案1: ${result1.message} | 方案2: ${result2.message}`
    };
  }

  /**
   * 方案1: reminderAgentManager（代理提醒 / 原子化闹钟能力）
   */
  private static async tryReminderAgent(hour: number, minute: number, title: string): Promise<AlarmResult> {
    try {
      const grant = await AlarmService.requestPermissions(
        ['ohos.permission.PUBLISH_AGENT_REMINDER' as Permissions]
      );
      if (!grant) {
        return { success: false, reminderId: -1, message: '代理提醒权限被拒绝' };
      }
      const alarmTitle = title.length > 60 ? title.substring(0, 57) + '…' : title;
      const alarm: reminderAgentManager.ReminderRequestAlarm = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM,
        hour: hour,
        minute: minute,
        daysOfWeek: [],
        title: alarmTitle
      };
      const id = await reminderAgentManager.publishReminder(alarm);
      const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;
      Logger.info(TAG, `reminderAgent 成功, id=${id}`);
      return { success: true, reminderId: id, message: `已请求系统闹钟 ${hour}:${minuteStr}` };
    } catch (e) {
      const err = e as Record<string, Object>;
      return { success: false, reminderId: -1, message: `${err.code} ${err.message}` };
    }
  }

  /**
   * 方案2: calendarManager 创建日历事件 + 提醒
   */
  private static async tryCalendarEvent(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const context = getGlobalContext();
    if (!context) {
      return { success: false, reminderId: -1, message: 'context 为空' };
    }

    const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;

    // 申请日历权限
    Logger.info(TAG, '申请日历读写权限...');
    const granted = await AlarmService.requestPermissions(
      ['ohos.permission.READ_CALENDAR' as Permissions, 'ohos.permission.WRITE_CALENDAR' as Permissions]
    );
    if (!granted) {
      return { success: false, reminderId: -1, message: '日历权限被拒绝' };
    }
    Logger.info(TAG, '日历权限已获取');

    try {
      // 获取 CalendarManager
      const mgr = calendarManager.getCalendarManager(context);
      Logger.info(TAG, 'CalendarManager 获取成功');

      // 获取默认日历
      let calendar: calendarManager.Calendar;
      try {
        calendar = await mgr.getCalendar();
        Logger.info(TAG, `获取默认日历成功, account=${JSON.stringify(calendar.getAccount())}`);
      } catch (e) {
        Logger.info(TAG, '默认日历不存在，创建新日历...');
        const account: calendarManager.CalendarAccount = {
          name: 'Silk',
          type: calendarManager.CalendarType.LOCAL,
          displayName: 'Silk 提醒'
        };
        calendar = await mgr.createCalendar(account);
        Logger.info(TAG, '新日历创建成功');
      }

      // 构造事件时间
      const now = new Date();
      const startTime = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, minute, 0).getTime();
      const endTime = startTime + 30 * 60 * 1000; // 持续30分钟

      // 创建事件，reminderTime: [0] 表示事件开始时提醒
      const eventTitle = title.length > 80 ? title.substring(0, 77) + '…' : title;
      const event: calendarManager.Event = {
        type: calendarManager.EventType.IMPORTANT,
        title: eventTitle,
        startTime: startTime,
        endTime: endTime,
        reminderTime: [0],
        description: `Silk 待办 · ${hour}:${minuteStr}`
      };

      Logger.info(TAG, `创建日历事件: ${JSON.stringify(event)}`);
      const eventId = await calendar.addEvent(event);
      Logger.info(TAG, `日历事件创建成功, eventId=${eventId}`);

      return {
        success: true,
        reminderId: eventId,
        message: `闹钟已设定: ${hour}:${minuteStr} (日历提醒)`
      };
    } catch (e) {
      const err = e as Record<string, Object>;
      const errMsg = `${err.code} ${err.message}`;
      Logger.error(TAG, `日历事件创建失败: ${errMsg}`);
      return { success: false, reminderId: -1, message: errMsg };
    }
  }

  /**
   * 申请运行时权限
   */
  private static async requestPermissions(permissions: Permissions[]): Promise<boolean> {
    const context = getGlobalContext();
    if (!context) {
      return false;
    }
    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const result = await atManager.requestPermissionsFromUser(context, permissions);
      Logger.info(TAG, `权限申请结果: ${JSON.stringify(result.authResults)}`);
      // authResults: 0 = granted, -1 = denied
      for (let i = 0; i < result.authResults.length; i++) {
        if (result.authResults[i] !== 0) {
          Logger.error(TAG, `权限 ${permissions[i]} 被拒绝`);
          return false;
        }
      }
      return true;
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `权限申请异常: ${err.code} ${err.message}`);
      return false;
    }
  }
}
