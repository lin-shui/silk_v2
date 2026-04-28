/**
 * AudioDuplexPlayer - 音频全双工播放器
 * 使用 AudioRenderer 播放 24kHz mono float32 PCM 音频
 */
import { audio } from '@kit.AudioKit';
import { buffer } from '@kit.ArkTS';
import { Logger } from './Logger';

const TAG = 'AudioDuplexPlayer';

export class AudioDuplexPlayer {
  private renderer: audio.AudioRenderer | null = null;
  private isPlaying: boolean = false;
  private playQueue: ArrayBuffer[] = [];
  private isWriting: boolean = false;

  get playing(): boolean {
    return this.isPlaying;
  }

  /**
   * 初始化播放器
   */
  async init(): Promise<void> {
    try {
      if (this.renderer) {
        await this.release();
      }

      const rendererOptions: audio.AudioRendererOptions = {
        streamInfo: {
          samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_24000,
          channels: audio.AudioChannel.CHANNEL_1,
          sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_F32LE,
          encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
        },
        rendererInfo: {
          usage: audio.StreamUsage.STREAM_USAGE_VOICE_COMMUNICATION,
          rendererFlags: 0
        }
      };

      this.renderer = await audio.createAudioRenderer(rendererOptions);
      await this.renderer.start();
      this.isPlaying = true;

      Logger.info(TAG, '音频播放器已初始化');
    } catch (e) {
      Logger.error(TAG, `初始化音频播放器失败: ${JSON.stringify(e)}`);
      this.renderer = null;
      this.isPlaying = false;
    }
  }

  /**
   * 解码 base64 音频数据并加入播放队列
   */
  enqueueAudio(base64Data: string): void {
    if (!base64Data || !this.isPlaying) {
      return;
    }

    try {
      const decoded: buffer.Buffer = buffer.from(base64Data, 'base64');
      const audioBuffer: ArrayBuffer = decoded.buffer;
      this.playQueue.push(audioBuffer);
      this.tryWrite();
    } catch (e) {
      Logger.error(TAG, `解码音频失败: ${e}`);
    }
  }

  /**
   * 尝试写入音频数据到渲染器
   */
  private tryWrite(): void {
    if (this.isWriting || !this.renderer || this.playQueue.length === 0) {
      return;
    }

    this.isWriting = true;
    const chunk: ArrayBuffer | undefined = this.playQueue.shift();
    if (!chunk) {
      this.isWriting = false;
      return;
    }

    this.renderer.write(chunk).then((writtenBytes: number) => {
      Logger.debug(TAG, `已写入 ${writtenBytes} 字节音频`);
      this.isWriting = false;

      // 继续写入队列中的剩余数据
      if (this.playQueue.length > 0) {
        this.tryWrite();
      }
    }).catch((e: Error) => {
      Logger.error(TAG, `写入音频失败: ${e.message}`);
      this.isWriting = false;
    });
  }

  /**
   * 清空播放队列
   */
  flush(): void {
    this.playQueue = [];
  }

  /**
   * 停止并释放播放器
   */
  async release(): Promise<void> {
    this.isPlaying = false;
    this.flush();
    this.isWriting = false;

    try {
      if (this.renderer) {
        await this.renderer.stop();
        await this.renderer.release();
        this.renderer = null;
      }
    } catch (e) {
      Logger.error(TAG, `释放播放器异常: ${e}`);
    }

    Logger.info(TAG, '音频播放器已释放');
  }
}
