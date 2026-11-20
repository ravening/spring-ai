/* Test-only shims for React Native / Expo APIs that mocks may touch. */
global.__DEV__ = true;

jest.mock('@react-native-async-storage/async-storage', () => {
  const data = new Map();
  return {
    __esModule: true,
    default: {
      getItem: async (key) => data.get(key) ?? null,
      setItem: async (key, value) => { data.set(key, value); },
      removeItem: async (key) => { data.delete(key); },
      getAllKeys: async () => [...data.keys()],
      multiRemove: async (keys) => { keys.forEach((key) => data.delete(key)); },
    },
  };
});

jest.mock('expo-document-picker', () => ({ getDocumentAsync: async () => ({ canceled: true }) }));
