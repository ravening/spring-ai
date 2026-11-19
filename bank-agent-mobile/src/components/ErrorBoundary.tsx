import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { logError } from '@/lib/crashlog';

interface State {
  error: Error | null;
}

/** Keeps a rendering bug from taking down the whole app; logs locally for later export. */
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error) {
    // Local-only crash log; no network dependency.
    // eslint-disable-next-line no-console
    console.error('[bank-agent-mobile] render error', error.message);
    void logError(error, 'render');
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.message}>{this.state.error.message}</Text>
        <TouchableOpacity style={styles.button} onPress={() => this.setState({ error: null })}>
          <Text style={styles.buttonText}>Try again</Text>
        </TouchableOpacity>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: '#F6FAF8' },
  title: { fontSize: 20, fontWeight: '700', color: '#10251B', marginBottom: 8 },
  message: { fontSize: 14, color: '#4B6357', textAlign: 'center' },
  button: { marginTop: 18, backgroundColor: '#0B6E4F', borderRadius: 8, paddingVertical: 10, paddingHorizontal: 20 },
  buttonText: { color: '#FFFFFF', fontWeight: '700' },
});
