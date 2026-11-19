import { AppStorage, DEFAULT_SETTINGS, type KeyValueStore } from '../src/lib/storage';

function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return {
    getItem: async (key) => data.get(key) ?? null,
    setItem: async (key, value) => { data.set(key, value); },
    removeItem: async (key) => { data.delete(key); },
    getAllKeys: async () => [...data.keys()],
    multiRemove: async (keys) => { keys.forEach((key) => data.delete(key)); },
  };
}

describe('AppStorage', () => {
  it('stores and reloads settings with defaults merged', async () => {
    const store = memoryStore();
    const storage = new AppStorage(store);
    await storage.setSettings({ ...DEFAULT_SETTINGS, autoSpeak: true });
    const loaded = await storage.getSettings();
    expect(loaded.autoSpeak).toBe(true);
    expect(loaded.streamingEnabled).toBe(DEFAULT_SETTINGS.streamingEnabled);
  });

  it('caches conversations per user', async () => {
    const store = memoryStore();
    const storage = new AppStorage(store);
    const conversations = [{ id: 'c1', userId: 'alice', title: 'Hi', updatedAt: null, messageCount: 1, active: false, activeRunId: null }];
    await storage.setConversations('alice', conversations);
    expect(await storage.getConversations('alice')).toEqual(conversations);
    expect(await storage.getConversations('bob')).toBeNull();
  });

  it('ignores corrupted cache entries', async () => {
    const store = memoryStore();
    await store.setItem('bank:settings', '{not json');
    const storage = new AppStorage(store);
    expect(await storage.getSettings()).toEqual(DEFAULT_SETTINGS);
  });

  it('clears all keys', async () => {
    const store = memoryStore();
    const storage = new AppStorage(store);
    await storage.setSelectedUser('alice');
    await storage.clearAll();
    expect(await storage.getSelectedUser()).toBeNull();
  });
});
