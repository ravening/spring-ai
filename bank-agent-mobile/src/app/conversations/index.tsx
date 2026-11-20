import { useCallback } from 'react';
import {
  Alert, FlatList, RefreshControl, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { router } from 'expo-router';
import { useApp } from '@/state/AppContext';
import { truncate } from '@/lib/markdown';
import type { ConversationSummary } from '@/lib/types';

function formatDate(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export default function ConversationsScreen() {
  const {
    conversations, userId, users, connected, unreadCount, settings,
    refreshConversations, createConversation, deleteConversation, showToast,
  } = useApp();

  const openNew = useCallback(async () => {
    try {
      const created = await createConversation();
      router.push(`/conversations/${created.id}`);
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not create conversation');
    }
  }, [createConversation, showToast]);

  const remove = useCallback((conversation: ConversationSummary) => {
    Alert.alert(
      'Delete conversation?',
      'This removes the chat and its uploaded documents from the server.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete', style: 'destructive',
          onPress: async () => {
            try {
              await deleteConversation(conversation.id);
              showToast('Conversation deleted');
            } catch (error) {
              showToast(error instanceof Error ? error.message : 'Could not delete conversation');
            }
          },
        },
      ],
    );
  }, [deleteConversation, showToast]);

  const currentUser = users.find((user) => user.id === userId);

  return (
    <View style={styles.container}>
      <View style={styles.toolbar}>
        <TouchableOpacity onPress={() => router.push('/login')} accessibilityLabel="Switch customer">
          <Text style={styles.userChip}>{currentUser?.displayName ?? userId ?? 'No customer'} ▾</Text>
        </TouchableOpacity>
        <View style={styles.toolbarButtons}>
          {settings.notificationsEnabled && (
            <TouchableOpacity
              onPress={() => router.push('/notifications')}
              accessibilityLabel={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
            >
              <View style={styles.bellWrap}>
                <Text style={styles.toolButton}>🔔</Text>
                {unreadCount > 0 && (
                  <View style={styles.badge}>
                    <Text style={styles.badgeText}>{unreadCount > 99 ? '99+' : unreadCount}</Text>
                  </View>
                )}
              </View>
            </TouchableOpacity>
          )}
          <TouchableOpacity onPress={() => router.push('/debug')} accessibilityLabel="Connection and tools">
            <Text style={styles.toolButton}>⚙︎ Tools</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => router.push('/settings')} accessibilityLabel="Settings">
            <Text style={styles.toolButton}>Settings</Text>
          </TouchableOpacity>
        </View>
      </View>

      {!connected && <Text style={styles.offline}>Offline – showing cached conversations</Text>}

      <TouchableOpacity style={styles.newButton} onPress={() => void openNew()}>
        <Text style={styles.newButtonText}>＋ New conversation</Text>
      </TouchableOpacity>

      <FlatList
        data={conversations}
        keyExtractor={(item) => item.id}
        refreshControl={<RefreshControl refreshing={false} onRefresh={() => void refreshConversations()} />}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <TouchableOpacity style={styles.rowMain} onPress={() => router.push(`/conversations/${item.id}`)}>
              <Text style={styles.title} numberOfLines={1}>
                {truncate(item.title || 'Untitled conversation', 60)}
              </Text>
              <Text style={styles.meta}>
                {item.messageCount} messages{item.updatedAt ? ` · ${formatDate(item.updatedAt)}` : ''}
              </Text>
              {item.active && <Text style={styles.active}>⏳ working…</Text>}
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.delete}
              onPress={() => remove(item)}
              accessibilityLabel={`Delete ${item.title}`}
            >
              <Text style={styles.deleteText}>🗑</Text>
            </TouchableOpacity>
          </View>
        )}
        ListEmptyComponent={
          <Text style={styles.empty}>No conversations yet. Tap “New conversation” to start.</Text>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingHorizontal: 14, paddingTop: 10, backgroundColor: '#F6FAF8' },
  toolbar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  userChip: { fontSize: 15, fontWeight: '700', color: '#0B6E4F' },
  toolbarButtons: { flexDirection: 'row', gap: 12, alignItems: 'center' },
  toolButton: { fontSize: 13, color: '#0B6E4F' },
  bellWrap: { position: 'relative', padding: 2 },
  badge: {
    position: 'absolute', top: -6, right: -8, minWidth: 18, height: 18, borderRadius: 9,
    backgroundColor: '#B3261E', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 3,
  },
  badgeText: { color: '#FFFFFF', fontSize: 10, fontWeight: '700' },
  offline: { color: '#B3261E', fontSize: 13, marginBottom: 8 },
  newButton: { backgroundColor: '#0B6E4F', borderRadius: 10, paddingVertical: 12, alignItems: 'center', marginBottom: 12 },
  newButtonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 15 },
  row: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: '#FFFFFF', borderRadius: 10,
    padding: 12, marginBottom: 8, borderWidth: 1, borderColor: '#E1EAE5',
  },
  rowMain: { flex: 1 },
  title: { fontSize: 16, fontWeight: '700', color: '#10251B' },
  meta: { fontSize: 12, color: '#60756A', marginTop: 4 },
  active: { fontSize: 12, color: '#B26A00', marginTop: 4 },
  delete: { padding: 8 },
  deleteText: { fontSize: 18 },
  empty: { textAlign: 'center', color: '#60756A', marginTop: 40 },
});
