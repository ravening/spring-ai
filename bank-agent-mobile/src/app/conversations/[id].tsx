import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, FlatList, KeyboardAvoidingView, Modal, Platform,
  ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View,
} from 'react-native';
import { useIsFocused, useLocalSearchParams } from 'expo-router';
import * as Speech from 'expo-speech';
import { createAudioPlayer } from 'expo-audio';
import { api, ApiError } from '@/lib/api';
import { stripMarkdown, truncate } from '@/lib/markdown';
import {
  isOnDeviceSttAvailable, transcribeOnDevice, AudioCapture, isTtsAvailable,
} from '@/lib/speech';
import {
  pickDocument, pickImage, validateAttachment, type PickedAsset,
} from '@/lib/attachments';
import { appStorage } from '@/lib/storage';
import type {
  ChatItem, ConversationDetail, DocumentInfo, RunStatus, VoiceStatus,
} from '@/lib/types';
import { useApp } from '@/state/AppContext';
import { ChatMessage } from '@/components/ChatMessage';

const POLL_MS = 1200;

function toChatItems(detail: ConversationDetail): ChatItem[] {
  return detail.messages.map((message, index) => ({
    id: `${detail.id}-${index}`,
    role: message.role === 'user' ? 'user' : message.role === 'assistant' ? 'assistant' : 'system',
    content: message.content,
    createdAt: message.createdAt,
  }));
}

export default function ChatScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { userId, settings, startRun, refreshConversations, showToast, updateSettings } = useApp();
  const focused = useIsFocused();

  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState('');
  const [runId, setRunId] = useState<string | null>(null);
  const [status, setStatus] = useState<RunStatus | null>(null);
  const [documents, setDocuments] = useState<DocumentInfo[]>([]);
  const [voice, setVoice] = useState<VoiceStatus | null>(null);
  const [speaking, setSpeaking] = useState(false);
  const [serverAudioUrl, setServerAudioUrl] = useState<string | null>(null);
  const [sheet, setSheet] = useState<'attachments' | 'documents' | null>(null);
  const [scope, setScope] = useState<'chat' | 'user'>('chat');
  const [uploading, setUploading] = useState(false);
  const [listening, setListening] = useState(false);
  const [partial, setPartial] = useState('');
  const [loading, setLoading] = useState(true);
  const [traceVisible, setTraceVisible] = useState(false);

  const focusedRef = useRef(focused);
  const runIdRef = useRef<string | null>(null);
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const streamAbort = useRef<AbortController | null>(null);
  const audioCapture = useRef<AudioCapture | null>(null);
  const sendingRef = useRef(false);

  useEffect(() => { focusedRef.current = focused; }, [focused]);
  useEffect(() => { runIdRef.current = runId; }, [runId]);

  const loadConversation = useCallback(async () => {
    if (!id || !userId) return;
    setLoading(true);
    try {
      const detail = await api.getConversation(id, userId);
      setItems(toChatItems(detail));
      setDocuments(detail.documents);
      await appStorage.setConversation(userId, detail);
      if (detail.active && detail.activeRunId) {
        setRunId(detail.activeRunId);
        setItems((current) => [
          ...current,
          { id: `${detail.activeRunId}-assistant`, role: 'assistant', content: '', createdAt: null, pending: true, runId: detail.activeRunId! },
        ]);
      }
    } catch {
      const cached = await appStorage.getConversation(userId, id);
      if (cached) {
        setItems(toChatItems(cached));
        setDocuments(cached.documents);
      }
      showToast('Could not load this conversation from the server');
    } finally {
      setLoading(false);
    }
  }, [id, userId, showToast]);

  useEffect(() => {
    void loadConversation();
    void api.voiceStatus().then(setVoice).catch(() => setVoice({ stt: false, tts: false }));
    return () => {
      if (pollTimer.current) clearInterval(pollTimer.current);
      streamAbort.current?.abort();
      void Speech.stop();
    };
  }, [loadConversation]);

  const checkServerAudio = useCallback(async (nextRunId: string) => {
    try {
      const controller = new AbortController();
      const response = await api.authenticatedFetch(api.getRunAudioUrl(nextRunId), { signal: controller.signal });
      controller.abort();
      setServerAudioUrl(response.ok ? api.getRunAudioUrl(nextRunId) : null);
    } catch {
      setServerAudioUrl(null);
    }
  }, []);

  const finalizeRun = useCallback(async (finalStatus: RunStatus) => {
    setRunId(null);
    runIdRef.current = null;
    setItems((current) =>
      current.map((item) =>
        item.runId === finalStatus.runId
          ? {
              ...item,
              pending: false,
              error: finalStatus.status === 'FAILED',
              content: finalStatus.status === 'FAILED'
                ? finalStatus.error || 'The run failed.'
                : finalStatus.answer || item.content,
              createdAt: finalStatus.finishedAt ?? item.createdAt,
            }
          : item,
      ),
    );
    // The voice endpoint only returns a runId; the transcript lives on the run.
    if (finalStatus.message) {
      setItems((current) =>
        current.map((item) =>
          item.role === 'user' && !item.pending && item.content.startsWith('🎤')
            ? { ...item, content: finalStatus.message }
            : item,
        ),
      );
    }
    await refreshConversations();
    if (id && userId) {
      const fresh = await api.getConversation(id, userId).catch(() => null);
      if (fresh) await appStorage.setConversation(userId, fresh);
      setDocuments(fresh?.documents ?? []);
    }

    if (finalStatus.status === 'COMPLETED' && finalStatus.answer) {
      // answer_ready is emitted once by the server (inbox + chat message); no local
      // notification here, so push and polling cannot create duplicates.
      if (settings.autoSpeak && (await isTtsAvailable())) {
        await Speech.speak(stripMarkdown(finalStatus.answer), { language: 'en-US' });
      }
      await checkServerAudio(finalStatus.runId);
    }
  }, [refreshConversations, id, userId, settings.autoSpeak, checkServerAudio]);

  const startPolling = useCallback((nextRunId: string) => {
    if (pollTimer.current) clearInterval(pollTimer.current);
    const tick = async () => {
      try {
        const current = await api.getRunStatus(nextRunId);
        setStatus(current);
        if (current.status === 'RUNNING') return;
        if (pollTimer.current) clearInterval(pollTimer.current);
        pollTimer.current = null;
        await finalizeRun(current);
      } catch {
        if (pollTimer.current) clearInterval(pollTimer.current);
        pollTimer.current = null;
        setItems((list) =>
          list.map((item) => (item.runId === nextRunId ? { ...item, pending: false, error: true } : item)),
        );
      }
    };
    pollTimer.current = setInterval(() => void tick(), POLL_MS);
    void tick();
  }, [finalizeRun]);

  const startStreaming = useCallback((nextRunId: string) => {
    if (!settings.streamingEnabled) return;
    streamAbort.current?.abort();
    streamAbort.current = new AbortController();
    let draft = '';
    void api
      .streamRun(
        nextRunId,
        (event) => {
          if (event.draft) draft = '';
          if (event.type === 'done') return;
          draft += event.text;
          setItems((current) =>
            current.map((item) => (item.runId === nextRunId ? { ...item, content: draft } : item)),
          );
        },
        streamAbort.current.signal,
      )
      .catch(() => undefined);
  }, [settings.streamingEnabled]);

  const attachStartedRun = useCallback((startedRunId: string, assistantItemId: string) => {
    setRunId(startedRunId);
    runIdRef.current = startedRunId;
    setItems((current) =>
      current.map((item) =>
        item.id === assistantItemId ? { ...item, runId: startedRunId } : item,
      ),
    );
    startPolling(startedRunId);
    startStreaming(startedRunId);
  }, [startPolling, startStreaming]);

  const send = useCallback(async (text: string) => {
    const message = text.trim();
    if (!message || runIdRef.current || sendingRef.current || !userId) return;
    sendingRef.current = true;
    const userItem: ChatItem = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: message,
      createdAt: new Date().toISOString(),
    };
    const assistantItem: ChatItem = {
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: '',
      createdAt: null,
      pending: true,
    };
    setItems((current) => [...current, userItem, assistantItem]);
    setInput('');
    setStatus(null);
    setTraceVisible(false);

    try {
      const started = await startRun(message, id);
      attachStartedRun(started.runId, assistantItem.id);
    } catch (error) {
      setItems((current) =>
        current.map((item) =>
          item.id === assistantItem.id ? { ...item, pending: false, error: true } : item,
        ),
      );
      showToast(error instanceof ApiError ? error.message : 'Could not send the message');
    } finally {
      sendingRef.current = false;
    }
  }, [userId, startRun, id, attachStartedRun, showToast]);

  const startServerRecording = useCallback(async () => {
    const capture = audioCapture.current ?? new AudioCapture();
    audioCapture.current = capture;
    await capture.start();
    setListening(true);
  }, []);

  const submitVoiceRecording = useCallback(async () => {
    setListening(false);
    try {
      const audio = await (audioCapture.current ?? new AudioCapture()).stop();
      audioCapture.current = null;
      const started = await api.voiceChat(audio, userId!, id);
      const userItem: ChatItem = {
        id: `voice-${Date.now()}`,
        role: 'user', content: `🎤 ${started.message}`,
        createdAt: new Date().toISOString(),
      };
      const assistantItem: ChatItem = {
        id: `voice-assistant-${Date.now()}`, role: 'assistant', content: '', createdAt: null, pending: true,
      };
      setItems((current) => [...current, userItem, assistantItem]);
      attachStartedRun(started.runId, assistantItem.id);
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not transcribe the recording');
    }
  }, [userId, id, attachStartedRun, showToast]);

  const handleVoice = useCallback(async () => {
    if (runIdRef.current || sendingRef.current) {
      showToast('Wait for the current answer before recording another message');
      return;
    }
    const preferOnDevice =
      settings.preferredVoiceMode === 'on-device' ||
      (settings.preferredVoiceMode === 'auto' && isOnDeviceSttAvailable());

    if (preferOnDevice) {
      setListening(true);
      setPartial('');
      let recordingOnServer = false;
      try {
        const transcript = await transcribeOnDevice({
          timeoutMs: 20_000,
          onPartial: (text) => setPartial(text),
        });
        if (transcript) await send(transcript);
        else showToast('No speech was detected');
      } catch (error) {
        showToast(error instanceof Error ? error.message : 'Voice input failed');
        // On-device failure falls back to the server STT endpoint.
        if (voice?.stt) {
          try {
            await startServerRecording();
            recordingOnServer = true;
            showToast('Using server transcription – tap the mic again when done');
          } catch (captureError) {
            showToast(captureError instanceof Error ? captureError.message : 'Microphone unavailable');
          }
        }
      } finally {
        // Keep "listening" on when push-to-talk recording was just started.
        if (!recordingOnServer) setListening(false);
        setPartial('');
      }
      return;
    }

    // Server STT fallback (push-to-talk): first tap starts, second tap uploads.
    if (!voice?.stt) {
      showToast('Server voice is disabled. Enable BANK_VOICE_ENABLED and AUDIO_API_KEY, or use a device build.');
      return;
    }
    if (!listening) {
      try {
        await startServerRecording();
      } catch (error) {
        showToast(error instanceof Error ? error.message : 'Could not start recording');
      }
      return;
    }
    await submitVoiceRecording();
  }, [
    settings.preferredVoiceMode, voice, listening, send, startServerRecording,
    submitVoiceRecording, showToast,
  ]);

  const handleSpeak = useCallback(async (text: string) => {
    if (speaking) {
      await Speech.stop();
      setSpeaking(false);
      return;
    }
    setSpeaking(true);
    try {
      await Speech.speak(stripMarkdown(text), {
        language: 'en-US',
        onDone: () => setSpeaking(false),
        onError: () => setSpeaking(false),
      });
    } catch {
      setSpeaking(false);
    }
  }, [speaking]);

  const handleAttach = useCallback(async (asset: PickedAsset) => {
    const problem = validateAttachment(asset);
    if (problem) {
      showToast(problem);
      return;
    }
    if (!userId) return;
    setUploading(true);
    try {
      await api.uploadDocument(asset, userId, scope, id);
      const fresh = await api.getConversation(id!, userId);
      setDocuments(fresh.documents);
      await appStorage.setConversation(userId, fresh);
      showToast(`${asset.name} uploaded as ${scope} context`);
      setSheet(null);
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }, [scope, userId, id, showToast]);

  const removeDocument = useCallback(async (documentId: string) => {
    if (!userId) return;
    try {
      await api.deleteDocument(documentId, userId);
      const fresh = await api.getConversation(id!, userId);
      setDocuments(fresh.documents);
      showToast('Document removed');
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not remove document');
    }
  }, [userId, id, showToast]);

  const playServerAudio = useCallback(async () => {
    if (!serverAudioUrl) return;
    try {
      const player = createAudioPlayer(serverAudioUrl);
      player.play();
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not play server audio');
    }
  }, [serverAudioUrl, showToast]);

  const renderItem = useCallback(
    ({ item }: { item: ChatItem }) => (
      <ChatMessage item={item} onSpeak={(text) => void handleSpeak(text)} speaking={speaking} />
    ),
    [handleSpeak, speaking],
  );

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={90}
    >
      <View style={styles.headerRow}>
        <TouchableOpacity onPress={() => setSheet('documents')} accessibilityLabel="Uploaded documents">
          <Text style={styles.headerButton}>📎 Docs ({documents.length})</Text>
        </TouchableOpacity>
        <TouchableOpacity
          onPress={() => void updateSettings({ autoSpeak: !settings.autoSpeak })}
          accessibilityLabel="Toggle automatic speech"
        >
          <Text style={[styles.headerButton, settings.autoSpeak && styles.headerButtonOn]}>
            {settings.autoSpeak ? '🔊 Auto' : '🔇 Auto'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => setTraceVisible((value) => !value)} accessibilityLabel="Toggle agent trace">
          <Text style={styles.headerButton}>{traceVisible ? 'Hide trace' : 'Trace'}</Text>
        </TouchableOpacity>
      </View>

      {loading ? (
        <ActivityIndicator style={styles.loading} color="#0B6E4F" />
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.list}
          onContentSizeChange={() => undefined}
        />
      )}

      {traceVisible && status && (
        <View style={styles.traceBox}>
          <Text style={styles.traceTitle}>Agent trace · {status.status}</Text>
          <ScrollView style={styles.traceScroll}>
            {status.events.map((event, index) => (
              <Text key={index} style={styles.traceLine}>
                {event.agent || 'system'} · {event.type}: {truncate(event.message || event.detail || '', 140)}
              </Text>
            ))}
            {status.toolCalls.map((tool, index) => (
              <Text key={`tool-${index}`} style={styles.traceTool}>
                {tool.origin}/{tool.name} ({tool.durationMs}ms){tool.error ? ' ERROR' : ''}
              </Text>
            ))}
          </ScrollView>
          <TouchableOpacity onPress={() => setTraceVisible(false)}><Text style={styles.close}>Close</Text></TouchableOpacity>
        </View>
      )}

      {listening && <Text style={styles.listening}>🎙 Listening… {partial}</Text>}

      <View style={styles.composer}>
        <TouchableOpacity style={styles.iconButton} onPress={() => setSheet('attachments')} accessibilityLabel="Attach a file">
          <Text style={styles.iconText}>＋</Text>
        </TouchableOpacity>
        <TextInput
          style={styles.input}
          value={input}
          onChangeText={setInput}
          placeholder="Ask about your accounts…"
          accessibilityLabel="Message"
          multiline
          maxLength={4000}
          onSubmitEditing={() => void send(input)}
        />
        <TouchableOpacity
          style={[styles.iconButton, listening && styles.iconButtonActive]}
          onPress={() => void handleVoice()}
          accessibilityLabel={listening ? 'Stop recording' : 'Voice input'}
        >
          <Text style={styles.iconText}>{listening ? '■' : '🎙'}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.sendButton}
          onPress={() => void send(input)}
          disabled={Boolean(runId) || !input.trim()}
          accessibilityLabel="Send message"
        >
          {runId ? <ActivityIndicator size="small" color="#FFFFFF" /> : <Text style={styles.sendText}>Send</Text>}
        </TouchableOpacity>
      </View>

      {serverAudioUrl && (
        <TouchableOpacity style={styles.audioLink} onPress={() => void playServerAudio()}>
          <Text style={styles.audioLinkText}>▶ Play server-generated audio</Text>
        </TouchableOpacity>
      )}

      <Modal visible={sheet !== null} transparent animationType="slide" onRequestClose={() => setSheet(null)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>
              {sheet === 'attachments' ? 'Attach context' : 'Uploaded documents'}
            </Text>
            {uploading && <ActivityIndicator color="#0B6E4F" />}

            {sheet === 'attachments' && !uploading && (
              <>
                <View style={styles.scopeRow}>
                  <TouchableOpacity
                    style={[styles.scopeButton, scope === 'chat' && styles.scopeActive]}
                    onPress={() => setScope('chat')}
                  >
                    <Text style={styles.scopeText}>This chat</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.scopeButton, scope === 'user' && styles.scopeActive]}
                    onPress={() => setScope('user')}
                  >
                    <Text style={styles.scopeText}>All my chats</Text>
                  </TouchableOpacity>
                </View>
                <TouchableOpacity style={styles.modalAction} onPress={() => void pickImage('library').then((asset) => asset && handleAttach(asset))}>
                  <Text style={styles.modalActionText}>Photo library</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.modalAction} onPress={() => void pickImage('camera').then((asset) => asset && handleAttach(asset))}>
                  <Text style={styles.modalActionText}>Camera</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.modalAction} onPress={() => void pickDocument().then((asset) => asset && handleAttach(asset))}>
                  <Text style={styles.modalActionText}>Document / file</Text>
                </TouchableOpacity>
              </>
            )}

            {sheet === 'documents' && (
              <ScrollView style={styles.docList}>
                {documents.length === 0 && <Text style={styles.emptyDocs}>No uploaded documents yet.</Text>}
                {documents.map((document) => (
                  <View key={document.id} style={styles.docRow}>
                    <View style={styles.docInfo}>
                      <Text style={styles.docName} numberOfLines={1}>{document.filename}</Text>
                      <Text style={styles.docMeta}>
                        {document.scope} · {document.chunks} chunks
                      </Text>
                    </View>
                    <TouchableOpacity onPress={() => void removeDocument(document.id)} accessibilityLabel={`Delete ${document.filename}`}>
                      <Text style={styles.docDelete}>✕</Text>
                    </TouchableOpacity>
                  </View>
                ))}
              </ScrollView>
            )}

            <TouchableOpacity style={styles.modalClose} onPress={() => setSheet(null)}>
              <Text style={styles.modalCloseText}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F6FAF8' },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 12, paddingVertical: 8 },
  headerButton: { fontSize: 13, color: '#0B6E4F' },
  headerButtonOn: { fontWeight: '700' },
  loading: { flex: 1, justifyContent: 'center' },
  list: { paddingVertical: 6 },
  traceBox: { marginHorizontal: 10, maxHeight: 200, backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#E1EAE5', borderRadius: 8, padding: 10 },
  traceTitle: { fontWeight: '700', color: '#10251B', marginBottom: 4 },
  traceScroll: { maxHeight: 140 },
  traceLine: { fontSize: 12, color: '#40564B', marginBottom: 3 },
  traceTool: { fontSize: 12, color: '#0B6E4F', marginBottom: 3 },
  close: { color: '#0B6E4F', fontSize: 12, marginTop: 6 },
  listening: { textAlign: 'center', color: '#B26A00', paddingVertical: 4 },
  composer: { flexDirection: 'row', alignItems: 'flex-end', padding: 10, backgroundColor: '#FFFFFF', borderTopWidth: 1, borderColor: '#E1EAE5' },
  iconButton: { width: 40, height: 40, borderRadius: 20, backgroundColor: '#E8F3ED', alignItems: 'center', justifyContent: 'center', marginRight: 6 },
  iconButtonActive: { backgroundColor: '#0B6E4F' },
  iconText: { fontSize: 20, color: '#0B6E4F' },
  input: { flex: 1, minHeight: 40, maxHeight: 120, borderWidth: 1, borderColor: '#CBDAD2', borderRadius: 20, paddingHorizontal: 14, paddingVertical: 8, fontSize: 15, backgroundColor: '#FFFFFF' },
  sendButton: { marginLeft: 6, minWidth: 62, height: 40, borderRadius: 20, backgroundColor: '#0B6E4F', alignItems: 'center', justifyContent: 'center' },
  sendText: { color: '#FFFFFF', fontWeight: '700' },
  audioLink: { padding: 10, alignItems: 'center' },
  audioLinkText: { color: '#0B6E4F', fontSize: 13 },
  modalBackdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.4)' },
  modalCard: { backgroundColor: '#FFFFFF', borderTopLeftRadius: 18, borderTopRightRadius: 18, padding: 18, maxHeight: '75%' },
  modalTitle: { fontSize: 18, fontWeight: '700', color: '#10251B', marginBottom: 12 },
  scopeRow: { flexDirection: 'row', gap: 8, marginBottom: 12 },
  scopeButton: { flex: 1, padding: 10, borderRadius: 8, borderWidth: 1, borderColor: '#CBDAD2', alignItems: 'center' },
  scopeActive: { backgroundColor: '#E8F3ED', borderColor: '#0B6E4F' },
  scopeText: { color: '#10251B' },
  modalAction: { paddingVertical: 14, borderBottomWidth: 1, borderColor: '#E1EAE5' },
  modalActionText: { fontSize: 16, color: '#0B6E4F' },
  modalClose: { marginTop: 12, alignItems: 'center', padding: 10 },
  modalCloseText: { color: '#60756A', fontWeight: '700' },
  docList: { maxHeight: 340 },
  docRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: 1, borderColor: '#E1EAE5' },
  docInfo: { flex: 1 },
  docName: { fontSize: 15, color: '#10251B' },
  docMeta: { fontSize: 12, color: '#60756A', marginTop: 2 },
  docDelete: { fontSize: 18, color: '#B3261E', padding: 8 },
  emptyDocs: { color: '#60756A', textAlign: 'center', marginVertical: 20 },
});
