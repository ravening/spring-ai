jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  getPermissionsAsync: jest.fn(async () => ({ granted: true })),
  requestPermissionsAsync: jest.fn(async () => ({ granted: true })),
  scheduleNotificationAsync: jest.fn(async () => 'notification-id'),
  cancelAllScheduledNotificationsAsync: jest.fn(async () => undefined),
}));

import * as Notifications from 'expo-notifications';
import { notifyRunCompleted, requestNotificationPermission } from '../src/lib/notifications';

describe('notifications', () => {
  beforeEach(() => (Notifications.scheduleNotificationAsync as jest.Mock).mockClear());

  it('requests permission', async () => {
    await expect(requestNotificationPermission()).resolves.toBe(true);
  });

  it('schedules a run-completion notification with a deep link', async () => {
    await notifyRunCompleted('run-1', 'conv-1', 'Answer ready', 'Balance is 10 EUR');
    expect(Notifications.scheduleNotificationAsync).toHaveBeenCalledWith({
      content: expect.objectContaining({
        title: 'Answer ready',
        body: 'Balance is 10 EUR',
        data: expect.objectContaining({
          runId: 'run-1', conversationId: 'conv-1', url: '/conversations/conv-1',
        }),
      }),
      trigger: null,
    });
  });
});
