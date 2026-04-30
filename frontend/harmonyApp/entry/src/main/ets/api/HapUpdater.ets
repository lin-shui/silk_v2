/**
 * HAP 更新管理器 — 检查更新 & 下载 & 安装
 */
import http from '@ohos.net.http';
import fs from '@ohos.file.fs';
import request from '@ohos.request';
import common from '@ohos.app.ability.common';
import Want from '@ohos.app.ability.Want';
import wantConstant from '@ohos.app.ability.wantConstant';
import fileUri from '@ohos.file.fileuri';
import { BusinessError } from '@ohos.base';
import { API_BASE_URL } from '../common/Constants';
import { BUILD_TIMESTAMP } from '../common/EnvConfig';

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
   * 本地构建时间戳（Unix 秒），用于版本比较
   */
  static getLocalBuildTimestamp(): number {
    return BUILD_TIMESTAMP;
  }

  /**
   * 判断远程版本是否比本地新
   * 服务端 lastModified 是文件修改时间的 Unix 毫秒时间戳
   * 本地 BUILD_TIMESTAMP 是 hvigor --sync 时的 Unix 秒时间戳
   */
  static isNewerVersion(info: HapVersionInfo): boolean {
    return info.lastModified > BUILD_TIMESTAMP * 1000;
  }

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
    } catch (_) {
      return null;
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 下载 HAP（优先用系统下载服务保存到公开 Download 目录）
   * @param context 应用上下文
   * @param fileName 保存的文件名
   * @returns 完整文件路径，失败返回空字符串
   */
  static async downloadHap(
    context: common.Context,
    fileName: string = 'Silk-Harmony.hap'
  ): Promise<string> {
    // 先用系统下载服务（文件保存到公开 Download 目录，安装器才能读取）
    try {
      const downloadTask = await request.downloadFile(context, {
        url: `${API_BASE_URL}${HapUpdater.DOWNLOAD_PATH}`,
        filePath: fileName,
        description: 'Silk HAP 更新',
        background: true
      });
      return await new Promise<string>((resolve) => {
        downloadTask.on('complete', async () => {
          try {
            const info = await downloadTask.getTaskInfo();
            resolve(info.filePath);
          } catch (_) {
            resolve('');
          }
        });
        downloadTask.on('fail', () => {
          resolve('');
        });
        setTimeout(() => resolve(''), 120000);
      });
    } catch (_) {
      // 系统下载服务不可用时回退到 http + cacheDir
    }

    // Fallback: http 直下 + cacheDir
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
      if (response.responseCode !== 200) return '';
      const file = fs.openSync(filePath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY);
      fs.writeSync(file.fd, response.result as ArrayBuffer);
      fs.closeSync(file);
      return filePath;
    } catch (_) {
      return '';
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 尝试安装下载好的 HAP
   * 先尝试显式调用系统包安装器，失败后用隐式 Want
   * @param context 应用上下文（需为 UIAbilityContext）
   * @param filePath HAP 文件绝对路径
   * @returns true 表示已尝试启动安装（不保证成功），false 表示完全无法启动
   */
  static async installHap(context: common.UIAbilityContext, filePath: string): Promise<boolean> {
    // 用 fileUri 生成正确的 URI，授予临时读取权限
    const uri = fileUri.getUriFromPath(filePath);
    // 先尝试显式启动系统包安装器
    try {
      const want: Want = {
        bundleName: 'com.ohos.packageinstaller',
        abilityName: 'InstallAbility',
        uri: uri,
        type: 'application/hap',
        flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION
      };
      await context.startAbility(want);
      return true;
    } catch (_) {
      // 第一次失败，尝试隐式 Want
    }

    // 隐式 Want：让系统找能处理 HAP 的应用
    try {
      const want: Want = {
        action: 'ohos.want.action.viewData',
        uri: uri,
        type: 'application/hap',
        flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION
      };
      await context.startAbility(want);
      return true;
    } catch (_) {
      return false;
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
