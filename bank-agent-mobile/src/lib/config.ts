/**
 * API base URL resolution.
 *
 * Priority (highest first):
 *   1. EXPO_PUBLIC_API_BASE_URL environment variable (build-time, e.g. an ngrok URL)
 *   2. app.json `extra.apiBaseUrl` (device/simulator LAN default)
 *   3. http://localhost:8095 (iOS simulator / web fallback)
 */
declare const require: (moduleId: string) => any;

export const DEFAULT_API_BASE_URL = 'http://localhost:8095';

export interface ResolvedConfig {
  baseUrl: string;
  isHttps: boolean;
  /** True when the URL points at loopback (unreachable from a physical device). */
  isLoopback: boolean;
}

export function normalizeBaseUrl(input: string | null | undefined): string {
  if (!input || !input.trim()) return DEFAULT_API_BASE_URL;
  const trimmed = input.trim().replace(/\/+$/, '');
  return trimmed || DEFAULT_API_BASE_URL;
}

export function isLoopbackUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname;
    return host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch {
    return false;
  }
}

export function resolveConfig(
  envValue?: string | null,
  extraValue?: string | null,
  platformDefault: string = DEFAULT_API_BASE_URL,
): ResolvedConfig {
  const baseUrl = normalizeBaseUrl(envValue || extraValue || platformDefault);
  const isHttps = /^https:\/\//i.test(baseUrl);
  return { baseUrl, isHttps, isLoopback: isLoopbackUrl(baseUrl) };
}

/** Convenience for callers that only need the URL string. */
export function getApiBaseUrl(): string {
  const fromEnv =
    typeof process !== 'undefined' ? process.env?.EXPO_PUBLIC_API_BASE_URL : undefined;
  // Resolved lazily so pure unit tests can run in Node.
  let fromExtra: string | undefined;
  let platformDefault = DEFAULT_API_BASE_URL;
  try {
    const Constants = require('expo-constants').default;
    fromExtra = Constants?.expoConfig?.extra?.apiBaseUrl;
  } catch {
    fromExtra = undefined;
  }
  try {
    const { Platform } = require('react-native');
    if (Platform.OS === 'android') platformDefault = 'http://10.0.2.2:8095';
  } catch {
    // Node / test environment: keep the localhost default.
  }
  return resolveConfig(fromEnv, fromExtra, platformDefault).baseUrl;
}

export function buildUrl(baseUrl: string, path: string, params?: Record<string, string | undefined | null>): string {
  const url = normalizeBaseUrl(baseUrl) + (path.startsWith('/') ? path : `/${path}`);
  if (!params) return url;
  const query = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return query ? `${url}?${query}` : url;
}
