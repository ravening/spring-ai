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

/** The detail endpoint omits updatedAt/messageCount; only the list summary has them. */
export interface ConversationDetail extends Omit<ConversationSummary, 'updatedAt' | 'messageCount'> {
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

export interface McpToolInfo {
  name: string;
  description: string;
}

/** Mirrors bank-agent's McpStatus record: {state, endpoint, tools[], error}. */
export interface ToolStatus {
  localTools: string[];
  mcp: {
    state?: string;
    endpoint?: string;
    tools?: McpToolInfo[];
    error?: string | null;
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

/** Notification inbox (single source of truth on the server). */
export interface NotificationDto {
  id: number;
  userId: string;
  conversationId: string;
  type: string;
  title: string;
  body: string;
  detail: string | null;
  jobId: string | null;
  read: boolean;
  createdAt: number;
}

export interface NotificationPage {
  unreadCount: number;
  items: NotificationDto[];
}

export interface UnreadCount {
  count: number;
}

/** Backend rows use BigDecimal, which Jackson serializes as JSON numbers. */
export interface AccountRow {
  accountId: string;
  userId: string;
  name: string;
  type: string;
  currency: string;
  balance: number;
  iban: string;
}

export interface TransferResultDto {
  txnId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  currency: string;
  senderOldBalance: number;
  senderNewBalance: number;
  recipientOldBalance: number;
  recipientNewBalance: number;
  duplicate: boolean;
}

export interface TransferRow {
  id: string;
  fromUserId: string;
  fromAccountId: string;
  toUserId: string;
  toAccountId: string;
  amount: number;
  currency: string;
  note: string | null;
  fromOldBalance: number;
  fromNewBalance: number;
  toOldBalance: number;
  toNewBalance: number;
  createdAt: number;
}

export interface JobDto {
  id: string;
  userId: string;
  conversationId: string;
  type: string;
  payload: string;
  status: string;
  runAt: number | null;
  cron: string | null;
  intervalMinutes: number | null;
  timezone: string | null;
  runCount: number;
  lastRunAt: number | null;
  lastResult: string | null;
  createdAt: number;
}

export interface JobRunDto {
  id: string;
  jobId: string;
  idempotencyKey: string;
  scheduledFor: number | null;
  startedAt: number | null;
  finishedAt: number | null;
  status: string;
  result: string | null;
}

export interface JobDetails {
  job: JobDto;
  runs: JobRunDto[];
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
