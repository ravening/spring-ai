import {
  buildUrl, DEFAULT_API_BASE_URL, isLoopbackUrl, normalizeBaseUrl, resolveConfig,
} from '../src/lib/config';

describe('config', () => {
  it('normalizes trailing slashes and falls back to the default', () => {
    expect(normalizeBaseUrl('http://192.168.1.5:8095///')).toBe('http://192.168.1.5:8095');
    expect(normalizeBaseUrl('  ')).toBe(DEFAULT_API_BASE_URL);
    expect(normalizeBaseUrl(null)).toBe(DEFAULT_API_BASE_URL);
  });

  it('detects loopback URLs', () => {
    expect(isLoopbackUrl('http://localhost:8095')).toBe(true);
    expect(isLoopbackUrl('http://127.0.0.1:8095')).toBe(true);
    expect(isLoopbackUrl('http://10.0.2.2:8095')).toBe(false);
    expect(isLoopbackUrl('not a url')).toBe(false);
  });

  it('prefers the environment value over app.json extra', () => {
    const config = resolveConfig('https://tunnel.example', 'http://10.0.2.2:8095');
    expect(config).toEqual({
      baseUrl: 'https://tunnel.example',
      isHttps: true,
      isLoopback: false,
    });
  });

  it('builds URLs with encoded query parameters and drops empty ones', () => {
    expect(buildUrl('http://x:8095/', '/api/conversations', {
      userId: 'alice', conversationId: undefined, q: 'a b&c',
    })).toBe('http://x:8095/api/conversations?userId=alice&q=a%20b%26c');
  });
});
