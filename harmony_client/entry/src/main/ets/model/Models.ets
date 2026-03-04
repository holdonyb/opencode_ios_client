export interface HealthResponse {
  healthy: boolean
  version?: string
}

export interface Project {
  id: string
  worktree: string
  vcs?: string
}

export interface SessionTimeInfo {
  created: number
  updated: number
  archived?: number
}

export interface Session {
  id: string
  slug?: string
  projectID?: string
  directory: string
  parentID?: string
  title: string
  version?: string
  time: SessionTimeInfo
}

export interface SessionStatus {
  type: string
  attempt?: number
  message?: string
  next?: number
}

export interface MessageTimeInfo {
  created: number
  completed?: number
}

export interface MessageModelInfo {
  providerID: string
  modelID: string
}

export interface MessageInfo {
  id: string
  sessionID: string
  role: string
  parentID?: string
  providerID?: string
  modelID?: string
  model?: MessageModelInfo
  tokens?: TokenInfo
  cost?: number
  time: MessageTimeInfo
}

export interface TokenCacheInfo {
  read?: number
  write?: number
}

export interface TokenInfo {
  total?: number
  input?: number
  output?: number
  reasoning?: number
  cache?: TokenCacheInfo
}

export interface Part {
  id: string
  messageID: string
  sessionID: string
  type: string
  text?: string
  tool?: string
  callID?: string
  state?: Object | string
  metadata?: Object
}

export interface MessageWithParts {
  info: MessageInfo
  parts: Array<Part>
}

export interface AgentInfo {
  name: string
  description?: string
  mode?: string
  hidden?: boolean
}

export interface PendingPermission {
  id: string
  sessionID: string
  permission?: string
  patterns?: Array<string>
}

export interface TodoItem {
  id?: string
  content?: string
  status?: string
  priority?: string
  completed?: boolean
}

export interface SsePayload {
  type: string
  properties?: Record<string, Object | string | number | boolean>
}

export interface SseEvent {
  directory?: string
  payload: SsePayload
}

export interface FileNode {
  name: string
  path: string
  absolute?: string
  type: string
  ignored?: boolean
}

export interface FileContent {
  type: string
  content?: string
}

export interface FileStatusEntry {
  path?: string
  status?: string
}

export interface FileDiff {
  file: string
  before: string
  after: string
  additions?: number
  deletions?: number
  status?: string
}

export interface ProviderModelLimit {
  context?: number
  input?: number
  output?: number
}

export interface ProviderModel {
  id: string
  name?: string
  providerID?: string
  limit?: ProviderModelLimit
}

export interface ConfigProvider {
  id: string
  name?: string
  models: Record<string, ProviderModel>
}

export interface ProvidersResponse {
  providers: Array<ConfigProvider>
}

export interface ContextUsageSnapshot {
  sessionID: string
  sessionTitle: string
  providerID: string
  modelID: string
  contextLimit: number
  totalTokens: number
  inputTokens: number
  outputTokens: number
  reasoningTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  totalSessionCost?: number
  usageRatio: number
}

export interface SshTunnelConfig {
  enabled: boolean
  host: string
  port: number
  username: string
  password?: string
  privateKey?: string
  passphrase?: string
  remotePort: number
  localPort: number
}

export interface AppState {
  serverBaseUrl: string
  connected: boolean
  connectionError?: string
  serverVersion?: string
  sessions: Array<Session>
  currentSessionID?: string
  sessionStatuses: Record<string, SessionStatus>
  messages: Array<MessageWithParts>
  streamingPartTexts: Record<string, string>
  projects: Array<Project>
  selectedProjectWorktree?: string
  agents: Array<AgentInfo>
  pendingPermissions: Array<PendingPermission>
  todosBySession: Record<string, Array<TodoItem>>
  filePath: string
  fileNodes: Array<FileNode>
  fileStatuses: Array<FileStatusEntry>
  selectedFilePath?: string
  selectedFileContent?: FileContent
  providerContextLimits: Record<string, number>
  providerConfigError?: string
  contextUsage?: ContextUsageSnapshot
  messageLimitBySession: Record<string, number>
  hasMoreHistoryBySession: Record<string, boolean>
  loadingOlderMessages: boolean
  sessionDiffs: Array<FileDiff>
  fileSearchQuery: string
  fileSearchResults: Array<string>
  speechBaseUrl: string
  speechToken: string
  speechPrompt: string
  speechTerminology: string
  speechConnectionOk: boolean
  speechConnectionError?: string
  sshConfig: SshTunnelConfig
  sshStatus: string
}
