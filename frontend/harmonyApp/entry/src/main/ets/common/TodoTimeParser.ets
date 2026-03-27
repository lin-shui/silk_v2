/**
 * 从待办标题 / actionDetail 中解析闹钟时间（配合后端 actionDetail "HH:mm"）
 */
export interface ParsedAlarmTime {
  hour: number;
  minute: number;
}

const CN_DIGIT: Record<string, number> = {
  '零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4,
  '五': 5, '六': 6, '七': 7, '八': 8, '九': 9
};

/** 先匹配较长词，避免「十二点」被「二点」误伤 */
const CN_HOUR_KEYS: string[] = [
  '十二', '十一', '十', '九', '八', '七', '六', '五', '四', '三', '二', '一', '两'
];

function cnHourWordToNum(word: string): number | null {
  if (word === '十') {
    return 10;
  }
  if (word === '十一') {
    return 11;
  }
  if (word === '十二') {
    return 12;
  }
  const v = CN_DIGIT[word];
  return v !== undefined ? v : null;
}

export function parseAlarmTimeFromText(combined: string): ParsedAlarmTime | null {
  const text = combined.replace(/\s+/g, ' ').trim();
  if (text.length === 0) {
    return null;
  }

  let m = text.match(/(\d{1,2})\s*[:：]\s*(\d{2})/);
  if (m) {
    const h = parseInt(m[1], 10);
    const min = parseInt(m[2], 10);
    if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {
      return { hour: h, minute: min };
    }
  }

  m = text.match(/(\d{1,2})\s*点(?:\s*(\d{1,2})\s*分)?/);
  if (m) {
    let h = parseInt(m[1], 10);
    const min = m[2] ? parseInt(m[2], 10) : (text.indexOf('半') >= 0 ? 30 : 0);
    const pm = text.indexOf('下午') >= 0 || text.indexOf('晚上') >= 0 || text.indexOf('傍晚') >= 0;
    if (pm && h >= 1 && h <= 11) {
      h += 12;
    }
    if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {
      return { hour: h, minute: min };
    }
  }

  for (let i = 0; i < CN_HOUR_KEYS.length; i++) {
    const k = CN_HOUR_KEYS[i];
    const idx = text.indexOf(k + '点');
    if (idx >= 0) {
      const base = cnHourWordToNum(k);
      if (base === null) {
        continue;
      }
      let h = base;
      const pm = text.indexOf('下午') >= 0 || text.indexOf('晚上') >= 0 || text.indexOf('傍晚') >= 0;
      if (pm && h >= 1 && h <= 11) {
        h += 12;
      }
      const min = text.indexOf('半', idx) >= 0 ? 30 : 0;
      if (h >= 0 && h <= 23) {
        return { hour: h, minute: min };
      }
    }
  }

  return null;
}
