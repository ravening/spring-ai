import { BankApiClient, type FetchLike } from '../src/lib/api';
import { AppStorage, DEFAULT_SETTINGS, type KeyValueStore } from '../src/lib/storage';
import {
  applyRead, applyReadAll, mergeNotifications, nextBackoff, nextCursor, popupCandidate,
} from '../src/lib/notificationSync';
import type { NotificationDto } from '../src/lib/types';

jest.mock('expo-secure-store', () => ({
  getItemAsync: async () => null,
  setItemAsync: async () => undefined,
  deleteItemAsync: async () => undefined,
}));

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Call = { url: string; init?: RequestInit };

function makeClient(handler: (url: string, init?: RequestInit) => Response): { client: BankApiClient; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = async (url, init) => {
    calls.push({ url, init });
    return handler(url, init);
  };
  return { client: new BankApiClient('http://test:8095', fetchImpl), calls };
}

const item = (id: number, read = false): NotificationDto => ({
  id, userId: 'alice', conversationId: `conv-${id}`, type: 'job_result',
  title: 'Job completed', body: `body ${id}`, detail: '{"amount":"10.00"}',
  jobId: 'j1', read, createdAt: 1000 + id,
});

describe('notification inbox API', () => {
  it('polls with a sinceId cursor, unreadOnly and limit', async () => {
    const { client, calls } = makeClient(() => jsonResponse({ unreadCount: 2, items: [item(5), item(6)] }));
    const page = await client.listNotifications('alice', 4, 50, true);
    expect(page.unreadCount).toBe(2);
    expect(page.items.map((n) => n.id)).toEqual([5, 6]);
    expect(calls[0].url).toBe(
      'http://test:8095/api/notifications?userId=alice&sinceId=4&limit=50&unreadOnly=true',
    );
  });

  it('reads unread counts and marks one/all read with POST', async () => {
    const { client, calls } = makeClient((url) => {
      if (url.includes('unread-count')) return jsonResponse({ count: 3 });
      if (url.includes('read-all')) return jsonResponse({ updated: 3 });
      return jsonResponse({ id: 7, read: true });
    });
    await expect(client.unreadCount('alice')).resolves.toEqual({ count: 3 });
    await expect(client.markNotificationRead(7, 'alice')).resolves.toEqual({ id: 7, read: true });
    expect(calls[1].url).toBe('http://test:8095/api/notifications/7/read?userId=alice');
    expect(calls[1].init?.method).toBe('POST');
    await expect(client.markAllNotificationsRead('alice')).resolves.toEqual({ updated: 3 });
    expect(calls[2].url).toBe('http://test:8095/api/notifications/read-all?userId=alice');
  });

  it('deletes a notification with DELETE', async () => {
    const { client, calls } = makeClient(() => new Response(null, { status: 204 }));
    await expect(client.deleteNotification(7, 'alice')).resolves.toBeUndefined();
    expect(calls[0].init?.method).toBe('DELETE');
    expect(calls[0].url).toBe('http://test:8095/api/notifications/7?userId=alice');
  });

  it('exposes accounts, transfers and job CRUD including PATCH', async () => {
    const { client, calls } = makeClient((url, init) => {
      if (url.includes('/accounts')) return jsonResponse([{ accountId: 'ACC-ALICE-CHECK', balance: '100.00' }]);
      if (url.includes('/transfers') && init?.method === 'POST') {
        return jsonResponse({ txnId: 'TXN-1', recipientNewBalance: '110.00' }, 201);
      }
      if (url.includes('/jobs') && init?.method === 'PATCH') return jsonResponse({ id: 'j1', status: 'PAUSED' });
      if (url.includes('/run')) return jsonResponse({ id: 'j1', status: 'DONE' });
      return jsonResponse({ id: 'j1', status: 'PENDING' });
    });
    await expect(client.listAccounts('alice')).resolves.toEqual([{ accountId: 'ACC-ALICE-CHECK', balance: '100.00' }]);
    await expect(client.createTransfer('alice', {
      fromAccountId: 'ACC-ALICE-CHECK', toUserId: 'carol', toAccountId: 'ACC-CAROL-CHECK', amount: '10',
    })).resolves.toMatchObject({ txnId: 'TXN-1' });
    expect(JSON.parse(String(calls[1].init?.body))).toMatchObject({ userId: 'alice', amount: '10' });
    await expect(client.patchJob('j1', 'alice', { status: 'PAUSED' })).resolves.toMatchObject({ status: 'PAUSED' });
    expect(calls[2].init?.method).toBe('PATCH');
    await expect(client.runJobNow('j1', 'alice')).resolves.toMatchObject({ status: 'DONE' });
  });
});

describe('notification sync helpers', () => {
  it('merges by id, dedupes and sorts ascending', () => {
    const merged = mergeNotifications([item(2), item(5)], [item(5, true), item(3)]);
    expect(merged.map((n) => n.id)).toEqual([2, 3, 5]);
    expect(merged[2].read).toBe(true);
  });

  it('advances the cursor only to the highest id seen', () => {
    expect(nextCursor(4, [item(3), item(6), item(5)])).toBe(6);
    expect(nextCursor(10, [item(6)])).toBe(10);
  });

  it('skips already-surfaced ids and items older than the cursor', () => {
    const surfaced = new Set([6]);
    expect(popupCandidate([item(5), item(6), item(7)], 4, surfaced)?.id).toBe(5);
    expect(popupCandidate([item(6), item(7)], 4, surfaced)?.id).toBe(7);
    expect(popupCandidate([item(1), item(2)], 4, new Set())).toBeNull();
    expect(popupCandidate([], 0, new Set())).toBeNull();
  });

  it('applies read transitions locally', () => {
    const list = [item(1), item(2)];
    expect(applyRead(list, 1)).toEqual([item(1, true), item(2)]);
    expect(applyReadAll(list).every((n) => n.read)).toBe(true);
    expect(list[0].read).toBe(false); // immutable
  });

  it('backs off exponentially with a cap', () => {
    expect(nextBackoff(10_000)).toBe(20_000);
    expect(nextBackoff(40_000)).toBe(60_000);
  });
});

describe('notification storage', () => {
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

  it('defaults the global notification toggle to on and persists it', async () => {
    const storage = new AppStorage(memoryStore());
    expect((await storage.getSettings()).notificationsEnabled).toBe(true);
    await storage.setSettings({ ...DEFAULT_SETTINGS, notificationsEnabled: false });
    expect((await storage.getSettings()).notificationsEnabled).toBe(false);
  });

  it('persists per-user cursor, cache and surfaced ids independently', async () => {
    const storage = new AppStorage(memoryStore());
    await storage.setNotificationCursor('alice', 12);
    await storage.setNotifications('alice', [item(12)]);
    await storage.setSurfacedIds('alice', [11, 12, 12]);
    expect(await storage.getNotificationCursor('alice')).toBe(12);
    expect(await storage.getNotificationCursor('bob')).toBe(0);
    expect((await storage.getNotifications('alice')).map((n) => n.id)).toEqual([12]);
    expect(await storage.getSurfacedIds('alice')).toEqual([11, 12]);
    await storage.clearNotificationCache('alice');
    expect(await storage.getNotificationCursor('alice')).toBe(0);
    expect(await storage.getNotifications('alice')).toEqual([]);
    expect(await storage.getSurfacedIds('alice')).toEqual([]);
  });
});
