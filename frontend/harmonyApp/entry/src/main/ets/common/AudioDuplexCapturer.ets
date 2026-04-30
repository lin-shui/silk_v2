/**
 * AudioDuplexCapturer - 实时麦克风 PCM 采集
 * 使用 AudioCapturer 采集 16kHz mono float32 PCM，每 1s 回调一个音频块
 */
import { audio } from '@kit.AudioKit';
import { buffer } from '@kit.ArkTS';
import { Logger } from './Logger';

const TAG = 'AudioDuplexCapturer';

export type AudioChunkCallback = (base64Pcm: string) => void;

export class AudioDuplexCapturer {
  private capturer: audio.AudioCapturer | null = null;
  private isCapturing: boolean = false;
  private onChunk: AudioChunkCallback | null = null;
  private readTask: number = -1;

  get capturing(): boolean {
    return this.isCapturing;
  }

  setOnChunk(callback: AudioChunkCallback | null): void {
    this.onChunk = callback;
  }

  /**
   * 开始采集麦克风音频
   */
  async start(): Promise<void> {
    if (this.isCapturing) {
      return;
    }

    try {
      const capturerOptions: audio.AudioCapturerOptions = {
        streamInfo: {
          samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
          channels: audio.AudioChannel.CHANNEL_1,
          sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_F32LE,
          encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
        },
        capturerInfo: {
          source: audio.SourceType.SOURCE_TYPE_MIC,
          capturerFlags: 0
        }
      };

      this.capturer = await audio.createAudioCapturer(capturerOptions);
      await this.capturer.start();

      this.isCapturing = true;
      Logger.info(TAG, '麦克风采集已启动');

      this.startReadLoop();
    } catch (e) {
      Logger.error(TAG, `启动麦克风采集失败: ${JSON.stringify(e)}`);
      throw new Error('startAudioCapturer failed');
    }
  }

  /**
   * 读取循环：每 1s 读取约 64000 字节（16000 samples × 4 bytes）
   */
  private startReadLoop(): void {
    const readNext = (): void => {
      if (!this.isCapturing || !this.capturer) {
        return;
      }

      try {
        // 1s 的 PCM 数据大小 = 16000 samples × 4 bytes = 64000 bytes
        const bufferSize: number = 64000;

        this.capturer.read(bufferSize, true).then((buf: ArrayBuffer) => {
          if (!this.isCapturing) {
            return;
          }

          if (buf.byteLength > 0) {
            const base64Str: string = buffer.from(buf).toString('base64');
            if (this.onChunk) {
              this.onChunk(base64Str);
            }
          }

          // 立即读取下一帧
          if (this.isCapturing) {
            readNext();
          }
        }).catch((e: Error) => {
          Logger.error(TAG, `读取音频数据失败: ${e.message}`);
          if (this.isCapturing) {
            readNext();
          }
        });
      } catch (e) {
        Logger.error(TAG, `读取循环异常: ${e}`);
        if (this.isCapturing) {
          this.readTask = setTimeout(readNext, 1000);
        }
      }
    };

    readNext();
  }

  /**
   * 停止采集
   */
  async stop(): Promise<void> {
    this.isCapturing = false;

    if (this.readTask !== -1) {
      clearTimeout(this.readTask);
      this.readTask = -1;
    }

    try {
      if (this.capturer) {
        await this.capturer.stop();
        await this.capturer.release();
        this.capturer = null;
      }
    } catch (e) {
      Logger.error(TAG, `停止麦克风采集异常: ${e}`);
    }

    Logger.info(TAG, '麦克风采集已停止');
  }
}
