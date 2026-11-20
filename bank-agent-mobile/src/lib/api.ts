import { buildUrl, getApiBaseUrl, normalizeBaseUrl } from './config';
import { authorizationHeader } from './auth';
import type {
  AttachmentAsset,
  AuthMe,
  AuthStatus,
  BankUser,
  ConversationDetail,
  ConversationSummary,
  AccountRow,
  DocumentInfo,
  JobDetails,
  JobDto,
  NotificationPage,
  PushStatus,
  RunStatus,
  StartRunResponse,
  ToolStatus,
  TransferResultDto,
  TransferRow,
  UnreadCount,
  VoiceStartResponse,
  VoiceStatus,
} from './types';

declare const require: (moduleId: string) => any;

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** URL query parameters. Empty/undefined values are dropped. */
  params?: Record<string, string | undefined | null>;
  /** Multipart form data (used for uploads and voice). */
  form?: FormData;
  signal?: AbortSignal;
  timeoutMs?: number;
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

function errorMessage(status: number, body: unknown): string {
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    for (const key of ['message', 'error', 'detail']) {
      const value = record[key];
      if (typeof value === 'string' && value.trim()) return value;
    }
  }
  if (typeof body === 'string' && body.trim()) return body.slice(0, 300);
  return `Request failed with HTTP ${status}`;
}

/**
 * Typed client for the bank-agent REST API.
 *
 * Every method maps 1:1 to an endpoint in bank-agent/src/main/java/com/example/bank/web.
 * The transport is injectable so unit tests can exercise it without a server.
 */
export class BankApiClient {
  constructor(
    public baseUrl: string = getApiBaseUrl(),
    private readonly fetchImpl: FetchLike = (input, init) => fetch(input, init),
  ) {}

  /** Lets the user point the app at a LAN IP or tunnel without rebuilding. */
  setBaseUrl(url: string): void {
    this.baseUrl = normalizeBaseUrl(url);
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, params, form, signal, timeoutMs = 30_000 } = options;
    const url = buildUrl(this.baseUrl, path, params);

    const headers: Record<string, string> = { ...authorizationHeader() };
    let payload: BodyInit | undefined;
    if (form) {
      payload = form;
      // Do NOT set Content-Type: React Native / fetch adds the multipart boundary.
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }

    const timeoutController = new AbortController();
    const timer = setTimeout(() => timeoutController.abort(), timeoutMs);
    const onOuterAbort = () => timeoutController.abort();
    signal?.addEventListener('abort', onOuterAbort);

    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body: payload,
        signal: timeoutController.signal,
      });
    } catch (error) {
      const aborted = timeoutController.signal.aborted;
      throw new ApiError(
        aborted ? 'Request timed out' : error instanceof Error ? error.message : 'Network error',
        0,
      );
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onOuterAbort);
    }

    let parsed: unknown;
    if (response.status !== 204) {
      const text = await response.text();
      if (text) {
        try {
          parsed = JSON.parse(text);
        } catch {
          parsed = text;
        }
      }
    }

    if (!response.ok) {
      throw new ApiError(errorMessage(response.status, parsed), response.status, parsed);
    }
    return parsed as T;
  }

  /** Fetch with the current bearer token (used for audio streams). */
  authenticatedFetch(url: string, init?: RequestInit): Promise<Response> {
    return this.fetchImpl(url, {
      ...init,
      headers: { ...authorizationHeader(), ...(init?.headers as Record<string, string> | undefined) },
    });
  }

  authStatus(): Promise<AuthStatus> {
    return this.request<AuthStatus>('/api/auth/status');
  }

  authMe(userId?: string): Promise<AuthMe> {
    return this.request<AuthMe>('/api/auth/me', { params: { userId } });
  }

  pushStatus(): Promise<PushStatus> {
    return this.request<PushStatus>('/api/notifications/status');
  }

  registerPushToken(token: string, userId: string, platform: string): Promise<{ registered: boolean; userId: string }> {
    return this.request('/api/notifications/register', {
      method: 'POST',
      body: { token, userId, platform },
    });
  }

  unregisterPushToken(userId: string, token?: string): Promise<void> {
    return this.request('/api/notifications/register', { method: 'DELETE', params: { userId, token } });
  }

  // ---- Notification inbox (polling + notification center) ----

  listNotifications(userId: string, sinceId = 0, limit?: number, unreadOnly = false): Promise<NotificationPage> {
    return this.request<NotificationPage>('/api/notifications', {
      params: { userId, sinceId: String(sinceId), limit: limit === undefined ? undefined : String(limit), unreadOnly: String(unreadOnly) },
    });
  }

  unreadCount(userId: string): Promise<UnreadCount> {
    return this.request<UnreadCount>('/api/notifications/unread-count', { params: { userId } });
  }

  markNotificationRead(id: number, userId: string): Promise<{ id: number; read: boolean }> {
    return this.request(`/api/notifications/${id}/read`, { method: 'POST', params: { userId } });
  }

  markAllNotificationsRead(userId: string): Promise<{ updated: number }> {
    return this.request('/api/notifications/read-all', { method: 'POST', params: { userId } });
  }

  deleteNotification(id: number, userId: string): Promise<void> {
    return this.request<void>(`/api/notifications/${id}`, { method: 'DELETE', params: { userId } });
  }

  // ---- Authoritative accounts / transfers (BankStore) ----

  listAccounts(userId: string): Promise<AccountRow[]> {
    return this.request<AccountRow[]>('/api/accounts', { params: { userId } });
  }

  listTransfers(userId: string): Promise<TransferRow[]> {
    return this.request<TransferRow[]>('/api/transfers', { params: { userId } });
  }

  createTransfer(
    userId: string,
    body: { fromAccountId: string; toUserId: string; toAccountId: string; amount: string; currency?: string; note?: string; idempotencyKey?: string },
  ): Promise<TransferResultDto> {
    return this.request<TransferResultDto>('/api/transfers', { method: 'POST', body: { ...body, userId } });
  }

  // ---- Job CRUD (mirrors the REST contract; jobs are mainly created from chat) ----

  listJobs(userId: string, conversationId?: string, status?: string): Promise<JobDto[]> {
    return this.request<JobDto[]>('/api/jobs', { params: { userId, conversationId, status } });
  }

  getJob(id: string, userId: string): Promise<JobDetails> {
    return this.request<JobDetails>(`/api/jobs/${encodeURIComponent(id)}`, { params: { userId } });
  }

  createJob(userId: string, body: Record<string, unknown>): Promise<JobDto> {
    return this.request<JobDto>('/api/jobs', { method: 'POST', body: { ...body, userId } });
  }

  patchJob(id: string, userId: string, patch: Record<string, unknown>): Promise<JobDto> {
    return this.request<JobDto>(`/api/jobs/${encodeURIComponent(id)}`, { method: 'PATCH', body: { ...patch, userId } });
  }

  cancelJob(id: string, userId: string): Promise<void> {
    return this.request<void>(`/api/jobs/${encodeURIComponent(id)}`, { method: 'DELETE', params: { userId } });
  }

  runJobNow(id: string, userId: string): Promise<JobDto> {
    return this.request<JobDto>(`/api/jobs/${encodeURIComponent(id)}/run`, { method: 'POST', params: { userId } });
  }

  listUsers(): Promise<BankUser[]> {
    return this.request<BankUser[]>('/api/users');
  }

  listConversations(userId: string): Promise<ConversationSummary[]> {
    return this.request<ConversationSummary[]>('/api/conversations', { params: { userId } });
  }

  createConversation(userId: string): Promise<ConversationSummary> {
    return this.request<ConversationSummary>('/api/conversations', {
      method: 'POST',
      body: { userId },
    });
  }

  getConversation(conversationId: string, userId: string): Promise<ConversationDetail> {
    return this.request<ConversationDetail>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
      params: { userId },
    });
  }

  deleteConversation(conversationId: string, userId: string): Promise<void> {
    return this.request<void>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
      method: 'DELETE',
      params: { userId },
    });
  }

  sendMessage(userId: string, message: string, conversationId?: string): Promise<StartRunResponse> {
    return this.request<StartRunResponse>('/api/chat', {
      method: 'POST',
      body: { userId, conversationId: conversationId || undefined, message },
    });
  }

  getRunStatus(runId: string): Promise<RunStatus> {
    return this.request<RunStatus>(`/api/runs/${encodeURIComponent(runId)}`);
  }

  getRunAudioUrl(runId: string): string {
    return buildUrl(this.baseUrl, `/api/runs/${encodeURIComponent(runId)}/audio`);
  }

  /**
   * Downloads the server-synthesized answer audio with the bearer token attached and
   * writes it to the cache directory, returning the local file URI. Needed because
   * expo-audio cannot send an Authorization header for a remote URL, so playback
   * would 401 whenever token auth is enabled. Returns null when the run has no audio.
   */
  async downloadRunAudio(runId: string, signal?: AbortSignal): Promise<string | null> {
    const response = await this.authenticatedFetch(this.getRunAudioUrl(runId), { signal });
    if (!response.ok) {
      await response.body?.cancel?.().catch(() => undefined);
      return null;
    }
    const buffer = await response.arrayBuffer();
    // Lazy require so Node-based unit tests never load the native module.
    const { File, Paths } = require('expo-file-system') as typeof import('expo-file-system');
    const file = new File(Paths.cache, `run-${runId}.mp3`);
    if (file.exists) file.delete();
    await file.write(buffer);
    return file.uri;
  }

  listDocuments(userId: string, conversationId?: string): Promise<DocumentInfo[]> {
    return this.request<DocumentInfo[]>('/api/documents', {
      params: { userId, conversationId },
    });
  }

  deleteDocument(documentId: string, userId: string): Promise<void> {
    return this.request<void>(`/api/documents/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
      params: { userId },
    });
  }

  voiceStatus(): Promise<VoiceStatus> {
    return this.request<VoiceStatus>('/api/voice/status');
  }

  listTools(): Promise<ToolStatus> {
    return this.request<ToolStatus>('/api/tools');
  }

  /** Uploads a file as RAG context; scope is `chat` or `user`. */
  async uploadDocument(
    file: AttachmentAsset,
    userId: string,
    scope: 'chat' | 'user',
    conversationId?: string,
  ): Promise<DocumentInfo> {
    if (scope === 'chat' && !conversationId) {
      throw new ApiError('conversationId is required when scope=chat', 0);
    }
    const form = new FormData();
    form.append('file', { uri: file.uri, name: file.name, type: file.mimeType } as unknown as Blob);
    form.append('userId', userId);
    form.append('scope', scope);
    if (conversationId) form.append('conversationId', conversationId);
    return this.request<DocumentInfo>('/api/documents', { method: 'POST', form, timeoutMs: 120_000 });
  }

  /** Server-side STT fallback: posts recorded audio and starts a normal agent run. */
  async voiceChat(
    audio: AttachmentAsset,
    userId: string,
    conversationId?: string,
  ): Promise<VoiceStartResponse> {
    const form = new FormData();
    form.append('audio', { uri: audio.uri, name: audio.name, type: audio.mimeType } as unknown as Blob);
    form.append('userId', userId);
    if (conversationId) form.append('conversationId', conversationId);
    return this.request<VoiceStartResponse>('/api/chat/voice', {
      method: 'POST',
      form,
      timeoutMs: 120_000,
    });
  }

  /**
   * Optional SSE answer stream. Returns false when streaming is unavailable so the
   * caller can fall back to polling GET /api/runs/{runId}.
   */
  async streamRun(
    runId: string,
    onEvent: (event: { type: 'draft' | 'delta' | 'done'; text: string; draft: boolean }) => void,
    signal?: AbortSignal,
  ): Promise<boolean> {
    const url = buildUrl(this.baseUrl, `/api/runs/${encodeURIComponent(runId)}/stream`);
    const response = await this.fetchImpl(url, {
      headers: { ...authorizationHeader(), Accept: 'text/event-stream' },
      signal,
    });
    if (!response.ok || !response.body?.getReader) {
      await response.body?.cancel?.().catch(() => undefined);
      return false;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = 'delta';
    let dataLines: string[] = [];

    const dispatch = () => {
      if (!dataLines.length) return;
      const raw = dataLines.join('\n');
      dataLines = [];
      try {
        const payload = JSON.parse(raw) as { draft: boolean; delta: string };
        onEvent({
          type: eventName === 'draft' || eventName === 'done' ? eventName : 'delta',
          text: payload.delta ?? '',
          draft: Boolean(payload.draft),
        });
      } catch {
        // Ignore malformed SSE payloads; polling remains the source of truth.
      }
    };

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart());
          } else if (line.trim() === '') {
            dispatch();
            eventName = 'delta';
          }
        }
      }
      dispatch();
      return true;
    } catch {
      return false;
    } finally {
      reader.releaseLock?.();
    }
  }
}

// A single shared client is enough for the whole app.
export const api = new BankApiClient();
