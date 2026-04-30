/**
 * 元服务运行时模型
 * MetaServiceIntent 是启动引擎接收到的运行时意图，与配置层的 MetaServiceDef 分离
 */
import { MetaServiceType } from '../common/MetaServiceConfig';

export interface MetaServiceIntent {
  serviceId: string;
  serviceName: string;
  serviceType: MetaServiceType;
  keywords?: string[];
  params?: Record<string, string>;
  uri?: string;
  fallbackUrl?: string;
}

export interface LaunchResult {
  success: boolean;
  message: string;
  launchType: 'meta_service' | 'browser' | 'none';
}
