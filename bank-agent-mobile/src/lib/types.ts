/** Wire types mirroring the bank-agent REST API (Spring records -> JSON). */

export interface AuthStatus {
  enabled: boolean;
  tokens: number;
}

export interface AuthMe {
  userId: string;
  authenticated: boolean;
}

export interface PushStatus {
  enabled: boolean;
  registrations: number;
}

export interface VoiceStartResponse extends StartRunResponse {
  message: string;
}

export interface BankUser {
  id: string;
  displayName: string;
  segment: string;
  homeBranch: string;
}

export interface ConversationSummary {
  id: string;
  userId: string;
  title: string;
  updatedAt: string | null;
  messageCount: number;
  active: boolean;
  activeRunId: string | null;
}

export interface ConversationMessage {
  role: string;
  content: string;
  createdAt: string | null;
}

export interface DocumentInfo {
  id: string;
  filename: string;
  scope: string;
  conversationId: string | null;
  userId: string;
  sizeBytes: number;
  chunks: number;
  uploadedAtEpochSeconds: number;
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[];
  documents: DocumentInfo[];
}

export interface StartRunResponse {
  conversationId: string;
  runId: string;
}

export type RunStatusValue = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface AgentEvent {
  at: string | null;
  type: string;
  agent: string;
  message: string;
  detail: string | null;
  durationMs: number | null;
}

export interface ToolCallRecord {
  at: string | null;
  agent: string;
  origin: string;
  name: string;
  arguments: string;
  result: string;
  durationMs: number;
  error: boolean;
}

export interface RunStatus {
  runId: string;
  conversationId: string;
  userId: string;
  status: RunStatusValue;
  message: string;
  answer: string | null;
  error: string | null;
  createdAt: string | null;
  finishedAt: string | null;
  events: AgentEvent[];
  toolCalls: ToolCallRecord[];
}

export interface VoiceStatus {
  stt: boolean;
  tts: boolean;
}

export interface ToolStatus {
  localTools: string[];
  mcp: {
    connected?: boolean;
    tools?: number;
    message?: string;
    [key: string]: unknown;
  };
}

/** A local file ready to be uploaded as multipart form data. */
export interface AttachmentAsset {
  uri: string;
  name: string;
  mimeType: string;
  /** Local file size in bytes, when known (used for client-side validation only). */
  sizeBytes?: number;
}

/** A message shown in the chat UI. `localId` is stable for animation keys. */
export interface ChatItem {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: string | null;
  pending?: boolean;
  error?: boolean;
  runId?: string;
}
