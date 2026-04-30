/**
 * 购物跳转服务
 * 保持原接口不变，内部委派到 MetaServiceLauncher
 */
import { ShoppingPlatform, ShoppingIntent } from './ShoppingIntentParser';
import { MetaServiceIntent } from '../models/MetaServiceDef';
import { MetaServiceLauncher } from './MetaServiceLauncher';
import { MetaServiceConfig } from './MetaServiceConfig';

export interface ShoppingLaunchResult {
  success: boolean;
  message: string;
  launchType?: string;
}

export class ShoppingLauncher {
  static async launchShopping(intent: ShoppingIntent): Promise<ShoppingLaunchResult> {
    if (!intent.isShopping) {
      return { success: false, message: '非购物意图，无法跳转' };
    }

    const metaIntent = ShoppingLauncher.toMetaServiceIntent(intent);
    const result = await MetaServiceLauncher.launch(metaIntent);

    return {
      success: result.success,
      message: result.message,
      launchType: result.launchType
    };
  }

  private static toMetaServiceIntent(intent: ShoppingIntent): MetaServiceIntent {
    const platformToServiceId: Record<string, string> = {
      'taobao': 'taobao_shopping',
      'jd': 'jd_shopping',
      'meituan': 'meituan_shopping',
      'pdd': 'pdd_shopping'
    };

    const serviceId = platformToServiceId[intent.platform] || 'jd_shopping';
    const def = MetaServiceConfig.getServiceDef(serviceId);
    const keywords = intent.keywords.join(' ');
    const serviceName = def ? def.name : intent.platformName;

    let uri: string | undefined;
    let fallbackUrl: string | undefined;

    if (def) {
      const uriParams: Record<string, string> = {};
      uriParams['keywords'] = keywords;
      uri = MetaServiceConfig.resolveUri(def, uriParams);
      fallbackUrl = MetaServiceConfig.resolveSearchUrl(def, keywords);
    } else {
      fallbackUrl = intent.searchUrl;
    }

    return {
      serviceId: serviceId,
      serviceName: serviceName,
      serviceType: 'search',
      keywords: intent.keywords,
      uri: uri,
      fallbackUrl: fallbackUrl
    };
  }

  static async isAppInstalled(_platform: ShoppingPlatform): Promise<boolean> {
    return true;
  }

  static getPlatformBundleName(platform: ShoppingPlatform): string | null {
    const map: Record<string, string | null> = {
      'taobao': 'com.taobao.taobao',
      'jd': 'com.jingdong.app.mall',
      'meituan': 'com.sankuai.meituan',
      'pdd': 'com.xunmeng.pinduoduo',
      'generic': null
    };
    const result = map[platform];
    return result !== undefined ? result : null;
  }

  static getPlatformUriScheme(platform: ShoppingPlatform, query: string): string {
    const encodedQuery = encodeURIComponent(query);
    const schemeMap: Record<string, string> = {
      'taobao': `taobao://s.taobao.com/search?q=${encodedQuery}`,
      'jd': `openapp.jdmobile://search?keyword=${encodedQuery}`,
      'meituan': `imeituan://www.meituan.com/search?keyword=${encodedQuery}`,
      'pdd': `pinduoduo://search_result.html?search_key_input=${encodedQuery}`,
      'generic': `https://www.baidu.com/s?wd=${encodedQuery}`
    };
    return schemeMap[platform] || '';
  }
}
