import * as Notifications from 'expo-notifications';
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { api } from './api';
import { requestNotificationPermission } from './notifications';

export interface PushRegistrationResult {
  token: string | null;
  error?: string;
}

/**
 * Registers this device with the backend so completed runs can generate a remote push.
 * An EAS projectId is required for Expo push tokens; without one we fall back to the
 * local notifications already used by the chat screen.
 */
export async function registerDeviceForPush(userId: string): Promise<PushRegistrationResult> {
  try {
    const granted = await requestNotificationPermission();
    if (!granted) return { token: null, error: 'Notification permission denied' };

    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('default', {
        name: 'default',
        importance: Notifications.AndroidImportance.MAX,
        sound: 'default',
      });
    }

    const projectId = Constants.expoConfig?.extra?.eas?.projectId as string | undefined;
    if (!projectId) {
      return { token: null, error: 'No EAS projectId configured – using local notifications' };
    }

    const pushToken = (await Notifications.getExpoPushTokenAsync({ projectId })).data;
    if (!pushToken) return { token: null, error: 'Could not obtain a push token' };

    await api.registerPushToken(pushToken, userId, Platform.OS);
    return { token: pushToken };
  } catch (error) {
    return { token: null, error: error instanceof Error ? error.message : 'Push registration failed' };
  }
}

export async function unregisterDeviceForPush(userId: string): Promise<void> {
  try {
    await api.unregisterPushToken(userId);
  } catch {
    // Best effort.
  }
}
