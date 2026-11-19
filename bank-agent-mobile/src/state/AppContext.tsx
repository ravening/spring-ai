import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import { appStorage, type AppSettings } from '@/lib/storage';
import { authTokens, restoreBearerToken, setBearerToken } from '@/lib/auth';
import { registerDeviceForPush, unregisterDeviceForPush } from '@/lib/push';
import type { BankUser, ConversationSummary, StartRunResponse } from '@/lib/types';

interface AppContextValue {
  users: BankUser[];
  userId: string | null;
  conversations: ConversationSummary[];
  settings: AppSettings;
  booted: boolean;
  connected: boolean;
  authEnabled: boolean;
  toast: string | null;
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
    apiBaseUrl: api.baseUrl,
  });
  const [booted, setBooted] = useState(false);
  const [connected, setConnected] = useState(true);
  const [authEnabled, setAuthEnabled] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

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
          // Auth enabled but no token: wait for token sign-in.
          resolvedUser = null;
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          // The status endpoint itself is protected: auth is definitely enabled.
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

  const selectUser = useCallback(async (nextUserId: string) => {
    setUserId(nextUserId);
    setConversations([]);
    await appStorage.setSelectedUser(nextUserId);
    const cached = await appStorage.getConversations(nextUserId);
    setConversations(cached ?? []);
    try {
      const fresh = await api.listConversations(nextUserId);
      setConversations(fresh);
      setConnected(true);
      await appStorage.setConversations(nextUserId, fresh);
      // Push is best effort; a missing EAS projectId just means local notifications.
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
    await registerDeviceForPush(identity.userId);
    showToast(`Signed in as ${identity.userId}`);
  }, [showToast]);

  const logout = useCallback(async () => {
    if (userId) await unregisterDeviceForPush(userId);
    setBearerToken(null);
    await authTokens.clear();
    setUserId(null);
    setConversations([]);
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
    setSettings((current) => {
      const next = { ...current, ...patch };
      if (patch.apiBaseUrl) api.setBaseUrl(patch.apiBaseUrl);
      void appStorage.setSettings(next);
      return next;
    });
  }, []);

  const value = useMemo<AppContextValue>(
    () => ({
      users, userId, conversations, settings, booted, connected, authEnabled, toast,
      selectUser, loginWithToken, logout, refreshConversations, createConversation,
      deleteConversation, startRun, updateSettings, showToast,
    }),
    [
      users, userId, conversations, settings, booted, connected, authEnabled, toast, selectUser,
      loginWithToken, logout, refreshConversations, createConversation, deleteConversation,
      startRun, updateSettings, showToast,
    ],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const context = useContext(AppContext);
  if (!context) throw new Error('useApp must be used inside AppProvider');
  return context;
}
