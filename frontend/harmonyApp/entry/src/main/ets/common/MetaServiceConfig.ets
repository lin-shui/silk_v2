/**
 * 元服务注册中心
 * 定义所有受支持鸿蒙元服务的配置：bundleName 候选、URI scheme、参数定义、触发关键词
 * bundleName 需要在真机上通过 hdc shell aa dump -l 实测确认
 */
import { Logger } from './Logger';

export type MetaServiceType = 'search' | 'parameter';

export interface MetaServiceParamDef {
  key: string;
  label: string;
  required: boolean;
  type: 'text' | 'location';
}

export interface MetaServiceDef {
  id: string;
  name: string;
  serviceType: MetaServiceType;
  bundleNameCandidates: string[];
  abilityName: string;
  uriSchemeTemplate?: string;
  searchUrlTemplate?: string;
  paramSchema?: MetaServiceParamDef[];
  triggerKeywords: string[];
  hasBrowserFallback: boolean;
}

const TAG = 'MetaServiceConfig';

const SERVICE_REGISTRY: MetaServiceDef[] = [
  {
    id: 'jd_shopping',
    name: '京东购物',
    serviceType: 'search',
    bundleNameCandidates: ['com.atomicservice.6917594172732814448', 'com.jd.jd', 'com.jingdong.app.mall'],
    abilityName: 'EntryAbility',
    uriSchemeTemplate: 'openapp.jdmobile://search?keyword={{keywords}}',
    searchUrlTemplate: 'https://search.jd.com/Search?keyword={{keywords}}',
    triggerKeywords: ['京东', '京东购物', '京', 'jd', 'JD', '买'],
    hasBrowserFallback: true
  },
  {
    id: 'taobao_shopping',
    name: '淘宝',
    serviceType: 'search',
    bundleNameCandidates: ['com.taobao.taobao'],
    abilityName: 'EntryAbility',
    uriSchemeTemplate: 'taobao://s.taobao.com/search?q={{keywords}}',
    searchUrlTemplate: 'https://s.taobao.com/search?q={{keywords}}',
    triggerKeywords: ['淘宝', '淘', 'tb'],
    hasBrowserFallback: true
  },
  {
    id: 'meituan_shopping',
    name: '美团',
    serviceType: 'search',
    bundleNameCandidates: ['com.sankuai.meituan'],
    abilityName: 'EntryAbility',
    uriSchemeTemplate: 'imeituan://www.meituan.com/search?keyword={{keywords}}',
    searchUrlTemplate: 'https://i.meituan.com/awp/h5/search/s.html?keyword={{keywords}}',
    triggerKeywords: ['美团', '外卖', '团'],
    hasBrowserFallback: true
  },
  {
    id: 'pdd_shopping',
    name: '拼多多',
    serviceType: 'search',
    bundleNameCandidates: ['com.xunmeng.pinduoduo'],
    abilityName: 'EntryAbility',
    uriSchemeTemplate: 'pinduoduo://search_result.html?search_key_input={{keywords}}',
    searchUrlTemplate: 'https://mobile.yangkeduo.com/search_result.html?search_key_input={{keywords}}',
    triggerKeywords: ['拼多多', '拼多', 'pdd', 'PDD'],
    hasBrowserFallback: true
  },
  {
    id: 't3_ride',
    name: 'T3出行',
    serviceType: 'parameter',
    bundleNameCandidates: ['com.atomicservice.6917588158831852577', 'com.t3go.t3go', 'com.t3go.hmos'],
    abilityName: 'EntryAbility',
    uriSchemeTemplate: 't3go://ride?pickup={{pickup}}&destination={{destination}}',
    paramSchema: [
      { key: 'pickup', label: '出发地', required: true, type: 'text' },
      { key: 'destination', label: '目的地', required: true, type: 'text' }
    ],
    triggerKeywords: ['打车', '叫车', 'T3', '出行', '专车', '快车', '去机场', '去车站'],
    hasBrowserFallback: false
  }
];

export class MetaServiceConfig {
  static getServiceDef(id: string): MetaServiceDef | undefined {
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      if (SERVICE_REGISTRY[i].id === id) {
        return SERVICE_REGISTRY[i];
      }
    }
    return undefined;
  }

  static getAllServices(): MetaServiceDef[] {
    const result: MetaServiceDef[] = [];
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      result.push(SERVICE_REGISTRY[i]);
    }
    return result;
  }

  static getSearchServices(): MetaServiceDef[] {
    const result: MetaServiceDef[] = [];
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      if (SERVICE_REGISTRY[i].serviceType === 'search') {
        result.push(SERVICE_REGISTRY[i]);
      }
    }
    return result;
  }

  static getParameterServices(): MetaServiceDef[] {
    const result: MetaServiceDef[] = [];
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      if (SERVICE_REGISTRY[i].serviceType === 'parameter') {
        result.push(SERVICE_REGISTRY[i]);
      }
    }
    return result;
  }

  static findMatchingServices(text: string): MetaServiceDef[] {
    const lower = text.toLowerCase();
    const matched: MetaServiceDef[] = [];
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      const def = SERVICE_REGISTRY[i];
      for (let j = 0; j < def.triggerKeywords.length; j++) {
        if (lower.includes(def.triggerKeywords[j].toLowerCase())) {
          matched.push(def);
          break;
        }
      }
    }
    return matched;
  }

  static resolveUri(def: MetaServiceDef, params: Record<string, string>): string | undefined {
    if (!def.uriSchemeTemplate) {
      return undefined;
    }
    let uri = def.uriSchemeTemplate;
    const keys = Object.keys(params);
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const value = encodeURIComponent(params[key] || '');
      uri = uri.replace(`{{${key}}}`, value);
    }
    return uri;
  }

  static resolveSearchUrl(def: MetaServiceDef, keywords: string): string | undefined {
    if (!def.searchUrlTemplate) {
      return undefined;
    }
    return def.searchUrlTemplate.replace('{{keywords}}', encodeURIComponent(keywords));
  }

  static logRegistry(): void {
    const names: string[] = [];
    for (let i = 0; i < SERVICE_REGISTRY.length; i++) {
      names.push(SERVICE_REGISTRY[i].name);
    }
    Logger.info(TAG, `注册元服务: ${names.join(', ')}`);
  }
}
