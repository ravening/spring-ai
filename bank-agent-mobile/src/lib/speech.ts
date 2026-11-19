import AudioModule from 'expo-audio/build/AudioModule';
import {
  RecordingPresets,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
} from 'expo-audio';
import * as Speech from 'expo-speech';
import type { AttachmentAsset } from './types';

declare const require: (moduleId: string) => any;

interface RecognitionResultEvent {
  isFinal: boolean;
  results: Array<{ transcript: string; confidence: number }>;
}
interface RecognitionErrorEvent {
  error: string;
  message?: string;
}
interface OnDeviceSttModule {
  addListener(event: 'result' | 'error', listener: (event: any) => void): { remove(): void };
  start(options: Record<string, unknown>): void;
  stop(): void;
  abort(): void;
  requestPermissionsAsync(): Promise<{ granted: boolean }>;
}

/**
 * The on-device recognizer is an optional native module (not part of Expo Go).
 * Loading it lazily keeps the app usable in Expo Go, where server STT is the fallback.
 */
let cachedModule: OnDeviceSttModule | null | undefined;
export function getOnDeviceSttModule(): OnDeviceSttModule | null {
  if (cachedModule !== undefined) return cachedModule;
  try {
    const mod = require('expo-speech-recognition');
    const candidate = mod?.ExpoSpeechRecognitionModule;
    cachedModule =
      candidate && typeof candidate.start === 'function' ? (candidate as OnDeviceSttModule) : null;
  } catch {
    cachedModule = null;
  }
  return cachedModule;
}

export function isOnDeviceSttAvailable(): boolean {
  return getOnDeviceSttModule() !== null;
}

/** On-device speech-to-text. Resolves with the final transcript or throws. */
export async function transcribeOnDevice(options: { timeoutMs?: number; onPartial?: (text: string) => void } = {}): Promise<string> {
  const module = getOnDeviceSttModule();
  if (!module) throw new Error('On-device speech recognition is not available on this build');

  const permissions = await module.requestPermissionsAsync();
  if (!permissions.granted) throw new Error('Microphone / speech recognition permission denied');

  const { timeoutMs = 15_000, onPartial } = options;
  return new Promise<string>((resolve, reject) => {
    let settled = false;
    let finalText = '';
    let partialText = '';
    const subscriptions: Array<{ remove(): void }> = [];

    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      subscriptions.forEach((subscription) => subscription.remove());
      try {
        module.abort();
      } catch {
        // ignore
      }
      if (error) reject(error);
      else resolve(finalText.trim() || partialText.trim());
    };

    const timer = setTimeout(() => finish(new Error('No speech detected before the timeout')), timeoutMs);

    subscriptions.push(
      module.addListener('result', (event: RecognitionResultEvent) => {
        const transcript = (event.results ?? []).map((result) => result.transcript ?? '').join(' ').trim();
        if (!transcript) return;
        if (event.isFinal) finalText = transcript;
        else {
          partialText = transcript;
          onPartial?.(transcript);
        }
        if (event.isFinal) finish();
      }),
    );
    subscriptions.push(
      module.addListener('error', (event: RecognitionErrorEvent) => {
        finish(new Error(event.message || event.error || 'Speech recognition failed'));
      }),
    );

    try {
      module.start({
        lang: 'en-US',
        interimResults: true,
        continuous: false,
        requiresOnDeviceRecognition: true,
        addsPunctuation: true,
      });
    } catch (error) {
      finish(error instanceof Error ? error : new Error('Could not start speech recognition'));
    }
  });
}

/** Records microphone audio for the server-side STT fallback (POST /api/chat/voice). */
type Recorder = InstanceType<typeof AudioModule.AudioRecorder>;

export class AudioCapture {
  private recorder: Recorder | null = null;

  async start(): Promise<void> {
    const permission = await requestRecordingPermissionsAsync();
    if (!permission.granted) throw new Error('Microphone permission denied');
    await setAudioModeAsync({ playsInSilentMode: true, allowsRecording: true });
    this.recorder = new AudioModule.AudioRecorder(RecordingPresets.LOW_QUALITY);
    await this.recorder.prepareToRecordAsync();
    this.recorder.record();
  }

  async stop(): Promise<AttachmentAsset> {
    if (!this.recorder) throw new Error('Recording was not started');
    await this.recorder.stop();
    const uri = this.recorder.uri;
    this.recorder = null;
    if (!uri) throw new Error('No audio was recorded');
    return { uri, name: `voice-${Date.now()}.m4a`, mimeType: 'audio/m4a' };
  }

  async cancel(): Promise<void> {
    if (!this.recorder) return;
    try {
      await this.recorder.stop();
    } catch {
      // ignore
    }
    this.recorder = null;
  }
}

export async function isTtsAvailable(): Promise<boolean> {
  try {
    const voices = await Speech.getAvailableVoicesAsync();
    return voices.length > 0;
  } catch {
    return false;
  }
}

export async function speak(text: string, onDone?: () => void): Promise<void> {
  if (!text) return;
  await Speech.speak(text, {
    language: 'en-US',
    onDone,
    onError: () => onDone?.(),
  });
}

export async function stopSpeaking(): Promise<void> {
  try {
    await Speech.stop();
  } catch {
    // ignore
  }
}
