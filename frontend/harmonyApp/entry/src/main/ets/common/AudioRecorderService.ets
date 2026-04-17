/**
 * AudioRecorderService - 麦克风录音服务（用于语音输入 ASR）
 * 使用 AVRecorder 录制 M4A/AAC 格式音频，16kHz 单声道，适配 Qwen3-ASR
 */
import { media } from '@kit.MediaKit';
import { fileIo } from '@kit.CoreFileKit';
import { Logger } from './Logger';
import { BusinessError } from '@kit.BasicServicesKit';

const TAG = 'AudioRecorderService';

const MAX_RECORDING_MS = 60_000;

export class AudioRecorderService {
  private avRecorder: media.AVRecorder | null = null;
  private outputFilePath: string = '';
  private outputFd: number = -1;
  private isCurrentlyRecording: boolean = false;
  private maxDurationTimer: number = -1;
  private onAutoStop: (() => void) | null = null;

  get isRecording(): boolean {
    return this.isCurrentlyRecording;
  }

  /**
   * Start recording audio to a temporary file in the app sandbox.
   * @param cacheDir - app cache directory path (e.g. context.cacheDir)
   * @param autoStopCallback - optional callback invoked when max duration reached
   */
  async startRecording(cacheDir: string, autoStopCallback?: () => void): Promise<void> {
    if (this.isCurrentlyRecording) {
      Logger.info(TAG, 'Already recording, ignoring duplicate start');
      return;
    }

    this.onAutoStop = autoStopCallback ?? null;

    this.outputFilePath = `${cacheDir}/silk_voice_${Date.now()}.m4a`;
    const file = fileIo.openSync(this.outputFilePath, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE);
    this.outputFd = file.fd;

    this.avRecorder = await media.createAVRecorder();

    this.avRecorder.on('error', (err: BusinessError) => {
      Logger.error(TAG, `AVRecorder error: code=${err.code} msg=${err.message}`);
      this.cleanupTimer();
    });

    const profile: media.AVRecorderProfile = {
      audioBitrate: 256_000,
      audioChannels: 1,
      audioCodec: media.CodecMimeType.AUDIO_AAC,
      audioSampleRate: 16_000,
      fileFormat: media.ContainerFormatType.CFT_MPEG_4A,
    };

    const config: media.AVRecorderConfig = {
      audioSourceType: media.AudioSourceType.AUDIO_SOURCE_TYPE_MIC,
      profile: profile,
      url: `fd://${this.outputFd}`,
    };

    await this.avRecorder.prepare(config);
    await this.avRecorder.start();
    this.isCurrentlyRecording = true;

    const timerId: number = setTimeout(async () => {
      Logger.info(TAG, 'Max recording duration reached, auto-stopping');
      if (this.isCurrentlyRecording) {
        await this.stopRecordingInternal();
        if (this.onAutoStop) {
          this.onAutoStop();
        }
      }
    }, MAX_RECORDING_MS);
    this.maxDurationTimer = timerId;

    Logger.info(TAG, `Recording started -> ${this.outputFilePath}`);
  }

  /**
   * Stop recording and return the path to the recorded audio file.
   */
  async stopRecording(): Promise<string> {
    return this.stopRecordingInternal();
  }

  private async stopRecordingInternal(): Promise<string> {
    this.cleanupTimer();

    if (!this.isCurrentlyRecording || !this.avRecorder) {
      Logger.info(TAG, 'Not recording, nothing to stop');
      return '';
    }

    try {
      await this.avRecorder.stop();
      await this.avRecorder.release();
    } catch (e) {
      Logger.error(TAG, `Error stopping recorder: ${JSON.stringify(e)}`);
    }

    if (this.outputFd >= 0) {
      try {
        fileIo.closeSync(this.outputFd);
      } catch (_e) {
        // fd may already be closed
      }
      this.outputFd = -1;
    }

    this.avRecorder = null;
    this.isCurrentlyRecording = false;

    Logger.info(TAG, `Recording stopped -> ${this.outputFilePath}`);
    return this.outputFilePath;
  }

  /**
   * Read the recorded file as an ArrayBuffer (for base64 encoding / upload).
   */
  readFileAsArrayBuffer(filePath: string): ArrayBuffer {
    const file = fileIo.openSync(filePath, fileIo.OpenMode.READ_ONLY);
    const stat = fileIo.statSync(filePath);
    const buf = new ArrayBuffer(stat.size);
    fileIo.readSync(file.fd, buf);
    fileIo.closeSync(file.fd);
    return buf;
  }

  /**
   * Delete the temporary recording file.
   */
  cleanupFile(filePath: string): void {
    try {
      fileIo.unlinkSync(filePath);
    } catch (_e) {
      // file may not exist
    }
  }

  private cleanupTimer(): void {
    if (this.maxDurationTimer >= 0) {
      clearTimeout(this.maxDurationTimer);
      this.maxDurationTimer = -1;
    }
  }
}
