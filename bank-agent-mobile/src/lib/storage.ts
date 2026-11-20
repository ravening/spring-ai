import AsyncStorage from '@react-native-async-storage/async-storage';
import { getApiBaseUrl } from './config';
import type { ConversationDetail, ConversationSummary, NotificationDto } from './types';

export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  getAllKeys?: () => Promise<string[]>;
  multiRemove?: (keys: string[]) => Promise<void>;
}

export const asyncStore: KeyValueStore = {
  getItem: (key) => AsyncStorage.getItem(key),
  setItem: (key, value) => AsyncStorage.setItem(key, value),
  removeItem: (key) => AsyncStorage.removeItem(key),
  getAllKeys: async () => [...(await AsyncStorage.getAllKeys())],
  multiRemove: (keys) => AsyncStorage.multiRemove(keys),
};

export interface AppSettings {
  autoSpeak: boolean;
  preferredVoiceMode: 'auto' | 'on-device' | 'server';
  streamingEnabled: boolean;
  biometricLock: boolean;
  notificationsEnabled: boolean;
  apiBaseUrl: string;
}

export const DEFAULT_SETTINGS: AppSettings = {
  autoSpeak: false,
  preferredVoiceMode: 'auto',
  streamingEnabled: true,
  biometricLock: false,
  notificationsEnabled: true,
  apiBaseUrl: getApiBaseUrl(),
};

const KEYS = {
  selectedUser: 'bank:selectedUser',
  settings: 'bank:settings',
  conversations: (userId: string) => `bank:conversations:${userId}`,
  detail: (userId: string, id: string) => `bank:conversation:${userId}:${id}`,
  notificationCursor: (userId: string) => `bank:notification-cursor:${userId}`,
  notifications: (userId: string) => `bank:notifications:${userId}`,
  surfacedIds: (userId: string) => `bank:notification-surfaced:${userId}`,
};

async function readJson<T>(store: KeyValueStore, key: string): Promise<T | null> {
  const raw = await store.getItem(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    await store.removeItem(key).catch(() => undefined);
    return null;
  }
}

/** Offline cache + preferences. All methods degrade gracefully when storage fails. */
export class AppStorage {
  constructor(private readonly store: KeyValueStore = asyncStore) {}

  async getSelectedUser(): Promise<string | null> {
    return this.store.getItem(KEYS.selectedUser).catch(() => null);
  }

  async setSelectedUser(userId: string): Promise<void> {
    await this.store.setItem(KEYS.selectedUser, userId).catch(() => undefined);
  }

  async getSettings(): Promise<AppSettings> {
    const saved = await readJson<Partial<AppSettings>>(this.store, KEYS.settings);
    return { ...DEFAULT_SETTINGS, ...(saved ?? {}) };
  }

  async setSettings(settings: AppSettings): Promise<void> {
    await this.store.setItem(KEYS.settings, JSON.stringify(settings)).catch(() => undefined);
  }

  async getConversations(userId: string): Promise<ConversationSummary[] | null> {
    return readJson<ConversationSummary[]>(this.store, KEYS.conversations(userId));
  }

  async setConversations(userId: string, conversations: ConversationSummary[]): Promise<void> {
    await this.store
      .setItem(KEYS.conversations(userId), JSON.stringify(conversations))
      .catch(() => undefined);
  }

  async getConversation(userId: string, id: string): Promise<ConversationDetail | null> {
    return readJson<ConversationDetail>(this.store, KEYS.detail(userId, id));
  }

  async setConversation(userId: string, detail: ConversationDetail): Promise<void> {
    await this.store
      .setItem(KEYS.detail(userId, detail.id), JSON.stringify(detail))
      .catch(() => undefined);
  }

  async removeConversation(userId: string, id: string): Promise<void> {
    await this.store.removeItem(KEYS.detail(userId, id)).catch(() => undefined);
  }

  // ---- Notification inbox cache + polling cursor (per user) ----

  async getNotificationCursor(userId: string): Promise<number> {
    const raw = await this.store.getItem(KEYS.notificationCursor(userId)).catch(() => null);
    const value = Number(raw);
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  async setNotificationCursor(userId: string, cursor: number): Promise<void> {
    await this.store.setItem(KEYS.notificationCursor(userId), String(cursor)).catch(() => undefined);
  }

  async getNotifications(userId: string): Promise<NotificationDto[]> {
    return (await readJson<NotificationDto[]>(this.store, KEYS.notifications(userId))) ?? [];
  }

  async setNotifications(userId: string, notifications: NotificationDto[]): Promise<void> {
    await this.store.setItem(KEYS.notifications(userId), JSON.stringify(notifications)).catch(() => undefined);
  }

  /** Ids already shown via push or the in-app popup; the poller must not re-pop them. */
  async getSurfacedIds(userId: string): Promise<number[]> {
    return (await readJson<number[]>(this.store, KEYS.surfacedIds(userId))) ?? [];
  }

  async setSurfacedIds(userId: string, ids: number[]): Promise<void> {
    await this.store.setItem(KEYS.surfacedIds(userId), JSON.stringify([...new Set(ids)])).catch(() => undefined);
  }

  async clearNotificationCache(userId: string): Promise<void> {
    await Promise.all([
      this.store.removeItem(KEYS.notificationCursor(userId)).catch(() => undefined),
      this.store.removeItem(KEYS.notifications(userId)).catch(() => undefined),
      this.store.removeItem(KEYS.surfacedIds(userId)).catch(() => undefined),
    ]);
  }

  async clearAll(): Promise<void> {
    try {
      const keys = await this.store.getAllKeys?.();
      if (keys?.length) {
        if (this.store.multiRemove) await this.store.multiRemove(keys);
        else await Promise.all(keys.map((key) => this.store.removeItem(key)));
      }
    } catch {
      // Storage failures must never break the app.
    }
  }
}

export const appStorage = new AppStorage();
