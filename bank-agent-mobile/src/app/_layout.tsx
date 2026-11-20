import React, { useCallback, useEffect, useState } from 'react';
import { AppState, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Stack, router, useRootNavigationState } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as LocalAuthentication from 'expo-local-authentication';
import * as Notifications from 'expo-notifications';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { AppProvider, useApp } from '@/state/AppContext';
import { requestNotificationPermission } from '@/lib/notifications';
import { logError } from '@/lib/crashlog';

function UnlockGate({ children }: { children: React.ReactNode }) {
  const { settings } = useApp();
  const [locked, setLocked] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const unlock = useCallback(async () => {
    try {
      const result = await LocalAuthentication.authenticateAsync({
        promptMessage: 'Unlock your banking assistant',
        fallbackLabel: 'Use passcode',
      });
      if (result.success) {
        setLocked(false);
        setError(null);
      } else {
        setError('Authentication was not successful. Try again.');
      }
    } catch {
      setError('Biometric authentication is unavailable. Turn it off in Settings.');
    }
  }, []);

  useEffect(() => {
    if (!settings.biometricLock) {
      setLocked(false);
      return;
    }
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'background' || state === 'inactive') setLocked(true);
    });
    // Lock on first launch as well once the preference is enabled.
    setLocked(true);
    return () => subscription.remove();
  }, [settings.biometricLock]);

  if (locked && settings.biometricLock) {
    return (
      <View style={styles.lockContainer}>
        <Text style={styles.lockIcon}>🔒</Text>
        <Text style={styles.lockTitle}>Bank Agent is locked</Text>
        <Text style={styles.lockBody}>Unlock to continue where you left off.</Text>
        {error && <Text style={styles.lockError}>{error}</Text>}
        <TouchableOpacity style={styles.unlockButton} onPress={() => void unlock()}>
          <Text style={styles.unlockText}>Unlock</Text>
        </TouchableOpacity>
      </View>
    );
  }
  return <>{children}</>;
}

function RootStack() {
  const {
    toast, booted, connected, showToast, unreadCount, popup,
    markRead, markNotificationSurfaced, dismissPopup, settings,
  } = useApp();
  const navigationState = useRootNavigationState();

  useEffect(() => {
    void requestNotificationPermission();
    const errorUtils = (globalThis as { ErrorUtils?: { setGlobalHandler: (handler: (error: Error, fatal?: boolean) => void) => void } }).ErrorUtils;
    errorUtils?.setGlobalHandler((error, fatal) => {
      void logError(error, fatal ? 'fatal' : 'javascript');
    });
  }, []);

  useEffect(() => {
    if (!navigationState?.key) return;
    const subscription = Notifications.addNotificationResponseReceivedListener((response) => {
      const data = response.notification.request.content.data;
      // Push is only a wake-up: mark the authoritative notification read, then
      // deep-link to its originating chat. Polling will not re-pop this id.
      const notificationId = data?.notificationId;
      if (typeof notificationId === 'number') void markRead(notificationId);
      else if (typeof notificationId === 'string' && /^\d+$/.test(notificationId)) {
        void markRead(Number(notificationId));
      }
      const url = data?.url;
      if (typeof url === 'string' && url.startsWith('/')) {
        router.navigate(url as never);
      }
    });
    return () => subscription.remove();
  }, [navigationState?.key, markRead]);

  // A received push suppresses the duplicate polling popup for that id; the tap
  // handler above still marks it read and deep-links into the originating chat.
  useEffect(() => {
    const subscription = Notifications.addNotificationReceivedListener((notification) => {
      const notificationId = notification.request.content.data?.notificationId;
      if (typeof notificationId === 'number') void markNotificationSurfaced(notificationId);
      else if (typeof notificationId === 'string' && /^\d+$/.test(notificationId)) {
        void markNotificationSurfaced(Number(notificationId));
      }
    });
    return () => subscription.remove();
  }, [markNotificationSurfaced]);

  // OS badge mirrors the authoritative server unread count; cleared when disabled.
  useEffect(() => {
    Notifications.setBadgeCountAsync(settings.notificationsEnabled ? unreadCount : 0).catch(() => undefined);
  }, [unreadCount, settings.notificationsEnabled]);

  // In-app banner queue: one popup at a time, auto-dismissed after ~5 seconds.
  useEffect(() => {
    if (!popup) return;
    const timer = setTimeout(() => dismissPopup(popup.id), 5000);
    return () => clearTimeout(timer);
  }, [popup, dismissPopup]);

  useEffect(() => {
    if (booted && !connected) showToast('Offline – showing cached conversations');
  }, [booted, connected, showToast]);

  return (
    <>
      <StatusBar style="dark" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: '#FFFFFF' },
          headerTintColor: '#0B6E4F',
          headerTitleStyle: { fontWeight: '700' },
          contentStyle: { backgroundColor: '#F6FAF8' },
        }}
      >
        <Stack.Screen name="index" options={{ headerShown: false }} />
        <Stack.Screen name="login" options={{ title: 'Choose customer' }} />
        <Stack.Screen name="conversations/index" options={{ title: 'Conversations' }} />
        <Stack.Screen name="conversations/[id]" options={{ title: 'Chat' }} />
        <Stack.Screen name="notifications/index" options={{ title: 'Notifications' }} />
        <Stack.Screen name="settings" options={{ title: 'Settings' }} />
        <Stack.Screen name="debug" options={{ title: 'Connection & tools' }} />
      </Stack>
      {popup && (
        <TouchableOpacity
          style={styles.banner}
          accessibilityLabel={`Open notification: ${popup.title}`}
          onPress={() => {
            const id = popup.id;
            dismissPopup(id);
            void markRead(id);
            router.navigate(`/conversations/${popup.conversationId}` as never);
          }}
        >
          <Text style={styles.bannerTitle}>🔔 {popup.title}</Text>
          <Text style={styles.bannerBody} numberOfLines={2}>{popup.body}</Text>
        </TouchableOpacity>
      )}
      {toast && (
        <View style={styles.toast} pointerEvents="none">
          <Text style={styles.toastText}>{toast}</Text>
        </View>
      )}
    </>
  );
}

export default function RootLayout() {
  return (
    <ErrorBoundary>
      <AppProvider>
        <UnlockGate>
          <RootStack />
        </UnlockGate>
      </AppProvider>
    </ErrorBoundary>
  );
}

const styles = StyleSheet.create({
  lockContainer: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F6FAF8', padding: 24 },
  lockIcon: { fontSize: 44, marginBottom: 12 },
  lockTitle: { fontSize: 22, fontWeight: '700', color: '#10251B' },
  lockBody: { fontSize: 15, color: '#4B6357', marginTop: 8, textAlign: 'center' },
  lockError: { color: '#B3261E', marginTop: 12, textAlign: 'center' },
  unlockButton: { marginTop: 24, backgroundColor: '#0B6E4F', borderRadius: 10, paddingVertical: 12, paddingHorizontal: 36 },
  unlockText: { color: '#FFFFFF', fontWeight: '700', fontSize: 16 },
  banner: {
    position: 'absolute', left: 12, right: 12, top: 12, backgroundColor: '#FFFFFF',
    borderRadius: 12, padding: 14, borderWidth: 1, borderColor: '#0B6E4F',
    shadowColor: '#000', shadowOpacity: 0.15, shadowRadius: 6, elevation: 6,
  },
  bannerTitle: { fontSize: 15, fontWeight: '700', color: '#10251B' },
  bannerBody: { fontSize: 13, color: '#4B6357', marginTop: 4 },
  toast: {
    position: 'absolute', left: 20, right: 20, bottom: 28, backgroundColor: '#10251B',
    borderRadius: 10, padding: 12, alignItems: 'center', opacity: 0.95,
  },
  toastText: { color: '#FFFFFF', fontSize: 14 },
});
