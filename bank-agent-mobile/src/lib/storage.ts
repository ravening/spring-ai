import AsyncStorage from '@react-native-async-storage/async-storage';
import { getApiBaseUrl } from './config';
import type { ConversationDetail, ConversationSummary } from './types';

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
  apiBaseUrl: string;
}

export const DEFAULT_SETTINGS: AppSettings = {
  autoSpeak: false,
  preferredVoiceMode: 'auto',
  streamingEnabled: true,
  biometricLock: false,
  apiBaseUrl: getApiBaseUrl(),
};

const KEYS = {
  selectedUser: 'bank:selectedUser',
  settings: 'bank:settings',
  conversations: (userId: string) => `bank:conversations:${userId}`,
  detail: (userId: string, id: string) => `bank:conversation:${userId}:${id}`,
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
