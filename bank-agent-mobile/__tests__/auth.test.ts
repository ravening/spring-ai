import { authorizationHeader, getBearerToken, setBearerToken } from '../src/lib/auth';

jest.mock('expo-secure-store', () => {
  const data = new Map();
  return {
    getItemAsync: async (key: string) => data.get(key) ?? null,
    setItemAsync: async (key: string, value: string) => { data.set(key, value); },
    deleteItemAsync: async (key: string) => { data.delete(key); },
  };
});

describe('bearer token handling', () => {
  afterEach(() => setBearerToken(null));

  it('produces no header without a token', () => {
    expect(getBearerToken()).toBeNull();
    expect(authorizationHeader()).toEqual({});
  });

  it('produces a Bearer header after sign-in', () => {
    setBearerToken('  secret-token  ');
    expect(getBearerToken()).toBe('secret-token');
    expect(authorizationHeader()).toEqual({ Authorization: 'Bearer secret-token' });
  });

  it('clears the token on sign-out', () => {
    setBearerToken('abc');
    setBearerToken(null);
    expect(authorizationHeader()).toEqual({});
  });
});
