import * as SecureStore from 'expo-secure-store';

const TOKEN_KEY = 'bank:bearerToken';

/** Bearer token kept in the OS keystore (Keychain / Keystore), not AsyncStorage. */
export const authTokens = {
  async get(): Promise<string | null> {
    try {
      return await SecureStore.getItemAsync(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  async set(token: string): Promise<void> {
    try {
      await SecureStore.setItemAsync(TOKEN_KEY, token.trim());
    } catch {
      // Keystore unavailable – the session stays in memory via the API client override.
    }
  },
  async clear(): Promise<void> {
    try {
      await SecureStore.deleteItemAsync(TOKEN_KEY);
    } catch {
      // Ignore.
    }
  },
};

let inMemoryToken: string | null = null;

export function setBearerToken(token: string | null): void {
  inMemoryToken = token && token.trim() ? token.trim() : null;
}

export function getBearerToken(): string | null {
  return inMemoryToken;
}

export function authorizationHeader(): Record<string, string> {
  const token = getBearerToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Loads the persisted token into memory on app start. */
export async function restoreBearerToken(): Promise<string | null> {
  const token = await authTokens.get();
  setBearerToken(token);
  return token;
}
