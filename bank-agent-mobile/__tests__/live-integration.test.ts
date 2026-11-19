/**
 * Live integration test for the real BankApiClient against a running backend.
 * Run with: LIVE_API=1 npm test  (requires bank-agent on http://localhost:8095)
 */
import { readFileSync } from 'node:fs';
import { BankApiClient } from '../src/lib/api';

jest.setTimeout(300_000);

const describeLive = process.env.LIVE_API === '1' ? describe : describe.skip;
const BASE = process.env.LIVE_API_BASE_URL || 'http://localhost:8095';

// React Native's fetch accepts { uri, name, type } file objects inside FormData;
// Node's fetch does not. This shim keeps the production client unchanged while
// letting the same code path run in a live Node test.
const RealFormData = global.FormData;
class NodeFormData extends FormData {
  override append(name: string, value: unknown, filename?: string): void {
    if (value && typeof value === 'object' && 'uri' in value) {
      const file = value as { uri: string; name: string; mimeType: string };
      const path = file.uri.replace(/^file:\/\//, '');
      const blob = new Blob([readFileSync(path)], { type: file.mimeType });
      super.append(name, blob, file.name);
    } else if (typeof value === 'string') {
      super.append(name, value);
    } else {
      super.append(name, value as Blob, filename);
    }
  }
}

describeLive('BankApiClient against live bank-agent', () => {
  const api = new BankApiClient(BASE);
  beforeAll(() => { global.FormData = NodeFormData as unknown as typeof FormData; });
  afterAll(() => { global.FormData = RealFormData; });

  it('lists users, reports voice and tools', async () => {
    const users = await api.listUsers();
    expect(users.map((user) => user.id)).toEqual(expect.arrayContaining(['alice', 'bob', 'carol']));
    const voice = await api.voiceStatus();
    expect(typeof voice.stt).toBe('boolean');
    const tools = await api.listTools();
    expect(tools.localTools).toContain('knowledge_search');
    expect(tools.mcp.state).toBe('CONNECTED');
  });

  it('creates a conversation, chats, polls, reads history and deletes', async () => {
    const conversation = await api.createConversation('alice');
    expect(conversation.id).toBeTruthy();

    const started = await api.sendMessage('alice', 'What is my account balance?', conversation.id);
    expect(started.runId).toBeTruthy();

    let status = await api.getRunStatus(started.runId);
    for (let attempt = 0; attempt < 180 && status.status === 'RUNNING'; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      status = await api.getRunStatus(started.runId);
    }
    expect(status.status).toBe('COMPLETED');
    expect(status.answer).toBeTruthy();

    const detail = await api.getConversation(conversation.id, 'alice');
    expect(detail.messages.length).toBeGreaterThanOrEqual(2);
    expect(detail.messages.some((message) => message.role === 'assistant')).toBe(true);

    await api.deleteConversation(conversation.id, 'alice');
  });

  it('uploads, lists and deletes a document', async () => {
    const conversation = await api.createConversation('alice');
    const file = {
      uri: 'file:///tmp/bank-statement.txt',
      name: 'bank-statement.txt',
      mimeType: 'text/plain',
    };
    const document = await api.uploadDocument(file, 'alice', 'chat', conversation.id);
    expect(document.filename).toBe('bank-statement.txt');
    const documents = await api.listDocuments('alice', conversation.id);
    expect(documents.some((item) => item.id === document.id)).toBe(true);
    await api.deleteDocument(document.id, 'alice');
    await api.deleteConversation(conversation.id, 'alice');
  });

  it('does not allow reading another customer conversation', async () => {
    const conversation = await api.createConversation('alice');
    await expect(api.getConversation(conversation.id, 'bob')).rejects.toMatchObject({ status: 403 });
    await api.deleteConversation(conversation.id, 'alice');
  });
});
