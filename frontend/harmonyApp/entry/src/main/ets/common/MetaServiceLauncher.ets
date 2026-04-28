/**
 * 元服务统一启动引擎
 * 三层降级：原生元服务 → URI scheme 深度链接 → 浏览器回退（仅搜索型）
 */
import common from '@ohos.app.ability.common';
import Want from '@ohos.app.ability.Want';
import { getGlobalContext } from '../entryability/EntryAbility';
import { Logger } from './Logger';
import { MetaServiceConfig } from './MetaServiceConfig';
import { MetaServiceIntent, LaunchResult } from '../models/MetaServiceDef';

const TAG = 'MetaServiceLauncher';

function getUiContext(): common.UIAbilityContext {
  const ctx = getGlobalContext();
  if (!ctx) {
    throw new Error('UIAbilityContext 未初始化（EntryAbility.onCreate 可能未执行）');
  }
  return ctx;
}

export class MetaServiceLauncher {
  static async launch(intent: MetaServiceIntent): Promise<LaunchResult> {
    Logger.info(TAG, `启动元服务: ${intent.serviceId}`);

    const appResult = await MetaServiceLauncher.tryLaunchApp(intent);
    if (appResult.success) {
      return appResult;
    }

    if (intent.uri) {
      const uriResult = await MetaServiceLauncher.tryLaunchUri(intent.uri);
      if (uriResult.success) {
        return {
          success: true,
          message: `正在通过链接打开 ${intent.serviceName}`,
          launchType: 'meta_service'
        };
      }
    }

    if (intent.fallbackUrl) {
      return await MetaServiceLauncher.launchBrowser(intent.fallbackUrl, intent.serviceName);
    }

    return {
      success: false,
      message: `无法启动 ${intent.serviceName}，请确认已安装元服务`,
      launchType: 'none'
    };
  }

  private static async tryLaunchApp(intent: MetaServiceIntent): Promise<LaunchResult> {
    const def = MetaServiceConfig.getServiceDef(intent.serviceId);
    if (!def) {
      return { success: false, message: `未找到服务定义: ${intent.serviceId}`, launchType: 'none' };
    }

    const ctx = getUiContext();
    const candidates = def.bundleNameCandidates;
    for (let i = 0; i < candidates.length; i++) {
      try {
        const params: Record<string, Object> = {};
        if (intent.params) {
          const keys = Object.keys(intent.params);
          for (let j = 0; j < keys.length; j++) {
            params[keys[j]] = intent.params[keys[j]];
          }
        }
        const want: Want = {
          bundleName: candidates[i],
          abilityName: def.abilityName,
          parameters: params
        };
        await ctx.startAbility(want);
        Logger.info(TAG, `成功启动: ${candidates[i]}`);
        return {
          success: true,
          message: `正在打开 ${def.name}`,
          launchType: 'meta_service'
        };
      } catch (error) {
        const errMsg = error instanceof Error ? error.message : '未知错误';
        Logger.error(TAG, `bundleName=${candidates[i]} 启动失败: ${errMsg}`);
      }
    }
    return { success: false, message: '所有 bundleName 均失败', launchType: 'none' };
  }

  private static async tryLaunchUri(uri: string): Promise<LaunchResult> {
    const ctx = getUiContext();
    try {
      const want: Want = {
        action: 'ohos.want.action.viewData',
        uri: uri
      };
      await ctx.startAbility(want);
      Logger.info(TAG, `URI 启动成功: ${uri}`);
      return { success: true, message: '正在打开', launchType: 'meta_service' };
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      Logger.error(TAG, `URI 启动失败: ${errMsg}`);
      return { success: false, message: errMsg, launchType: 'none' };
    }
  }

  private static async launchBrowser(url: string, serviceName: string): Promise<LaunchResult> {
    const ctx = getUiContext();
    try {
      const want: Want = {
        action: 'ohos.want.action.viewData',
        uri: url
      };
      await ctx.startAbility(want);
      Logger.info(TAG, `浏览器打开: ${url}`);
      return {
        success: true,
        message: `正在用浏览器打开 ${serviceName}`,
        launchType: 'browser'
      };
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      Logger.error(TAG, `浏览器启动失败: ${errMsg}`);
      return { success: false, message: `跳转失败: ${errMsg}`, launchType: 'none' };
    }
  }
}
