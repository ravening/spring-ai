import { ApiError, BankApiClient, type FetchLike } from '../src/lib/api';
import { setBearerToken } from '../src/lib/auth';

jest.mock('expo-secure-store', () => ({
  getItemAsync: async () => null,
  setItemAsync: async () => undefined,
  deleteItemAsync: async () => undefined,
}));

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Call = { url: string; init?: RequestInit };

function makeClient(handler: (url: string, init?: RequestInit) => Response): { client: BankApiClient; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = async (url, init) => {
    calls.push({ url, init });
    return handler(url, init);
  };
  return { client: new BankApiClient('http://test:8095', fetchImpl), calls };
}

const runStatus = {
  runId: 'run-1', conversationId: 'conv-1', userId: 'alice', status: 'COMPLETED',
  message: 'What is my balance?', answer: '€1,234', error: null,
  createdAt: null, finishedAt: null, events: [], toolCalls: [],
};

describe('BankApiClient', () => {
  it('lists users and conversations with encoded userId', async () => {
    const { client, calls } = makeClient((url) => {
      if (url.includes('/users')) return jsonResponse([{ id: 'alice', displayName: 'Alice' }]);
      return jsonResponse([{ id: 'conv-1', title: 'Hi', active: false }]);
    });
    await expect(client.listUsers()).resolves.toEqual([{ id: 'alice', displayName: 'Alice' }]);
    await expect(client.listConversations('alice')).resolves.toEqual([{ id: 'conv-1', title: 'Hi', active: false }]);
    expect(calls[1].url).toBe('http://test:8095/api/conversations?userId=alice');
  });

  it('creates, reads and deletes conversations', async () => {
    const { client, calls } = makeClient((_url, init) => {
      if (init?.method === 'POST') return jsonResponse({ id: 'new', userId: 'alice', title: '' }, 201);
      if (init?.method === 'DELETE') return new Response(null, { status: 204 });
      return jsonResponse({ id: 'conv-1', userId: 'alice', title: 'T', messages: [], documents: [] });
    });
    await client.createConversation('alice');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ userId: 'alice' });

    const detail = await client.getConversation('conv-1', 'alice');
    expect(detail.messages).toEqual([]);
    expect(calls[1].url).toBe('http://test:8095/api/conversations/conv-1?userId=alice');

    await expect(client.deleteConversation('conv-1', 'alice')).resolves.toBeUndefined();
    expect(calls[2].init?.method).toBe('DELETE');
  });

  it('starts a run and polls its status', async () => {
    const { client, calls } = makeClient((_url, init) => {
      if (init?.method === 'POST') return new Response(JSON.stringify({ conversationId: 'conv-1', runId: 'run-1' }), { status: 202 });
      return jsonResponse(runStatus);
    });
    await expect(client.sendMessage('alice', 'hello', 'conv-1')).resolves.toEqual({ conversationId: 'conv-1', runId: 'run-1' });
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ userId: 'alice', conversationId: 'conv-1', message: 'hello' });
    await expect(client.getRunStatus('run-1')).resolves.toEqual(runStatus);
  });

  it('throws ApiError with the server message on failures', async () => {
    const { client } = makeClient(() => jsonResponse({ message: 'Unknown userId' }, 400));
    await expect(client.listUsers()).rejects.toMatchObject({ status: 400, message: 'Unknown userId' });
    await expect(client.listUsers()).rejects.toBeInstanceOf(ApiError);
  });

  it('posts multipart uploads with the right fields', async () => {
    const { client, calls } = makeClient(() => jsonResponse({ id: 'doc-1' }, 201));
    await client.uploadDocument(
      { uri: 'file:///photo.jpg', name: 'photo.jpg', mimeType: 'image/jpeg' },
      'alice', 'chat', 'conv-1',
    );
    const form = calls[0].init?.body as FormData;
    expect(form.get('userId')).toBe('alice');
    expect(form.get('scope')).toBe('chat');
    expect(form.get('conversationId')).toBe('conv-1');
    await expect(
      client.uploadDocument({ uri: 'x', name: 'x.pdf', mimeType: 'application/pdf' }, 'alice', 'chat'),
    ).rejects.toThrow('conversationId is required');
  });

  it('posts voice audio to /api/chat/voice', async () => {
    const { client, calls } = makeClient(() => new Response(JSON.stringify({ conversationId: 'c', runId: 'r' }), { status: 202 }));
    const result = await client.voiceChat({ uri: 'file:///v.m4a', name: 'v.m4a', mimeType: 'audio/m4a' }, 'alice', 'c');
    expect(result.runId).toBe('r');
    expect(calls[0].url).toBe('http://test:8095/api/chat/voice');
    expect((calls[0].init?.body as FormData).get('userId')).toBe('alice');
  });

  it('parses SSE events for draft/delta/done', async () => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: draft\ndata: {"draft":true,"delta":""}\n\n'));
        controller.enqueue(encoder.encode('event: delta\ndata: {"draft":false,"delta":"hello "}\n\n'));
        controller.enqueue(encoder.encode('event: delta\ndata: {"draft":false,"delta":"world"}\n\n'));
        controller.enqueue(encoder.encode('event: done\ndata: {"draft":false,"delta":""}\n\n'));
        controller.close();
      },
    });
    const { client } = makeClient(() => new Response(stream, { status: 200 }));
    const events: string[] = [];
    const supported = await client.streamRun('run-1', (event) => {
      if (event.type === 'delta') events.push(event.text);
    });
    expect(supported).toBe(true);
    expect(events).toEqual(['hello ', 'world']);
  });

  it('attaches the bearer token to every request', async () => {
    const { client, calls } = makeClient(() => jsonResponse({ ok: true }));
    setBearerToken('my-token');
    try {
      await client.listUsers();
      expect(calls[0].init?.headers).toEqual({ Authorization: 'Bearer my-token' });
      const response = await client.authenticatedFetch('http://test:8095/api/runs/r/audio');
      expect(response.ok).toBe(true);
      expect(calls[1].init?.headers).toEqual({ Authorization: 'Bearer my-token' });
    } finally {
      setBearerToken(null);
    }
  });

  it('exposes auth and push endpoints', async () => {
    const { client, calls } = makeClient((url, _init) => {
      if (url.includes('/auth/status')) return jsonResponse({ enabled: true, tokens: 3 });
      if (url.includes('/auth/me')) return jsonResponse({ userId: 'alice', authenticated: true });
      if (url.includes('/notifications/status')) return jsonResponse({ enabled: true, registrations: 1 });
      return jsonResponse({ registered: true, userId: 'alice' }, 201);
    });
    await expect(client.authStatus()).resolves.toEqual({ enabled: true, tokens: 3 });
    await expect(client.authMe()).resolves.toEqual({ userId: 'alice', authenticated: true });
    await expect(client.pushStatus()).resolves.toEqual({ enabled: true, registrations: 1 });
    await expect(client.registerPushToken('ExpoPushToken[x]', 'alice', 'ios'))
      .resolves.toEqual({ registered: true, userId: 'alice' });
    const body = JSON.parse(String(calls[3].init?.body));
    expect(body).toEqual({ token: 'ExpoPushToken[x]', userId: 'alice', platform: 'ios' });
  });

  it('reports voice status and tool status', async () => {
    const { client } = makeClient((url) =>
      url.includes('voice') ? jsonResponse({ stt: true, tts: false }) : jsonResponse({ localTools: ['knowledge_search'], mcp: {} }),
    );
    await expect(client.voiceStatus()).resolves.toEqual({ stt: true, tts: false });
    await expect(client.listTools()).resolves.toEqual({ localTools: ['knowledge_search'], mcp: {} });
  });
});
