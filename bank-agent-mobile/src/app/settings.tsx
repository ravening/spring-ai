import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { Alert, ScrollView, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { router } from 'expo-router';
import { useApp } from '@/state/AppContext';
import { appStorage } from '@/lib/storage';
import { isOnDeviceSttAvailable } from '@/lib/speech';
import type { AppSettings } from '@/lib/storage';

function SettingRow({
  label, description, value, onValueChange,
}: { label: string; description?: string; value: boolean; onValueChange: (value: boolean) => void }) {
  return (
    <View style={styles.row}>
      <View style={styles.rowText}>
        <Text style={styles.label}>{label}</Text>
        {description && <Text style={styles.description}>{description}</Text>}
      </View>
      <Switch value={value} onValueChange={onValueChange} accessibilityLabel={label} />
    </View>
  );
}

export default function SettingsScreen() {
  const { settings, updateSettings, showToast, userId, authEnabled, logout } = useApp();
  const [pushEnabled, setPushEnabled] = useState(false);
  useEffect(() => {
    void api.pushStatus().then((status) => setPushEnabled(status.enabled)).catch(() => undefined);
  }, []);
  const [url, setUrl] = useState(settings.apiBaseUrl);
  const onDevice = isOnDeviceSttAvailable();

  const patch = (change: Partial<AppSettings>) => void updateSettings(change);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.section}>Backend</Text>
      <Text style={styles.hint}>
        Use your Mac's LAN IP (same Wi-Fi) or an HTTPS tunnel URL. `localhost` only works in the iOS simulator.
      </Text>
      <TextInput
        style={styles.input}
        value={url}
        onChangeText={setUrl}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel="Backend base URL"
        placeholder="http://192.168.1.10:8095"
      />
      <TouchableOpacity style={styles.button} onPress={() => { patch({ apiBaseUrl: url }); showToast('Backend URL saved'); }}>
        <Text style={styles.buttonText}>Save URL</Text>
      </TouchableOpacity>

      <Text style={styles.section}>Voice</Text>
      <SettingRow
        label="Auto speak answers"
        description="Read each answer aloud when it finishes"
        value={settings.autoSpeak}
        onValueChange={(value) => patch({ autoSpeak: value })}
      />
      <SettingRow
        label="Stream answers"
        description="Show tokens as the server writes them (falls back to polling)"
        value={settings.streamingEnabled}
        onValueChange={(value) => patch({ streamingEnabled: value })}
      />
      <Text style={styles.hint}>Speech mode: {settings.preferredVoiceMode}</Text>
      <View style={styles.modeRow}>
        {(['auto', 'on-device', 'server'] as const).map((mode) => (
          <TouchableOpacity
            key={mode}
            style={[styles.modeButton, settings.preferredVoiceMode === mode && styles.modeActive]}
            onPress={() => patch({ preferredVoiceMode: mode })}
          >
            <Text style={styles.modeText}>{mode}</Text>
          </TouchableOpacity>
        ))}
      </View>
      <Text style={styles.hint}>
        On-device recognition: {onDevice ? 'available' : 'unavailable in this build – server fallback used'}
      </Text>

      <Text style={styles.section}>Notifications</Text>
      <SettingRow
        label="Notifications"
        description="Poll for new job results and incoming transfers, show the unread badge and popups, and register this device for push"
        value={settings.notificationsEnabled}
        onValueChange={(value) => patch({ notificationsEnabled: value })}
      />
      <Text style={styles.hint}>Remote push on server: {pushEnabled ? 'enabled' : 'disabled'}</Text>

      <Text style={styles.section}>Privacy</Text>
      <SettingRow
        label="Biometric app lock"
        description="Require Face ID / fingerprint when returning to the app"
        value={settings.biometricLock}
        onValueChange={(value) => patch({ biometricLock: value })}
      />

      <Text style={styles.section}>Account</Text>
      <Text style={styles.hint}>Identity: {authEnabled ? 'token (verified by server)' : 'demo picker'}</Text>
      <Text style={styles.hint}>Signed in as {userId ?? 'no customer'}</Text>
      <Text style={styles.hint}>Remote push: {pushEnabled ? 'enabled on server' : 'disabled on server'}</Text>
      {authEnabled && (
        <TouchableOpacity style={styles.linkButton} onPress={() => void logout()}>
          <Text style={styles.link}>Sign out</Text>
        </TouchableOpacity>
      )}
      {!authEnabled && (
        <TouchableOpacity onPress={() => router.push('/login')} style={styles.linkButton}>
          <Text style={styles.link}>Switch customer</Text>
        </TouchableOpacity>
      )}

      <Text style={styles.section}>Data</Text>
      <TouchableOpacity
        style={styles.linkButton}
        onPress={() => {
          Alert.alert('Clear offline cache?', 'Server conversations stay intact.', [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Clear', style: 'destructive',
              onPress: async () => {
                await appStorage.clearAll();
                showToast('Local cache cleared');
              },
            },
          ]);
        }}
      >
        <Text style={styles.link}>Clear offline cache</Text>
      </TouchableOpacity>

      <Text style={styles.note}>Demo identity only. Add real authentication before public release.</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F6FAF8' },
  content: { padding: 18 },
  section: { fontSize: 18, fontWeight: '700', color: '#10251B', marginTop: 20, marginBottom: 8 },
  hint: { fontSize: 13, color: '#60756A', marginBottom: 8 },
  input: { borderWidth: 1, borderColor: '#CBDAD2', borderRadius: 8, padding: 10, backgroundColor: '#FFFFFF', fontSize: 14 },
  button: { backgroundColor: '#0B6E4F', borderRadius: 8, padding: 12, alignItems: 'center', marginTop: 8 },
  buttonText: { color: '#FFFFFF', fontWeight: '700' },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: 1, borderColor: '#E1EAE5' },
  rowText: { flex: 1, paddingRight: 12 },
  label: { fontSize: 15, color: '#10251B' },
  description: { fontSize: 12, color: '#60756A', marginTop: 3 },
  modeRow: { flexDirection: 'row', gap: 8 },
  modeButton: { flex: 1, padding: 10, borderRadius: 8, borderWidth: 1, borderColor: '#CBDAD2', alignItems: 'center' },
  modeActive: { backgroundColor: '#E8F3ED', borderColor: '#0B6E4F' },
  modeText: { color: '#10251B', fontSize: 13 },
  linkButton: { paddingVertical: 12 },
  link: { color: '#0B6E4F', fontSize: 15 },
  note: { color: '#8A9C92', fontSize: 12, marginTop: 24, textAlign: 'center' },
});
