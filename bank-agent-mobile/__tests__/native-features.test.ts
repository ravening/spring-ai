const mockStart = jest.fn();
const mockStop = jest.fn();
const mockAbort = jest.fn();
const mockRequestSttPermissions = jest.fn(async () => ({ granted: true }));
const resultListeners: Array<(event: unknown) => void> = [];

jest.mock('expo-speech-recognition', () => ({
  ExpoSpeechRecognitionModule: {
    addListener: (event: string, listener: (event: unknown) => void) => {
      if (event === 'result') resultListeners.push(listener);
      return { remove: jest.fn() };
    },
    start: mockStart,
    stop: mockStop,
    abort: mockAbort,
    requestPermissionsAsync: mockRequestSttPermissions,
  },
}));

jest.mock('expo-speech', () => ({
  speak: jest.fn((_text: string, _options: unknown) => undefined),
  getAvailableVoicesAsync: jest.fn(async () => [{ identifier: 'en-US' }]),
  stop: jest.fn(async () => undefined),
}));

jest.mock('expo-audio', () => ({
  RecordingPresets: { LOW_QUALITY: {}, HIGH_QUALITY: {} },
  requestRecordingPermissionsAsync: jest.fn(async () => ({ granted: true })),
  setAudioModeAsync: jest.fn(async () => undefined),
}));

jest.mock('expo-audio/build/AudioModule', () => ({
  __esModule: true,
  default: {
    AudioRecorder: class {
      uri = 'file:///tmp/recording.m4a';
      async prepareToRecordAsync() { return undefined; }
      record() { return undefined; }
      async stop() { return undefined; }
    },
  },
}));

import * as Speech from 'expo-speech';
import {
  getOnDeviceSttModule, isOnDeviceSttAvailable, speak, transcribeOnDevice,
} from '../src/lib/speech';

describe('native speech features', () => {
  beforeEach(() => {
    resultListeners.length = 0;
    mockStart.mockClear();
  });

  it('loads the on-device recognizer module', () => {
    expect(isOnDeviceSttAvailable()).toBe(true);
    expect(getOnDeviceSttModule()).not.toBeNull();
  });

  it('starts recognition with requiresOnDeviceRecognition and returns the final transcript', async () => {
    mockStart.mockImplementation(() => {
      setTimeout(() => {
        resultListeners.forEach((listener) =>
          listener({ isFinal: true, results: [{ transcript: 'show my balance', confidence: 1 }] }));
      }, 0);
    });
    const transcript = await transcribeOnDevice({ timeoutMs: 5000 });
    expect(transcript).toBe('show my balance');
    expect(mockStart).toHaveBeenCalledWith(expect.objectContaining({
      requiresOnDeviceRecognition: true,
      lang: 'en-US',
    }));
  });

  it('speaks the answer on-device', async () => {
    await speak('Your balance is 10 EUR');
    expect(Speech.speak).toHaveBeenCalledWith(
      'Your balance is 10 EUR',
      expect.objectContaining({ language: 'en-US' }),
    );
  });
});
