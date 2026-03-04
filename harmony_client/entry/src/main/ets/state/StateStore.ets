import {
  AppState,
  AgentInfo,
  ContextUsageSnapshot,
  FileContent,
  FileDiff,
  FileNode,
  FileStatusEntry,
  MessageWithParts,
  PendingPermission,
  Project,
  Session,
  SessionStatus,
  SshTunnelConfig,
  SseEvent,
  TodoItem
} from '../model/Models'

export enum SseSideEffect {
  None = 'none',
  FullSync = 'full_sync',
  ReloadSessions = 'reload_sessions',
  ReloadMessages = 'reload_messages'
}

export class StateStore {
  private state: AppState
  private listeners: Array<(state: AppState) => void> = []

  constructor(serverBaseUrl: string) {
    this.state = {
      serverBaseUrl: serverBaseUrl,
      connected: false,
      sessions: [],
      sessionStatuses: {},
      messages: [],
      streamingPartTexts: {},
      projects: [],
      agents: [],
      pendingPermissions: [],
      todosBySession: {},
      filePath: '',
      fileNodes: [],
      fileStatuses: [],
      providerContextLimits: {},
      providerConfigError: undefined,
      contextUsage: undefined,
      messageLimitBySession: {},
      hasMoreHistoryBySession: {},
      loadingOlderMessages: false,
      sessionDiffs: [],
      fileSearchQuery: '',
      fileSearchResults: [],
      speechBaseUrl: 'https://space.ai-builders.com/backend',
      speechToken: '',
      speechPrompt: 'Prefer snake_case filenames and keep code terms unchanged.',
      speechTerminology: '',
      speechConnectionOk: false,
      speechConnectionError: undefined,
      sshConfig: this.defaultSshConfig(),
      sshStatus: 'Disconnected'
    }
  }

  getState(): AppState {
    return this.state
  }

  subscribe(listener: (state: AppState) => void): void {
    this.listeners.push(listener)
  }

  setServerBaseUrl(url: string): void {
    this.state = {
      ...this.state,
      serverBaseUrl: url,
      connected: false,
      connectionError: undefined,
      sessions: [],
      currentSessionID: undefined,
      sessionStatuses: {},
      messages: [],
      streamingPartTexts: {},
      projects: [],
      selectedProjectWorktree: undefined,
      agents: [],
      pendingPermissions: [],
      todosBySession: {},
      filePath: '',
      fileNodes: [],
      fileStatuses: [],
      selectedFilePath: undefined,
      selectedFileContent: undefined,
      providerContextLimits: {},
      providerConfigError: undefined,
      contextUsage: undefined,
      messageLimitBySession: {},
      hasMoreHistoryBySession: {},
      loadingOlderMessages: false,
      sessionDiffs: [],
      fileSearchQuery: '',
      fileSearchResults: [],
      speechConnectionOk: false,
      speechConnectionError: undefined
    }
    this.notify()
  }

  setConnection(connected: boolean, version?: string, error?: string): void {
    this.state = {
      ...this.state,
      connected: connected,
      serverVersion: version ?? this.state.serverVersion,
      connectionError: error
    }
    this.notify()
  }

  setProjects(projects: Array<Project>): void {
    const selected = this.state.selectedProjectWorktree
    const keepSelected = selected && projects.some((p) => p.worktree === selected) ? selected : undefined
    this.state = {
      ...this.state,
      projects: projects,
      selectedProjectWorktree: keepSelected
    }
    this.notify()
  }

  setSelectedProject(worktree?: string): void {
    this.state = { ...this.state, selectedProjectWorktree: worktree }
    this.notify()
  }

  setAgents(agents: Array<AgentInfo>): void {
    this.state = { ...this.state, agents: agents }
    this.notify()
  }

  setSessions(sessions: Array<Session>): void {
    this.state = {
      ...this.state,
      sessions: sessions,
      currentSessionID: this.state.currentSessionID ?? sessions[0]?.id
    }
    this.notify()
  }

  setCurrentSession(sessionID?: string): void {
    this.state = {
      ...this.state,
      currentSessionID: sessionID,
      messages: [],
      streamingPartTexts: {}
    }
    this.notify()
  }

  setSessionStatuses(statuses: Record<string, SessionStatus>): void {
    this.state = {
      ...this.state,
      sessionStatuses: statuses
    }
    this.notify()
  }

  setMessages(requestedSessionID: string, messages: Array<MessageWithParts>): void {
    if (this.state.currentSessionID !== requestedSessionID) {
      return
    }
    this.state = { ...this.state, messages: messages }
    this.notify()
  }

  setSessionTodos(sessionID: string, todos: Array<TodoItem>): void {
    this.state = {
      ...this.state,
      todosBySession: {
        ...this.state.todosBySession,
        [sessionID]: todos
      }
    }
    this.notify()
  }

  setFileNodes(path: string, nodes: Array<FileNode>): void {
    this.state = {
      ...this.state,
      filePath: path,
      fileNodes: nodes
    }
    this.notify()
  }

  setFileStatuses(statuses: Array<FileStatusEntry>): void {
    this.state = {
      ...this.state,
      fileStatuses: statuses
    }
    this.notify()
  }

  setSelectedFile(path?: string, content?: FileContent): void {
    this.state = {
      ...this.state,
      selectedFilePath: path,
      selectedFileContent: content
    }
    this.notify()
  }

  setProviderContextLimits(limits: Record<string, number>, error?: string): void {
    this.state = {
      ...this.state,
      providerContextLimits: limits,
      providerConfigError: error
    }
    this.notify()
  }

  setContextUsage(snapshot?: ContextUsageSnapshot): void {
    this.state = {
      ...this.state,
      contextUsage: snapshot
    }
    this.notify()
  }

  setMessageLimit(sessionID: string, limit: number): void {
    this.state = {
      ...this.state,
      messageLimitBySession: {
        ...this.state.messageLimitBySession,
        [sessionID]: limit
      }
    }
    this.notify()
  }

  setHasMoreHistory(sessionID: string, hasMore: boolean): void {
    this.state = {
      ...this.state,
      hasMoreHistoryBySession: {
        ...this.state.hasMoreHistoryBySession,
        [sessionID]: hasMore
      }
    }
    this.notify()
  }

  setLoadingOlderMessages(loading: boolean): void {
    this.state = {
      ...this.state,
      loadingOlderMessages: loading
    }
    this.notify()
  }

  setSessionDiffs(diffs: Array<FileDiff>): void {
    this.state = {
      ...this.state,
      sessionDiffs: diffs
    }
    this.notify()
  }

  setFileSearch(query: string, results: Array<string>): void {
    this.state = {
      ...this.state,
      fileSearchQuery: query,
      fileSearchResults: results
    }
    this.notify()
  }

  setSpeechConfig(
    baseUrl: string,
    token: string,
    prompt: string,
    terminology: string
  ): void {
    this.state = {
      ...this.state,
      speechBaseUrl: baseUrl,
      speechToken: token,
      speechPrompt: prompt,
      speechTerminology: terminology
    }
    this.notify()
  }

  setSpeechConnection(ok: boolean, error?: string): void {
    this.state = {
      ...this.state,
      speechConnectionOk: ok,
      speechConnectionError: error
    }
    this.notify()
  }

  setSshConfig(config: SshTunnelConfig): void {
    this.state = {
      ...this.state,
      sshConfig: config
    }
    this.notify()
  }

  setSshStatus(status: string): void {
    this.state = {
      ...this.state,
      sshStatus: status
    }
    this.notify()
  }

  removeSession(sessionID: string): void {
    const nextSessions = this.state.sessions.filter((s) => s.id !== sessionID)
    const nextCurrent = this.state.currentSessionID === sessionID ? nextSessions[0]?.id : this.state.currentSessionID
    const nextStatuses: Record<string, SessionStatus> = { ...this.state.sessionStatuses }
    delete nextStatuses[sessionID]
    const nextTodos = { ...this.state.todosBySession }
    delete nextTodos[sessionID]
    const nextLimits = { ...this.state.messageLimitBySession }
    delete nextLimits[sessionID]
    const nextHasMore = { ...this.state.hasMoreHistoryBySession }
    delete nextHasMore[sessionID]
    this.state = {
      ...this.state,
      sessions: nextSessions,
      currentSessionID: nextCurrent,
      sessionStatuses: nextStatuses,
      todosBySession: nextTodos,
      messageLimitBySession: nextLimits,
      hasMoreHistoryBySession: nextHasMore,
      pendingPermissions: this.state.pendingPermissions.filter((p) => p.sessionID !== sessionID)
    }
    this.notify()
  }

  applySseEvent(event: SseEvent): SseSideEffect {
    const type = event.payload.type
    const properties = event.payload.properties ?? {}

    if (type === 'server.connected') {
      return SseSideEffect.FullSync
    }

    if (type === 'session.status') {
      const sessionID = this.getString(properties['sessionID'])
      const statusObj = properties['status'] as Record<string, Object | string | number | boolean>
      if (!sessionID || !statusObj) {
        return SseSideEffect.None
      }
      const status: SessionStatus = {
        type: this.getString(statusObj['type']) ?? 'idle',
        attempt: this.getNumber(statusObj['attempt']),
        message: this.getString(statusObj['message']),
        next: this.getNumber(statusObj['next'])
      }
      this.state = {
        ...this.state,
        sessionStatuses: { ...this.state.sessionStatuses, [sessionID]: status }
      }
      this.notify()
      return SseSideEffect.None
    }

    if (type === 'session.deleted') {
      const sessionID = this.getString(properties['sessionID']) ?? this.getString(properties['id'])
      if (sessionID) {
        this.removeSession(sessionID)
      }
      return SseSideEffect.ReloadSessions
    }

    if (type === 'message.part.updated') {
      const sessionID = this.getString(properties['sessionID'])
      if (!sessionID || sessionID !== this.state.currentSessionID) {
        return SseSideEffect.None
      }
      const part = properties['part'] as Record<string, Object | string | number | boolean>
      if (!part) {
        return SseSideEffect.None
      }
      const messageID = this.getString(part['messageID'])
      const partID = this.getString(part['id'])
      if (!messageID || !partID) {
        return SseSideEffect.None
      }
      const key = `${messageID}:${partID}`
      const delta = this.getString(properties['delta'])
      if (delta && delta.length > 0) {
        const previous = this.state.streamingPartTexts[key] ?? ''
        this.state = {
          ...this.state,
          streamingPartTexts: { ...this.state.streamingPartTexts, [key]: previous + delta }
        }
        this.notify()
        return SseSideEffect.None
      }
      return SseSideEffect.ReloadMessages
    }

    if (type === 'permission.asked') {
      const sessionID = this.getString(properties['sessionID'])
      const permissionID = this.getString(properties['permissionID']) ?? this.getString(properties['id'])
      if (sessionID && permissionID) {
        const exists = this.state.pendingPermissions.some((p) => p.id === permissionID && p.sessionID === sessionID)
        if (!exists) {
          const perm: PendingPermission = {
            id: permissionID,
            sessionID: sessionID,
            permission: this.getString(properties['permission']),
            patterns: this.getStringArray(properties['patterns'])
          }
          this.state = { ...this.state, pendingPermissions: [...this.state.pendingPermissions, perm] }
          this.notify()
        }
      }
      return SseSideEffect.None
    }

    if (type === 'permission.replied') {
      const sessionID = this.getString(properties['sessionID'])
      const permissionID = this.getString(properties['permissionID']) ?? this.getString(properties['id'])
      if (sessionID && permissionID) {
        this.state = {
          ...this.state,
          pendingPermissions: this.state.pendingPermissions.filter(
            (p) => !(p.sessionID === sessionID && p.id === permissionID)
          )
        }
        this.notify()
      }
      return SseSideEffect.None
    }

    if (type === 'todo.updated') {
      const sessionID = this.getString(properties['sessionID'])
      const todos = properties['todos'] as Array<Record<string, Object | string | number | boolean>>
      if (sessionID && todos) {
        const mapped: Array<TodoItem> = todos.map((t) => ({
          id: this.getString(t['id']),
          content: this.getString(t['content']),
          status: this.getString(t['status']),
          priority: this.getString(t['priority']),
          completed: this.getBoolean(t['completed'])
        }))
        this.setSessionTodos(sessionID, mapped)
      }
      return SseSideEffect.None
    }

    if (type === 'session.updated') {
      return SseSideEffect.ReloadSessions
    }
    if (type === 'message.updated') {
      return SseSideEffect.ReloadMessages
    }

    return SseSideEffect.None
  }

  private defaultSshConfig(): SshTunnelConfig {
    return {
      enabled: false,
      host: '',
      port: 22,
      username: '',
      password: '',
      privateKey: '',
      passphrase: '',
      remotePort: 18080,
      localPort: 14096
    }
  }

  private getString(input: Object | string | number | boolean | undefined): string | undefined {
    if (typeof input === 'string') return input
    return undefined
  }

  private getBoolean(input: Object | string | number | boolean | undefined): boolean | undefined {
    if (typeof input === 'boolean') return input
    return undefined
  }

  private getNumber(input: Object | string | number | boolean | undefined): number | undefined {
    if (typeof input === 'number') return input
    return undefined
  }

  private getStringArray(input: Object | string | number | boolean | undefined): Array<string> | undefined {
    if (!Array.isArray(input)) return undefined
    return input.filter((x): x is string => typeof x === 'string')
  }

  private notify(): void {
    this.listeners.forEach((listener) => listener(this.state))
  }
}
