import { useEffect } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { router } from 'expo-router';
import { useApp } from '@/state/AppContext';

export default function IndexScreen() {
  const { booted, userId } = useApp();

  useEffect(() => {
    if (!booted) return;
    router.replace(userId ? '/conversations' : '/login');
  }, [booted, userId]);

  return (
    <View style={styles.center}>
      <ActivityIndicator size="large" color="#0B6E4F" />
    </View>
  );
}

const styles = StyleSheet.create({ center: { flex: 1, alignItems: 'center', justifyContent: 'center' } });
