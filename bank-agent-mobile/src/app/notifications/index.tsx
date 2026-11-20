import { useCallback, useMemo } from 'react';
import {
  FlatList, RefreshControl, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { useApp } from '@/state/AppContext';
import type { NotificationDto } from '@/lib/types';

/** Best-effort parse of the rich detail JSON; the authoritative copy stays on the server. */
function detailLines(detail: string | null): string | null {
  if (!detail) return null;
  try {
    const value = JSON.parse(detail) as Record<string, unknown>;
    const parts: string[] = [];
    if (typeof value.amount === 'string' && typeof value.currency === 'string') {
      parts.push(`${value.amount} ${value.currency}`);
    }
    if (typeof value.from === 'string') parts.push(`from ${value.from}`);
    if (typeof value.oldBalance === 'string' && typeof value.newBalance === 'string') {
      parts.push(`${value.oldBalance} → ${value.newBalance}`);
    }
    if (typeof value.txnId === 'string') parts.push(value.txnId);
    if (typeof value.text === 'string') parts.push(String(value.text).slice(0, 120));
    return parts.length ? parts.join(' · ') : null;
  } catch {
    return null;
  }
}

function timeLabel(epochMs: number): string {
  const date = new Date(epochMs);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export default function NotificationsScreen() {
  const {
    notifications, unreadCount, settings, refreshNotifications, markRead, markAllRead,
  } = useApp();

  useFocusEffect(
    useCallback(() => {
      void refreshNotifications();
    }, [refreshNotifications]),
  );

  const items = useMemo(
    () => [...notifications].sort((a, b) => b.id - a.id),
    [notifications],
  );

  const open = useCallback((item: NotificationDto) => {
    void markRead(item.id);
    router.navigate(`/conversations/${item.conversationId}` as never);
  }, [markRead]);

  if (!settings.notificationsEnabled) {
    return (
      <View style={styles.disabledContainer}>
        <Text style={styles.disabledText}>
          Notifications are turned off. Enable them in Settings to receive updates here.
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerText}>
          {unreadCount > 0 ? `${unreadCount} unread` : 'All caught up'}
        </Text>
        {unreadCount > 0 && (
          <TouchableOpacity onPress={() => void markAllRead()} accessibilityLabel="Mark all read">
            <Text style={styles.markAll}>Mark all read</Text>
          </TouchableOpacity>
        )}
      </View>
      <FlatList
        data={items}
        keyExtractor={(item) => String(item.id)}
        refreshControl={<RefreshControl refreshing={false} onRefresh={() => void refreshNotifications()} />}
        renderItem={({ item }) => {
          const detail = detailLines(item.detail);
          return (
            <TouchableOpacity
              style={[styles.row, item.read ? styles.rowRead : styles.rowUnread]}
              onPress={() => open(item)}
              accessibilityLabel={`${item.title}: ${item.body}`}
            >
              <View style={styles.rowTop}>
                {!item.read && <View style={styles.dot} />}
                <Text style={[styles.title, item.read && styles.dim]}>{item.title}</Text>
                <Text style={styles.time}>{timeLabel(item.createdAt)}</Text>
              </View>
              <Text style={[styles.body, item.read && styles.dim]}>{item.body}</Text>
              {detail && <Text style={[styles.detail, item.read && styles.dim]}>{detail}</Text>}
            </TouchableOpacity>
          );
        }}
        ListEmptyComponent={
          <Text style={styles.empty}>No notifications yet. Transfer money or schedule a job to see updates here.</Text>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F6FAF8' },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderColor: '#E1EAE5',
  },
  headerText: { fontSize: 14, fontWeight: '700', color: '#10251B' },
  markAll: { color: '#0B6E4F', fontSize: 13 },
  row: {
    paddingHorizontal: 16, paddingVertical: 14, borderBottomWidth: 1,
    borderColor: '#E1EAE5', backgroundColor: '#FFFFFF',
  },
  rowUnread: { backgroundColor: '#F0F9F4' },
  rowRead: { backgroundColor: '#FFFFFF' },
  rowTop: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  dot: { width: 8, height: 8, borderRadius: 4, backgroundColor: '#0B6E4F' },
  title: { flex: 1, fontSize: 15, fontWeight: '700', color: '#10251B' },
  time: { fontSize: 11, color: '#8A9C92' },
  body: { fontSize: 13, color: '#40564B', marginTop: 5 },
  detail: { fontSize: 12, color: '#0B6E4F', marginTop: 6 },
  dim: { color: '#8A9C92' },
  empty: { textAlign: 'center', color: '#60756A', marginTop: 48, paddingHorizontal: 24 },
  disabledContainer: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32, backgroundColor: '#F6FAF8' },
  disabledText: { textAlign: 'center', color: '#60756A', fontSize: 15 },
});
