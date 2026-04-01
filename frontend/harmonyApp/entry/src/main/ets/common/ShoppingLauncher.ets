/**
 * 购物跳转服务
 * 负责打开电商平台 App 或网页
 */
import { ShoppingPlatform, ShoppingIntent } from './ShoppingIntentParser';
import { Logger } from './Logger';

export interface ShoppingLaunchResult {
  success: boolean;
  message: string;
  /** 跳转方式：app | browser */
  launchType?: string;
}

export class ShoppingLauncher {
  /**
   * 执行购物跳转
   * @param intent 购物意图信息
   * @returns 跳转结果
   */
  static async launchShopping(intent: ShoppingIntent): Promise<ShoppingLaunchResult> {
    if (!intent.isShopping) {
      const result: ShoppingLaunchResult = {
        success: false,
        message: '非购物意图，无法跳转'
      };
      return result;
    }

    // 优先尝试打开 App
    const appResult = await ShoppingLauncher.tryLaunchApp(intent);
    if (appResult.success) {
      return appResult;
    }

    // 降级到浏览器
    const browserResult = await ShoppingLauncher.launchBrowser(intent);
    return browserResult;
  }

  /**
   * 尝试打开电商平台 App
   */
  private static async tryLaunchApp(intent: ShoppingIntent): Promise<ShoppingLaunchResult> {
    const bundleName = ShoppingLauncher.getPlatformBundleName(intent.platform);

    if (!bundleName) {
      const result: ShoppingLaunchResult = {
        success: false,
        message: '不支持的平台'
      };
      return result;
    }

    try {
      // 使用鸿蒙的 startAbility 跨应用跳转
      // 注意：实际实现需要根据鸿蒙应用包名调整
      const query = intent.keywords.join(' ');

      // 这里使用 URI Scheme 跳转
      const uriScheme = ShoppingLauncher.getPlatformUriScheme(intent.platform, query);

      Logger.info('ShoppingLauncher', `尝试打开 App: ${bundleName}, URI: ${uriScheme}`);

      // 鸿蒙系统的跨应用跳转
      // 实际代码需要导入 @ohos.app.ability.common
      // 这里提供伪代码结构，实际部署时需要调整
      /*
      import common from '@ohos.app.ability.common';
      const context = getContext(this) as common.UIAbilityContext;

      await context.startAbility({
        bundleName: bundleName,
        abilityName: 'MainAbility',
        uri: uriScheme
      });
      */

      const result: ShoppingLaunchResult = {
        success: true,
        message: `正在打开 ${intent.platformName} App`,
        launchType: 'app'
      };
      return result;
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      Logger.error('ShoppingLauncher', `App 跳转失败: ${errMsg}`);
      const result: ShoppingLaunchResult = {
        success: false,
        message: `App 未安装或跳转失败: ${errMsg}`
      };
      return result;
    }
  }

  /**
   * 使用浏览器打开购物链接
   */
  private static async launchBrowser(intent: ShoppingIntent): Promise<ShoppingLaunchResult> {
    try {
      // 使用鸿蒙的 Web 组件或系统浏览器
      Logger.info('ShoppingLauncher', `打开浏览器: ${intent.searchUrl}`);

      // 实际代码示例
      /*
      import web_webview from '@ohos.web.webview';
      // 或使用系统浏览器
      import common from '@ohos.app.ability.common';
      const context = getContext(this) as common.UIAbilityContext;

      await context.startAbility({
        action: 'ohos.want.action.viewData',
        uri: intent.searchUrl
      });
      */

      const result: ShoppingLaunchResult = {
        success: true,
        message: `正在用浏览器打开 ${intent.platformName}`,
        launchType: 'browser'
      };
      return result;
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      Logger.error('ShoppingLauncher', `浏览器跳转失败: ${errMsg}`);
      const result: ShoppingLaunchResult = {
        success: false,
        message: `跳转失败: ${errMsg}`
      };
      return result;
    }
  }

  /**
   * 获取平台的鸿蒙应用包名
   * 注意：这些是示例包名，实际需要查询鸿蒙应用市场
   */
  private static getPlatformBundleName(platform: ShoppingPlatform): string | null {
    const bundleMap = new Map<ShoppingPlatform, string | null>();
    bundleMap.set('taobao', 'com.taobao.taobao'); // 需要确认鸿蒙版淘宝的实际包名
    bundleMap.set('jd', 'com.jingdong.app.mall'); // 需要确认鸿蒙版京东的实际包名
    bundleMap.set('meituan', 'com.sankuai.meituan'); // 需要确认鸿蒙版美团的实际包名
    bundleMap.set('pdd', 'com.xunmeng.pinduoduo'); // 需要确认鸿蒙版拼多多的实际包名
    bundleMap.set('generic', null);

    const result = bundleMap.get(platform);
    return result !== undefined ? result : null;
  }

  /**
   * 获取平台的 URI Scheme（用于深度链接）
   */
  private static getPlatformUriScheme(platform: ShoppingPlatform, query: string): string {
    const encodedQuery = encodeURIComponent(query);

    const schemeMap = new Map<ShoppingPlatform, string>();
    schemeMap.set('taobao', `taobao://s.taobao.com/search?q=${encodedQuery}`);
    schemeMap.set('jd', `openapp.jdmobile://search?keyword=${encodedQuery}`);
    schemeMap.set('meituan', `imeituan://www.meituan.com/search?keyword=${encodedQuery}`);
    schemeMap.set('pdd', `pinduoduo://search_result.html?search_key_input=${encodedQuery}`);
    schemeMap.set('generic', `https://www.baidu.com/s?wd=${encodedQuery}`);

    const result = schemeMap.get(platform);
    return result !== undefined ? result : '';
  }

  /**
   * 检测平台 App 是否已安装
   * 注意：鸿蒙系统可能需要特殊权限或 API
   */
  static async isAppInstalled(platform: ShoppingPlatform): Promise<boolean> {
    // 鸿蒙系统中查询应用是否安装
    // 实际实现需要使用 bundleManager
    /*
    import bundleManager from '@ohos.bundle.bundleManager';
    const bundleName = this.getPlatformBundleName(platform);
    try {
      await bundleManager.getBundleInfoForSelf(bundleName);
      return true;
    } catch {
      return false;
    }
    */

    // 默认返回 true，让实际跳转时处理
    return true;
  }
}
