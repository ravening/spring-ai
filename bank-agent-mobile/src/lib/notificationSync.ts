import type { NotificationDto } from './types';

/** Merge cached + freshly polled notifications, deduped by id and sorted by id asc. */
export function mergeNotifications(cache: NotificationDto[], incoming: NotificationDto[]): NotificationDto[] {
  const byId = new Map<number, NotificationDto>();
  for (const item of [...cache, ...incoming]) byId.set(item.id, item);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

/** Monotonic poll cursor: the highest id observed. */
export function nextCursor(cursor: number, items: NotificationDto[]): number {
  let next = cursor;
  for (const item of items) if (item.id > next) next = item.id;
  return next;
}

/**
 * The first newly-arrived item that has not already been surfaced by a push or a
 * previous popup. Items from before this poll (id <= previousCursor) never pop.
 */
export function popupCandidate(
  items: NotificationDto[],
  previousCursor: number,
  surfacedIds: ReadonlySet<number>,
): NotificationDto | null {
  return items.find((item) => item.id > previousCursor && !surfacedIds.has(item.id)) ?? null;
}

/** Marks one notification read locally; returns the updated list. */
export function applyRead(items: NotificationDto[], id: number): NotificationDto[] {
  return items.map((item) => (item.id === id ? { ...item, read: true } : item));
}

/** Marks every notification read locally. */
export function applyReadAll(items: NotificationDto[]): NotificationDto[] {
  return items.map((item) => ({ ...item, read: true }));
}

/** Extrapolates a retry delay with exponential backoff, capped at 60s. */
export function nextBackoff(currentMs: number): number {
  return Math.min(currentMs * 2, 60_000);
}

export const POLL_INTERVAL_MS = 10_000;
