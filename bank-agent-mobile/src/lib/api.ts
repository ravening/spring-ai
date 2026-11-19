import { buildUrl, getApiBaseUrl, normalizeBaseUrl } from './config';
import { authorizationHeader } from './auth';
import type {
  AttachmentAsset,
  AuthMe,
  AuthStatus,
  BankUser,
  ConversationDetail,
  ConversationSummary,
  DocumentInfo,
  PushStatus,
  RunStatus,
  StartRunResponse,
  ToolStatus,
  VoiceStartResponse,
  VoiceStatus,
} from './types';

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
  method?: 'GET' | 'POST' | 'DELETE';
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

  unregisterPushToken(userId: string): Promise<void> {
    return this.request('/api/notifications/register', { method: 'DELETE', params: { userId } });
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
