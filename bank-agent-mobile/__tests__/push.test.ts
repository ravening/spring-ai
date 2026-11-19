jest.mock('../src/lib/notifications', () => ({ requestNotificationPermission: jest.fn(async () => true) }));
jest.mock('expo-notifications', () => ({
  AndroidImportance: { MAX: 5 },
  setNotificationChannelAsync: jest.fn(async () => undefined),
  getExpoPushTokenAsync: jest.fn(async () => ({ data: 'ExpoPushToken[abc]' })),
  setNotificationHandler: jest.fn(),
}));
jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { extra: { eas: { projectId: 'project-123' } } } },
}));
jest.mock('react-native', () => ({ Platform: { OS: 'ios' } }));
jest.mock('../src/lib/api', () => ({
  api: {
    registerPushToken: jest.fn(async () => ({ registered: true, userId: 'alice' })),
    unregisterPushToken: jest.fn(async () => undefined),
  },
}));

import { api } from '../src/lib/api';
import { registerDeviceForPush, unregisterDeviceForPush } from '../src/lib/push';

const mockRegister = api.registerPushToken as jest.Mock;
const mockUnregister = api.unregisterPushToken as jest.Mock;

describe('push registration', () => {
  beforeEach(() => {
    mockRegister.mockClear();
    mockUnregister.mockClear();
  });

  it('registers the Expo push token with the backend', async () => {
    const result = await registerDeviceForPush('alice');
    expect(result.token).toBe('ExpoPushToken[abc]');
    expect(mockRegister).toHaveBeenCalledWith('ExpoPushToken[abc]', 'alice', expect.any(String));
  });

  it('unregisters on logout', async () => {
    await unregisterDeviceForPush('alice');
    expect(mockUnregister).toHaveBeenCalledWith('alice');
  });
});
