import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { api, ApiError } from '@/lib/api';
import { appStorage, type AppSettings } from '@/lib/storage';
import { authTokens, restoreBearerToken, setBearerToken } from '@/lib/auth';
import { registerDeviceForPush, unregisterDeviceForPush } from '@/lib/push';
import {
  applyRead, applyReadAll, mergeNotifications, nextBackoff, nextCursor,
  POLL_INTERVAL_MS, popupCandidate,
} from '@/lib/notificationSync';
import type {
  BankUser, ConversationSummary, NotificationDto, StartRunResponse,
} from '@/lib/types';

interface AppContextValue {
  users: BankUser[];
  userId: string | null;
  conversations: ConversationSummary[];
  settings: AppSettings;
  booted: boolean;
  connected: boolean;
  authEnabled: boolean;
  toast: string | null;
  // Notification inbox
  notifications: NotificationDto[];
  unreadCount: number;
  popup: NotificationDto | null;
  refreshNotifications: () => Promise<void>;
  markRead: (id: number) => Promise<void>;
  markAllRead: () => Promise<void>;
  markNotificationSurfaced: (id: number) => Promise<void>;
  dismissPopup: (id?: number) => void;
  selectUser: (userId: string) => Promise<void>;
  loginWithToken: (token: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshConversations: () => Promise<void>;
  createConversation: () => Promise<ConversationSummary>;
  deleteConversation: (id: string) => Promise<void>;
  startRun: (message: string, conversationId?: string) => Promise<StartRunResponse>;
  updateSettings: (patch: Partial<AppSettings>) => Promise<void>;
  showToast: (message: string) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [users, setUsers] = useState<BankUser[]>([]);
  const [userId, setUserId] = useState<string | null>(null);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [settings, setSettings] = useState<AppSettings>({
    autoSpeak: false,
    preferredVoiceMode: 'auto',
    streamingEnabled: true,
    biometricLock: false,
    notificationsEnabled: true,
    apiBaseUrl: api.baseUrl,
  });
  const [booted, setBooted] = useState(false);
  const [connected, setConnected] = useState(true);
  const [authEnabled, setAuthEnabled] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Notification inbox state + polling bookkeeping.
  const [notifications, setNotifications] = useState<NotificationDto[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [popup, setPopup] = useState<NotificationDto | null>(null);
  const notificationsRef = useRef<NotificationDto[]>([]);
  const cursorRef = useRef(0);
  const surfacedRef = useRef<Set<number>>(new Set());
  const backoffRef = useRef(POLL_INTERVAL_MS);
  const settingsRef = useRef(settings);

  useEffect(() => { settingsRef.current = settings; }, [settings]);

  const showToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 3500);
  }, []);

  const refreshConversations = useCallback(async () => {
    if (!userId) {
      setConversations([]);
      return;
    }
    try {
      const fresh = await api.listConversations(userId);
      setConversations(fresh);
      setConnected(true);
      await appStorage.setConversations(userId, fresh);
    } catch {
      setConnected(false);
      const cached = await appStorage.getConversations(userId);
      if (cached) setConversations(cached);
    }
  }, [userId]);

  useEffect(() => {
    let mounted = true;
    (async () => {
      const [savedSettings, savedUser] = await Promise.all([
        appStorage.getSettings(),
        appStorage.getSelectedUser(),
      ]);
      if (!mounted) return;
      setSettings(savedSettings);
      settingsRef.current = savedSettings;
      api.setBaseUrl(savedSettings.apiBaseUrl || api.baseUrl);

      const token = await restoreBearerToken();
      let resolvedUser = savedUser;
      try {
        const status = await api.authStatus();
        if (mounted) {
          setAuthEnabled(status.enabled);
          setConnected(true);
        }
        if (status.enabled && token) {
          const identity = await api.authMe();
          resolvedUser = identity.userId;
          if (mounted) setUserId(identity.userId);
        } else if (!status.enabled) {
          if (mounted) setUserId(savedUser);
        } else {
          resolvedUser = null;
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          if (mounted) {
            setAuthEnabled(true);
            setConnected(true);
          }
          resolvedUser = null;
        } else if (mounted) {
          setConnected(false);
        }
      }

      try {
        const directory = await api.listUsers();
        if (mounted) setUsers(directory);
      } catch {
        if (mounted) setConnected(false);
      }
      if (resolvedUser) {
        const cached = await appStorage.getConversations(resolvedUser);
        if (mounted && cached) setConversations(cached);
      }
      setBooted(true);
    })();
    return () => {
      mounted = false;
    };
  }, []);

  // Refresh whenever the active identity changes.
  useEffect(() => {
    if (booted && userId) void refreshConversations();
  }, [booted, userId, refreshConversations]);

  // While any run is active, keep the sidebar badges in sync across screens.
  const hasActiveRun = conversations.some((conversation) => conversation.active);
  useEffect(() => {
    if (!hasActiveRun) return;
    const interval = setInterval(() => void refreshConversations(), 2500);
    return () => clearInterval(interval);
  }, [hasActiveRun, refreshConversations]);

  // ---- Notification polling (foreground only, gated by the global toggle) ----

  const syncCursorToLatest = useCallback(async (user: string) => {
    try {
      const page = await api.listNotifications(user, 0, 100);
      const latest = nextCursor(0, page.items);
      cursorRef.current = latest;
      await appStorage.setNotificationCursor(user, latest).catch(() => undefined);
      const merged = mergeNotifications(notificationsRef.current, page.items);
      notificationsRef.current = merged;
      setNotifications(merged);
      setUnreadCount(page.unreadCount);
      await appStorage.setNotifications(user, merged).catch(() => undefined);
    } catch {
      // Offline: keep the existing cursor; the next successful poll catches up.
    }
  }, []);

  useEffect(() => {
    if (!booted || !userId || !settings.notificationsEnabled) {
      setPopup(null);
      setUnreadCount(0);
      return;
    }
    const user = userId;
    let mounted = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let previousState = AppState.currentState;

    const poll = async () => {
      if (!mounted) return;
      const previousCursor = cursorRef.current;
      try {
        const page = await api.listNotifications(user, previousCursor, 100);
        if (!mounted) return;
        const merged = mergeNotifications(notificationsRef.current, page.items);
        notificationsRef.current = merged;
        setNotifications(merged);
        setUnreadCount(page.unreadCount);
        await appStorage.setNotifications(user, merged).catch(() => undefined);

        const candidate = popupCandidate(page.items, previousCursor, surfacedRef.current);
        if (candidate && AppState.currentState === 'active') {
          setPopup(candidate);
          surfacedRef.current.add(candidate.id);
          await appStorage.setSurfacedIds(user, [...surfacedRef.current]).catch(() => undefined);
        }
        const next = nextCursor(previousCursor, page.items);
        if (next !== cursorRef.current) {
          cursorRef.current = next;
          await appStorage.setNotificationCursor(user, next).catch(() => undefined);
        }
        backoffRef.current = POLL_INTERVAL_MS;
      } catch {
        backoffRef.current = nextBackoff(backoffRef.current);
      } finally {
        if (!mounted) return;
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => void poll(), backoffRef.current);
      }
    };

    const start = async () => {
      const [cached, storedCursor, storedSurfaced] = await Promise.all([
        appStorage.getNotifications(user),
        appStorage.getNotificationCursor(user),
        appStorage.getSurfacedIds(user),
      ]);
      if (!mounted) return;
      cursorRef.current = Math.max(cursorRef.current, storedCursor);
      surfacedRef.current = new Set(storedSurfaced);
      notificationsRef.current = cached;
      setNotifications(cached);
      // Offline display: derive the badge from the cached read flags until the
      // first successful poll replaces it with the authoritative server count.
      setUnreadCount(cached.filter((item) => !item.read).length);
      backoffRef.current = POLL_INTERVAL_MS;
      void poll();
    };

    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active' && previousState !== 'active') {
        if (timer) clearTimeout(timer);
        void poll();
      }
      previousState = state;
    });
    void start();

    return () => {
      mounted = false;
      if (timer) clearTimeout(timer);
      subscription.remove();
    };
  }, [booted, userId, settings.notificationsEnabled]);

  const refreshNotifications = useCallback(async () => {
    if (!userId || !settings.notificationsEnabled) return;
    const previousCursor = cursorRef.current;
    try {
      const page = await api.listNotifications(userId, previousCursor, 100);
      const merged = mergeNotifications(notificationsRef.current, page.items);
      notificationsRef.current = merged;
      setNotifications(merged);
      setUnreadCount(page.unreadCount);
      await appStorage.setNotifications(userId, merged).catch(() => undefined);
      const next = nextCursor(previousCursor, page.items);
      if (next !== cursorRef.current) {
        cursorRef.current = next;
        await appStorage.setNotificationCursor(userId, next).catch(() => undefined);
      }
    } catch {
      // Keep cached data when offline.
    }
  }, [userId, settings.notificationsEnabled]);

  const markRead = useCallback(async (id: number) => {
    if (!userId) return;
    const wasUnread = notificationsRef.current.some((item) => item.id === id && !item.read);
    const updated = applyRead(notificationsRef.current, id);
    notificationsRef.current = updated;
    setNotifications(updated);
    if (wasUnread) setUnreadCount((current) => Math.max(0, current - 1));
    surfacedRef.current.add(id);
    await appStorage.setSurfacedIds(userId, [...surfacedRef.current]).catch(() => undefined);
    await appStorage.setNotifications(userId, updated).catch(() => undefined);
    await api.markNotificationRead(id, userId).catch(() => undefined);
  }, [userId]);

  const markAllRead = useCallback(async () => {
    if (!userId) return;
    const updated = applyReadAll(notificationsRef.current);
    notificationsRef.current = updated;
    setNotifications(updated);
    setUnreadCount(0);
    await appStorage.setNotifications(userId, updated).catch(() => undefined);
    await api.markAllNotificationsRead(userId).catch(() => undefined);
  }, [userId]);

  const markNotificationSurfaced = useCallback(async (id: number) => {
    if (!userId) return;
    surfacedRef.current.add(id);
    await appStorage.setSurfacedIds(userId, [...surfacedRef.current]).catch(() => undefined);
    setPopup((current) => (current?.id === id ? null : current));
  }, [userId]);

  const dismissPopup = useCallback((id?: number) => {
    if (id !== undefined && userId) {
      surfacedRef.current.add(id);
      void appStorage.setSurfacedIds(userId, [...surfacedRef.current]).catch(() => undefined);
    }
    setPopup(null);
  }, [userId]);

  const selectUser = useCallback(async (nextUserId: string) => {
    setUserId(nextUserId);
    setConversations([]);
    setPopup(null);
    await appStorage.setSelectedUser(nextUserId);
    const cached = await appStorage.getConversations(nextUserId);
    setConversations(cached ?? []);
    try {
      const fresh = await api.listConversations(nextUserId);
      setConversations(fresh);
      setConnected(true);
      await appStorage.setConversations(nextUserId, fresh);
      // Push is best effort; registration is gated by the notifications toggle.
      await registerDeviceForPush(nextUserId);
    } catch {
      setConnected(false);
    }
  }, []);

  const loginWithToken = useCallback(async (token: string) => {
    setBearerToken(token);
    await authTokens.set(token);
    const identity = await api.authMe();
    await appStorage.setSelectedUser(identity.userId);
    setUserId(identity.userId);
    setAuthEnabled(true);
    setConnected(true);
    setConversations([]);
    setPopup(null);
    await registerDeviceForPush(identity.userId);
    showToast(`Signed in as ${identity.userId}`);
  }, [showToast]);

  const logout = useCallback(async () => {
    if (userId) {
      await unregisterDeviceForPush(userId);
      await appStorage.clearNotificationCache(userId).catch(() => undefined);
    }
    setBearerToken(null);
    await authTokens.clear();
    setUserId(null);
    setConversations([]);
    notificationsRef.current = [];
    cursorRef.current = 0;
    surfacedRef.current = new Set();
    setNotifications([]);
    setUnreadCount(0);
    setPopup(null);
    showToast('Signed out');
  }, [userId, showToast]);

  const createConversation = useCallback(async () => {
    if (!userId) throw new Error('Choose a customer first');
    const created = await api.createConversation(userId);
    await refreshConversations();
    return created;
  }, [userId, refreshConversations]);

  const deleteConversation = useCallback(async (id: string) => {
    if (!userId) throw new Error('Choose a customer first');
    await api.deleteConversation(id, userId);
    await appStorage.removeConversation(userId, id);
    await refreshConversations();
  }, [userId, refreshConversations]);

  const startRun = useCallback(async (message: string, conversationId?: string) => {
    if (!userId) throw new Error('Choose a customer first');
    const started = await api.sendMessage(userId, message, conversationId);
    await refreshConversations();
    return started;
  }, [userId, refreshConversations]);

  const updateSettings = useCallback(async (patch: Partial<AppSettings>) => {
    const wasEnabled = settingsRef.current.notificationsEnabled;
    const enabling = patch.notificationsEnabled === true && !wasEnabled;
    const disabling = patch.notificationsEnabled === false && wasEnabled;
    setSettings((current) => {
      const next = { ...current, ...patch };
      settingsRef.current = next;
      if (patch.apiBaseUrl) api.setBaseUrl(patch.apiBaseUrl);
      void appStorage.setSettings(next);
      return next;
    });
    if (enabling && userId) {
      // Jump the cursor to the current max so enabling does not replay old items.
      await syncCursorToLatest(userId);
      await registerDeviceForPush(userId);
    } else if (disabling && userId) {
      await unregisterDeviceForPush(userId);
      notificationsRef.current = [];
      cursorRef.current = 0;
      surfacedRef.current = new Set();
      setNotifications([]);
      setUnreadCount(0);
      setPopup(null);
      await appStorage.clearNotificationCache(userId).catch(() => undefined);
    }
  }, [userId, syncCursorToLatest]);

  const value = useMemo<AppContextValue>(
    () => ({
      users, userId, conversations, settings, booted, connected, authEnabled, toast,
      notifications, unreadCount, popup, refreshNotifications, markRead, markAllRead,
      markNotificationSurfaced, dismissPopup,
      selectUser, loginWithToken, logout, refreshConversations, createConversation,
      deleteConversation, startRun, updateSettings, showToast,
    }),
    [
      users, userId, conversations, settings, booted, connected, authEnabled, toast,
      notifications, unreadCount, popup, refreshNotifications, markRead, markAllRead,
      markNotificationSurfaced, dismissPopup,
      selectUser, loginWithToken, logout, refreshConversations, createConversation,
      deleteConversation, startRun, updateSettings, showToast,
    ],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const context = useContext(AppContext);
  if (!context) throw new Error('useApp must be used inside AppProvider');
  return context;
}
