/**
 * HAP 更新管理器 — 检查更新 & 下载
 */
import http from '@ohos.net.http';
import fs from '@ohos.file.fs';
import common from '@ohos.app.ability.common';
import { API_BASE_URL } from '../common/Constants';

export interface HapVersionInfo {
  versionCode: number;
  versionName: string;
  lastModified: number;
  fileSize: number;
  downloadUrl: string;
}

export class HapUpdater {
  private static readonly VERSION_PATH = '/api/files/hap-version';
  private static readonly DOWNLOAD_PATH = '/api/files/download-hap';
  private static readonly SKIP_VERSION_KEY = 'silk_skip_version';
  private static readonly REMIND_LATER_KEY = 'silk_remind_later_time';

  /**
   * 从服务器获取最新 HAP 版本信息
   */
  static async getVersionInfo(): Promise<HapVersionInfo | null> {
    const httpRequest = http.createHttp();
    try {
      const response = await httpRequest.request(
        `${API_BASE_URL}${HapUpdater.VERSION_PATH}`,
        {
          method: http.RequestMethod.GET,
          header: { 'Content-Type': 'application/json' },
          connectTimeout: 10000,
          readTimeout: 10000
        }
      );
      if (response.responseCode === 200) {
        return JSON.parse(response.result as string) as HapVersionInfo;
      }
      return null;
    } catch (e) {
      return null;
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 下载 HAP 到 cacheDir
   * @param context 应用上下文（用于获取缓存目录）
   * @param fileName 保存的文件名
   * @returns 完整文件路径，失败返回空字符串
   */
  static async downloadHap(
    context: common.Context,
    fileName: string = 'Silk-Harmony.hap'
  ): Promise<string> {
    const filePath = context.cacheDir + '/' + fileName;
    const httpRequest = http.createHttp();
    try {
      const response = await httpRequest.request(
        `${API_BASE_URL}${HapUpdater.DOWNLOAD_PATH}`,
        {
          method: http.RequestMethod.GET,
          expectDataType: http.HttpDataType.ARRAY_BUFFER,
          connectTimeout: 30000,
          readTimeout: 120000
        }
      );

      if (response.responseCode !== 200) {
        return '';
      }

      const file = fs.openSync(filePath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY);
      const buffer = response.result as ArrayBuffer;
      fs.writeSync(file.fd, buffer);
      fs.closeSync(file);

      return filePath;
    } catch (e) {
      return '';
    } finally {
      httpRequest.destroy();
    }
  }

  // ==================== 跳过/稍后提醒逻辑 ====================

  /**
   * 记录跳过的版本
   */
  static markSkippedVersion(versionCode: number): void {
    AppStorage.setOrCreate(HapUpdater.SKIP_VERSION_KEY, versionCode);
  }

  /**
   * 检查某版本是否已被跳过
   */
  static isVersionSkipped(versionCode: number): boolean {
    const skipped = AppStorage.get<number>(HapUpdater.SKIP_VERSION_KEY);
    return skipped === versionCode;
  }

  /**
   * 设置稍后提醒（1小时后重新提示）
   */
  static setRemindLater(): void {
    const later = Date.now() + 60 * 60 * 1000;
    AppStorage.setOrCreate(HapUpdater.REMIND_LATER_KEY, later);
  }

  /**
   * 检查是否还在稍后提醒期间
   */
  static isInRemindLaterPeriod(): boolean {
    const until = AppStorage.get<number>(HapUpdater.REMIND_LATER_KEY);
    if (until === undefined || until === null) return false;
    return Date.now() < until;
  }

  // ==================== 工具方法 ====================

  static formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  static formatVersionDate(timestamp: number): string {
    const date = new Date(timestamp);
    const y = date.getFullYear();
    const m = (date.getMonth() + 1).toString().padStart(2, '0');
    const d = date.getDate().toString().padStart(2, '0');
    const h = date.getHours().toString().padStart(2, '0');
    const min = date.getMinutes().toString().padStart(2, '0');
    return y + '-' + m + '-' + d + ' ' + h + ':' + min;
  }
}
