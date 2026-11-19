import * as Notifications from 'expo-notifications';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

export async function requestNotificationPermission(): Promise<boolean> {
  try {
    const current = await Notifications.getPermissionsAsync();
    if (current.granted) return true;
    const requested = await Notifications.requestPermissionsAsync();
    return Boolean(requested.granted);
  } catch {
    return false;
  }
}

/**
 * Local notification fired when an agent run finishes while the user is not looking
 * at the chat. True remote push needs a server-side FCM/APNs integration; that is a
 * documented future step, not required for the local PoC.
 */
export async function notifyRunCompleted(
  runId: string,
  conversationId: string,
  title: string,
  body: string,
): Promise<void> {
  try {
    await Notifications.scheduleNotificationAsync({
      content: {
        title,
        body,
        data: { runId, conversationId, url: `/conversations/${conversationId}` },
        sound: true,
      },
      trigger: null,
    });
  } catch {
    // Notifications are best-effort.
  }
}

export async function cancelAllNotifications(): Promise<void> {
  try {
    await Notifications.cancelAllScheduledNotificationsAsync();
  } catch {
    // Ignore.
  }
}
