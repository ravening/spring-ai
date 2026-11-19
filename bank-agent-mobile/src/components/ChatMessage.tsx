import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { MarkdownView } from './MarkdownView';
import type { ChatItem } from '@/lib/types';

interface Props {
  item: ChatItem;
  showTrace?: boolean;
  onSpeak?: (text: string) => void;
  speaking?: boolean;
}

function formatTime(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function ChatMessage({ item, showTrace, onSpeak, speaking }: Props) {
  const isUser = item.role === 'user';
  return (
    <View style={[styles.row, isUser ? styles.rowUser : styles.rowAssistant]}>
      <View style={[styles.bubble, isUser ? styles.bubbleUser : styles.bubbleAssistant]}>
        {item.role === 'assistant' ? (
          <MarkdownView content={item.content || (item.pending ? 'Working…' : '')} />
        ) : (
          <Text style={[styles.plain, isUser && styles.plainUser]}>{item.content}</Text>
        )}
        {item.pending && (
          <View style={styles.pendingRow}>
            <ActivityIndicator size="small" color="#0B6E4F" />
            <Text style={styles.pendingText}>thinking…</Text>
          </View>
        )}
        {item.error && <Text style={styles.errorText}>Could not complete this request.</Text>}
        <View style={styles.metaRow}>
          <Text style={styles.time}>{formatTime(item.createdAt)}</Text>
          {showTrace && item.runId && <Text style={styles.runId}>run {item.runId.slice(0, 8)}</Text>}
          {!isUser && !item.pending && Boolean(item.content) && onSpeak && (
            <TouchableOpacity accessibilityLabel="Read answer aloud" onPress={() => onSpeak(item.content)}>
              <Text style={styles.speak}>{speaking ? '■ Stop' : '▶ Listen'}</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { paddingHorizontal: 12, marginVertical: 5, flexDirection: 'row' },
  rowUser: { justifyContent: 'flex-end' },
  rowAssistant: { justifyContent: 'flex-start' },
  bubble: { maxWidth: '88%', borderRadius: 14, padding: 10 },
  bubbleUser: { backgroundColor: '#0B6E4F', borderBottomRightRadius: 4 },
  bubbleAssistant: { backgroundColor: '#FFFFFF', borderBottomLeftRadius: 4, borderWidth: 1, borderColor: '#E1EAE5' },
  plain: { fontSize: 15, lineHeight: 21, color: '#1D2B24' },
  plainUser: { color: '#FFFFFF' },
  pendingRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 6 },
  pendingText: { color: '#4B6357', fontSize: 13 },
  errorText: { color: '#B3261E', fontSize: 13, marginTop: 6 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 6 },
  time: { fontSize: 11, color: '#72857B' },
  runId: { fontSize: 11, color: '#72857B' },
  speak: { fontSize: 12, color: '#0B6E4F', fontWeight: '600' },
});
