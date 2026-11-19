import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity } from 'react-native';
import { api } from '@/lib/api';
import type { ToolStatus, VoiceStatus } from '@/lib/types';

import { useApp } from '@/state/AppContext';
import { clearCrashLog, getCrashLog, type CrashEntry } from '@/lib/crashlog';

export default function DebugScreen() {
  const { connected, authEnabled } = useApp();
  const [tools, setTools] = useState<ToolStatus | null>(null);
  const [voice, setVoice] = useState<VoiceStatus | null>(null);
  const [pushEnabled, setPushEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [crashes, setCrashes] = useState<CrashEntry[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    const [toolsResult, voiceResult, pushResult] = await Promise.allSettled([
      api.listTools(), api.voiceStatus(), api.pushStatus(),
    ]);
    setTools(toolsResult.status === 'fulfilled' ? toolsResult.value : null);
    setVoice(voiceResult.status === 'fulfilled' ? voiceResult.value : null);
    setPushEnabled(pushResult.status === 'fulfilled' && pushResult.value.enabled);
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
    void getCrashLog().then(setCrashes);
  }, [load]);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.section}>Connection</Text>
      <Text style={styles.value}>{api.baseUrl}</Text>
      <Text style={[styles.status, connected ? styles.ok : styles.bad]}>
        {connected ? '● reachable' : '● unreachable – showing cached data'}
      </Text>

      {loading && <ActivityIndicator color="#0B6E4F" style={styles.loading} />}

      <Text style={styles.section}>Security</Text>
      <Text style={styles.value}>token auth: {authEnabled ? 'enabled' : 'disabled (demo mode)'}</Text>
      <Text style={styles.value}>remote push: {pushEnabled ? 'enabled' : 'disabled'}</Text>

      {voice && (
        <>
          <Text style={styles.section}>Voice</Text>
          <Text style={styles.value}>server STT: {voice.stt ? 'enabled' : 'disabled'}</Text>
          <Text style={styles.value}>server TTS: {voice.tts ? 'enabled' : 'disabled'}</Text>
        </>
      )}

      {tools && (
        <>
          <Text style={styles.section}>Local tools</Text>
          {tools.localTools.length === 0 && <Text style={styles.value}>none</Text>}
          {tools.localTools.map((tool) => <Text key={tool} style={styles.value}>• {tool}</Text>)}
          <Text style={styles.section}>MCP server</Text>
          <Text style={styles.value}>{JSON.stringify(tools.mcp)}</Text>
        </>
      )}

      {!loading && !tools && <Text style={styles.bad}>Could not load tool status.</Text>}
      <TouchableOpacity style={styles.button} onPress={() => void load()}>
        <Text style={styles.buttonText}>Refresh</Text>
      </TouchableOpacity>

      <Text style={styles.section}>Local error log</Text>
      {crashes.length === 0 && <Text style={styles.value}>No errors recorded.</Text>}
      {crashes.slice(0, 10).map((entry, index) => (
        <Text key={index} style={styles.value}>
          {entry.at} · {entry.context ?? 'app'}: {entry.message}
        </Text>
      ))}
      <TouchableOpacity
        style={styles.linkButton}
        onPress={() => void clearCrashLog().then(() => setCrashes([]))}
      >
        <Text style={styles.link}>Clear error log</Text>
      </TouchableOpacity>

      <Text style={styles.note}>
        {'Streaming is enabled in Settings. Polling GET /api/runs/{runId} is always active.'}
      </Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F6FAF8' },
  content: { padding: 18 },
  section: { fontSize: 18, fontWeight: '700', color: '#10251B', marginTop: 18, marginBottom: 8 },
  value: { fontSize: 14, color: '#40564B', marginBottom: 4 },
  status: { fontSize: 14, marginBottom: 8 },
  ok: { color: '#0B6E4F' },
  bad: { color: '#B3261E' },
  loading: { marginVertical: 16 },
  button: { backgroundColor: '#0B6E4F', borderRadius: 8, padding: 12, alignItems: 'center', marginTop: 20 },
  buttonText: { color: '#FFFFFF', fontWeight: '700' },
  linkButton: { paddingVertical: 12 },
  link: { color: '#0B6E4F', fontSize: 14 },
  note: { fontSize: 12, color: '#8A9C92', marginTop: 20 },
});
