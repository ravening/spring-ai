import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'bank:crashlog';
const MAX_ENTRIES = 50;

export interface CrashEntry {
  at: string;
  message: string;
  context?: string;
}

/** Local-only crash/error log. No external service or account is required. */
export async function logError(error: unknown, context?: string): Promise<void> {
  try {
    const entry: CrashEntry = {
      at: new Date().toISOString(),
      message: error instanceof Error ? error.message : String(error),
      context,
    };
    const raw = await AsyncStorage.getItem(KEY);
    const entries: CrashEntry[] = raw ? JSON.parse(raw) : [];
    entries.unshift(entry);
    await AsyncStorage.setItem(KEY, JSON.stringify(entries.slice(0, MAX_ENTRIES)));
  } catch {
    // Logging must never throw.
  }
}

export async function getCrashLog(): Promise<CrashEntry[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const entries = raw ? JSON.parse(raw) : [];
    return Array.isArray(entries) ? entries : [];
  } catch {
    return [];
  }
}

export async function clearCrashLog(): Promise<void> {
  try {
    await AsyncStorage.removeItem(KEY);
  } catch {
    // Ignore.
  }
}
