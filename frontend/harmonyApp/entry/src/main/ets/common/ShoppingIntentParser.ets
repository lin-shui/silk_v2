/**
 * 购物意图解析服务
 * 从待办文本中提取购物需求，生成购物链接
 */

export interface ShoppingIntent {
  /** 是否识别为购物意图 */
  isShopping: boolean;
  /** 商品关键词 */
  keywords: string[];
  /** 推荐平台 */
  platform: ShoppingPlatform;
  /** 生成的搜索链接 */
  searchUrl: string;
  /** 平台显示名称 */
  platformName: string;
}

export type ShoppingPlatform = 'taobao' | 'jd' | 'meituan' | 'pdd' | 'generic';

/** 平台 URL 信息接口 */
export interface PlatformUrlInfo {
  domain: string;
  searchPath: string;
  name: string;
}

/** 平台信息接口 */
export interface PlatformInfo {
  platform: ShoppingPlatform;
  name: string;
}

export class ShoppingIntentParser {
  /** 购物关键词模式 */
  private static readonly SHOPPING_PATTERNS: RegExp[] = [
    /购物|买东西|购买|下单|采购/,
    /买点|买一些|买几个/,
    /缺|少了|没了|用完了/,
    /需要.*买|要.*买|得.*买/
  ];

  /** 平台关键词 */
  private static getPlatformKeywords(): Map<ShoppingPlatform, string[]> {
    const map = new Map<ShoppingPlatform, string[]>();
    map.set('taobao', ['淘宝', '淘', 'tb']);
    map.set('jd', ['京东', '京', 'jd', 'JD']);
    map.set('meituan', ['美团', '外卖', '团']);
    map.set('pdd', ['拼多多', '拼多', 'pdd', 'PDD']);
    map.set('generic', []);
    return map;
  }

  /** 平台域名和搜索路径 */
  private static getPlatformUrls(): Map<ShoppingPlatform, PlatformUrlInfo> {
    const map = new Map<ShoppingPlatform, PlatformUrlInfo>();
    map.set('taobao', {
      domain: 'https://s.taobao.com',
      searchPath: '/search?q=',
      name: '淘宝'
    });
    map.set('jd', {
      domain: 'https://search.jd.com',
      searchPath: '/Search?keyword=',
      name: '京东'
    });
    map.set('meituan', {
      domain: 'https://i.meituan.com',
      searchPath: '/awp/h5/search/s.html?keyword=',
      name: '美团'
    });
    map.set('pdd', {
      domain: 'https://mobile.yangkeduo.com',
      searchPath: '/search_result.html?search_key_input=',
      name: '拼多多'
    });
    map.set('generic', {
      domain: 'https://www.baidu.com',
      searchPath: '/s?wd=',
      name: '百度搜索'
    });
    return map;
  }

  /**
   * 解析待办文本中的购物意图
   * @param title 待办标题
   * @param detail 待办详情（可选）
   * @returns 购物意图信息
   */
  static parseShoppingIntent(title: string, detail?: string): ShoppingIntent {
    const fullText = `${title} ${detail ? detail : ''}`;

    // 检测是否为购物意图
    let isShopping = false;
    for (let i = 0; i < ShoppingIntentParser.SHOPPING_PATTERNS.length; i++) {
      if (ShoppingIntentParser.SHOPPING_PATTERNS[i].test(fullText)) {
        isShopping = true;
        break;
      }
    }

    if (!isShopping) {
      const result: ShoppingIntent = {
        isShopping: false,
        keywords: [],
        platform: 'generic',
        searchUrl: '',
        platformName: ''
      };
      return result;
    }

    // 提取商品关键词
    const keywords = ShoppingIntentParser.extractKeywords(fullText);

    // 识别平台
    const platform = ShoppingIntentParser.detectPlatform(fullText);

    // 生成搜索链接
    const searchUrl = ShoppingIntentParser.generateSearchUrl(keywords, platform);

    const platformUrls = ShoppingIntentParser.getPlatformUrls();
    const platformInfo = platformUrls.get(platform);

    const result: ShoppingIntent = {
      isShopping: true,
      keywords: keywords,
      platform: platform,
      searchUrl: searchUrl,
      platformName: platformInfo ? platformInfo.name : '未知平台'
    };
    return result;
  }

  /**
   * 从文本中提取商品关键词
   */
  private static extractKeywords(text: string): string[] {
    // 移除购物行为词
    let cleaned = text
      .replace(/购物|买东西|购买|下单|采购|买点|买一些|买几个/g, '')
      .replace(/需要|要|得|缺|少了|没了|用完了/g, '')
      .replace(/淘宝|淘|tb|京东|京|jd|JD|美团|外卖|团|拼多多|拼多|pdd|PDD/gi, '')
      .replace(/\s+/g, ' ')
      .trim();

    // 提取关键名词（简化版：取非停用词）
    const stopWords = ['的', '了', '和', '与', '或', '等', '这', '那', '些', '个', '点', '些'];
    const words = cleaned.split(/\s+|，|,|、|。|！|！|\?|？/);

    const keywords: string[] = [];
    for (let i = 0; i < words.length; i++) {
      const word = words[i].trim();
      if (word.length > 0 && !stopWords.includes(word)) {
        keywords.push(word);
      }
    }

    return keywords.length > 0 ? keywords : ['商品'];
  }

  /**
   * 检测指定的购物平台
   */
  private static detectPlatform(text: string): ShoppingPlatform {
    const platforms: ShoppingPlatform[] = ['taobao', 'jd', 'meituan', 'pdd'];
    const platformKeywords = ShoppingIntentParser.getPlatformKeywords();

    for (let i = 0; i < platforms.length; i++) {
      const platform = platforms[i];
      const keywords = platformKeywords.get(platform);
      if (keywords) {
        for (let j = 0; j < keywords.length; j++) {
          if (text.includes(keywords[j])) {
            return platform;
          }
        }
      }
    }

    // 默认使用淘宝
    return 'taobao';
  }

  /**
   * 生成平台搜索链接
   */
  private static generateSearchUrl(keywords: string[], platform: ShoppingPlatform): string {
    const platformUrls = ShoppingIntentParser.getPlatformUrls();
    const platformInfo = platformUrls.get(platform);
    if (!platformInfo) {
      return '';
    }
    const query = encodeURIComponent(keywords.join(' '));
    return `${platformInfo.domain}${platformInfo.searchPath}${query}`;
  }

  /**
   * 获取所有支持的购物平台
   */
  static getSupportedPlatforms(): Array<PlatformInfo> {
    const platforms: PlatformInfo[] = [
      { platform: 'taobao', name: '淘宝' },
      { platform: 'jd', name: '京东' },
      { platform: 'meituan', name: '美团' },
      { platform: 'pdd', name: '拼多多' }
    ];
    return platforms;
  }
}
