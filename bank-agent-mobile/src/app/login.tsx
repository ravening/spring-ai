import { useState } from 'react';
import {
  ActivityIndicator, FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View,
} from 'react-native';
import { router } from 'expo-router';
import { useApp } from '@/state/AppContext';
import type { BankUser } from '@/lib/types';

export default function LoginScreen() {
  const { users, selectUser, loginWithToken, connected, authEnabled, showToast } = useApp();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);

  const choose = async (user: BankUser) => {
    await selectUser(user.id);
    showToast(`signed in as ${user.displayName} (demo)`);
    router.replace('/conversations');
  };

  const signIn = async () => {
    if (!token.trim()) {
      showToast('Enter the bearer token');
      return;
    }
    setBusy(true);
    try {
      await loginWithToken(token.trim());
      router.replace('/conversations');
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Sign-in failed');
    } finally {
      setBusy(false);
    }
  };

  if (authEnabled) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Bank Agent</Text>
        <Text style={styles.subtitle}>Sign in with your access token</Text>
        <TextInput
          style={styles.input}
          value={token}
          onChangeText={setToken}
          placeholder="Bearer token"
          autoCapitalize="none"
          autoCorrect={false}
          secureTextEntry
          accessibilityLabel="Access token"
        />
        <TouchableOpacity style={styles.primary} disabled={busy} onPress={() => void signIn()}>
          {busy ? <ActivityIndicator color="#FFFFFF" /> : <Text style={styles.primaryText}>Sign in</Text>}
        </TouchableOpacity>
        <Text style={styles.note}>The token determines your identity; the customer picker is disabled.</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Bank Agent</Text>
      <Text style={styles.subtitle}>Choose a demo customer to continue</Text>
      {!connected && (
        <Text style={styles.warning}>Backend unreachable – cached data may be shown.</Text>
      )}
      <FlatList
        data={users}
        keyExtractor={(user) => user.id}
        renderItem={({ item }) => (
          <TouchableOpacity style={styles.card} onPress={() => void choose(item)}>
            <Text style={styles.avatar}>{item.displayName.charAt(0)}</Text>
            <View style={styles.cardBody}>
              <Text style={styles.name}>{item.displayName}</Text>
              <Text style={styles.detail}>{item.segment} · {item.homeBranch}</Text>
            </View>
            <Text style={styles.chevron}>›</Text>
          </TouchableOpacity>
        )}
        ListEmptyComponent={<Text style={styles.empty}>No customers available. Check the connection.</Text>}
      />
      <Text style={styles.note}>
        Demo mode: identity is a picker, not a real login. Do not use with real customer data.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, backgroundColor: '#F6FAF8' },
  title: { fontSize: 28, fontWeight: '800', color: '#10251B' },
  subtitle: { fontSize: 15, color: '#4B6357', marginTop: 6, marginBottom: 20 },
  warning: { color: '#B3261E', marginBottom: 12 },
  input: {
    borderWidth: 1, borderColor: '#CBDAD2', borderRadius: 8, padding: 12,
    backgroundColor: '#FFFFFF', fontSize: 15, marginBottom: 12,
  },
  primary: { backgroundColor: '#0B6E4F', borderRadius: 8, padding: 14, alignItems: 'center' },
  primaryText: { color: '#FFFFFF', fontWeight: '700', fontSize: 16 },
  card: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: '#FFFFFF', borderRadius: 12,
    padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1EAE5',
  },
  avatar: { fontSize: 22, fontWeight: '700', color: '#FFFFFF', backgroundColor: '#0B6E4F',
    width: 44, height: 44, borderRadius: 22, textAlign: 'center', textAlignVertical: 'center' },
  cardBody: { flex: 1, marginLeft: 14 },
  name: { fontSize: 16, fontWeight: '700', color: '#10251B' },
  detail: { fontSize: 13, color: '#60756A', marginTop: 3 },
  chevron: { fontSize: 24, color: '#8A9C92' },
  empty: { textAlign: 'center', color: '#60756A', marginTop: 30 },
  note: { fontSize: 12, color: '#8A9C92', marginTop: 16, textAlign: 'center' },
});
