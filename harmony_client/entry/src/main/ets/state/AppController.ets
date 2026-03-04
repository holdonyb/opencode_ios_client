import {
  AppState,
  ContextUsageSnapshot,
  MessageModelInfo,
  MessageWithParts,
  SshTunnelConfig
} from '../model/Models'
import { ApiClient, ServerConfig } from '../network/ApiClient'
import { SseClient } from '../network/SseClient'
import { SseSideEffect, StateStore } from './StateStore'

export class AppController {
  private readonly defaultMessageLimit: number = 6
  private readonly modelPresets: Array<MessageModelInfo> = [
    { providerID: 'zai-coding-plan', modelID: 'glm-5' },
    { providerID: 'anthropic', modelID: 'claude-opus-4-6' },
    { providerID: 'anthropic', modelID: 'claude-sonnet-4-6' },
    { providerID: 'openai', modelID: 'gpt-5.3-codex' },
    { providerID: 'openai', modelID: 'gpt-5.2' },
    { providerID: 'google', modelID: 'gemini-3.1-pro-preview' },
    { providerID: 'google', modelID: 'gemini-3-flash-preview' }
  ]

  private store: StateStore
  private api: ApiClient
  private sseClient?: SseClient
  private pollTimer: number = -1
  private authUsername?: string
  private authPassword?: string
  private listeners: Array<(state: AppState) => void> = []
  private selectedModelBySession: Record<string, MessageModelInfo> = {}

  constructor(initialBaseUrl: string) {
    const config: ServerConfig = {
      baseUrl: this.normalizeBaseUrl(initialBaseUrl)
    }
    this.store = new StateStore(config.baseUrl)
    this.api = new ApiClient(config)
    this.store.subscribe((state) => this.notify(state))
  }

  subscribe(listener: (state: AppState) => void): void {
    this.listeners.push(listener)
    listener(this.store.getState())
  }

  getState(): AppState {
    return this.store.getState()
  }

  async applySettings(baseUrl: string, username?: string, password?: string): Promise<void> {
    const config: ServerConfig = {
      baseUrl: this.normalizeBaseUrl(baseUrl),
      username: username && username.trim().length > 0 ? username.trim() : undefined,
      password: password && password.trim().length > 0 ? password : undefined
    }
    this.api.setConfig(config)
    this.authUsername = config.username
    this.authPassword = config.password
    this.store.setServerBaseUrl(config.baseUrl)
    await this.refreshAll()
  }

  async refreshAll(): Promise<void> {
    try {
      const health = await this.api.health()
      this.store.setConnection(health.healthy, health.version)
      await this.loadProviders()
      await this.loadProjects()
      await this.loadAgents()
      await this.loadSessions()
      await this.loadSessionStatuses()
      await this.loadCurrentSessionData()
      await this.loadFileRoot()
      await this.loadFileStatuses()
      this.startSse()
    } catch (e) {
      this.store.setConnection(false, undefined, this.errorMessage(e))
    }
  }

  async selectProject(worktree?: string): Promise<void> {
    this.store.setSelectedProject(worktree)
    await this.loadSessions()
  }

  async selectSession(sessionID: string): Promise<void> {
    this.store.setCurrentSession(sessionID)
    this.store.setMessageLimit(sessionID, this.currentMessageLimit(sessionID))
    await this.loadCurrentSessionData()
  }

  async createSession(title?: string): Promise<void> {
    const state = this.store.getState()
    if (state.selectedProjectWorktree) {
      throw new Error('Create is allowed only under server default project')
    }
    const created = await this.api.createSession(title)
    await this.loadSessions()
    this.store.setCurrentSession(created.id)
    this.store.setMessageLimit(created.id, this.defaultMessageLimit)
    await this.loadCurrentSessionData()
  }

  async renameCurrentSession(title: string): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid || title.trim().length === 0) return
    await this.api.updateSession(sid, title.trim())
    await this.loadSessions()
  }

  async deleteCurrentSession(): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid) return
    await this.api.deleteSession(sid)
    await this.loadSessions()
    const next = this.store.getState().sessions[0]?.id
    if (next) {
      this.store.setCurrentSession(next)
      await this.loadCurrentSessionData()
    }
  }

  async sendMessage(text: string, agent: string): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid || text.trim().length === 0) return
    await this.api.promptAsync(sid, text, agent, this.selectedModelForSession(sid))
    await this.loadMessages(sid)
  }

  async summarizeCurrentSession(): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid) return
    await this.api.summarize(sid)
    await this.loadMessages(sid)
    await this.loadSessionStatuses()
  }

  async loadOlderMessages(): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid) return
    const hasMore = this.store.getState().hasMoreHistoryBySession[sid]
    if (hasMore === false) return
    this.store.setLoadingOlderMessages(true)
    this.store.setMessageLimit(sid, this.currentMessageLimit(sid) + this.defaultMessageLimit)
    await this.loadMessages(sid)
    this.store.setLoadingOlderMessages(false)
  }

  async abortCurrentSession(): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid) return
    await this.api.abort(sid)
    await this.loadSessionStatuses()
    await this.loadMessages(sid)
  }

  async loadFileRoot(): Promise<void> {
    await this.loadFileList('')
  }

  async loadFileList(path: string): Promise<void> {
    const nodes = await this.api.fileList(path)
    const sorted = nodes.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'directory' ? -1 : 1
      return a.name.localeCompare(b.name)
    })
    this.store.setFileNodes(path, sorted)
  }

  async openFile(path: string): Promise<void> {
    const content = await this.api.fileContent(path)
    this.store.setSelectedFile(path, content)
  }

  async searchFiles(query: string): Promise<void> {
    const trimmed = query.trim()
    if (trimmed.length === 0) {
      this.store.setFileSearch('', [])
      return
    }
    const results = await this.api.findFile(trimmed, 50)
    this.store.setFileSearch(trimmed, results)
  }

  async respondPermission(sessionID: string, permissionID: string, response: 'once' | 'always' | 'reject'): Promise<void> {
    await this.api.respondPermission(sessionID, permissionID, response)
  }

  setSelectedModelIndex(index: number): void {
    const sid = this.store.getState().currentSessionID
    if (!sid) return
    const safe = this.modelPresets[Math.max(0, Math.min(index, this.modelPresets.length - 1))]
    this.selectedModelBySession[sid] = safe
    this.recomputeContextUsage()
  }

  selectedModelIndexForCurrentSession(): number {
    const sid = this.store.getState().currentSessionID
    if (!sid) return 0
    const selected = this.selectedModelForSession(sid)
    const idx = this.modelPresets.findIndex((m) => m.providerID === selected.providerID && m.modelID === selected.modelID)
    return idx >= 0 ? idx : 0
  }

  getModelPresets(): Array<MessageModelInfo> {
    return this.modelPresets
  }

  setSpeechConfig(baseUrl: string, token: string, prompt: string, terminology: string): void {
    this.store.setSpeechConfig(baseUrl, token, prompt, terminology)
  }

  async testSpeechConnection(): Promise<void> {
    const state = this.store.getState()
    const base = this.normalizeSpeechBaseUrl(state.speechBaseUrl)
    const token = state.speechToken.trim()
    if (token.length === 0) {
      this.store.setSpeechConnection(false, 'Speech token is required')
      return
    }
    try {
      const response = await fetch(`${base}/v1/embeddings`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ input: 'ok' })
      })
      if (!response.ok) {
        this.store.setSpeechConnection(false, `Speech test HTTP ${response.status}`)
        return
      }
      this.store.setSpeechConnection(true, undefined)
    } catch (e) {
      this.store.setSpeechConnection(false, this.errorMessage(e))
    }
  }

  setSshConfig(config: SshTunnelConfig): void {
    this.store.setSshConfig(config)
  }

  async connectSshTunnel(): Promise<void> {
    const cfg = this.store.getState().sshConfig
    if (!cfg.enabled) {
      this.store.setSshStatus('SSH disabled')
      return
    }
    if (cfg.host.trim().length === 0 || cfg.username.trim().length === 0) {
      this.store.setSshStatus('SSH host and username are required')
      return
    }
    // Harmony V1 note: this configures the app to use the local forwarded endpoint once tunnel is available.
    this.store.setSshStatus('Connected (local forward configured)')
    const nextBase = `http://127.0.0.1:${cfg.localPort}`
    await this.applySettings(nextBase, this.authUsername, this.authPassword)
  }

  disconnectSshTunnel(): void {
    this.store.setSshStatus('Disconnected')
  }

  private async loadProviders(): Promise<void> {
    try {
      const response = await this.api.providers()
      const limits: Record<string, number> = {}
      response.providers.forEach((provider) => {
        Object.keys(provider.models ?? {}).forEach((modelID) => {
          const item = provider.models[modelID]
          const context = item?.limit?.context
          if (context && context > 0) {
            limits[`${provider.id}/${modelID}`] = context
          }
        })
      })
      this.store.setProviderContextLimits(limits, undefined)
      this.recomputeContextUsage()
    } catch (e) {
      this.store.setProviderContextLimits({}, this.errorMessage(e))
    }
  }

  private async loadProjects(): Promise<void> {
    const projects = await this.api.projects()
    this.store.setProjects(projects)
  }

  private async loadAgents(): Promise<void> {
    const agents = (await this.api.agents()).filter((a) => a.hidden !== true)
    this.store.setAgents(agents)
  }

  private async loadSessions(): Promise<void> {
    const selected = this.store.getState().selectedProjectWorktree
    const sessions = await this.api.sessions(selected, 100)
    sessions.sort((a, b) => b.time.updated - a.time.updated)
    this.store.setSessions(sessions)
  }

  private async loadSessionStatuses(): Promise<void> {
    this.store.setSessionStatuses(await this.api.sessionStatus())
  }

  private async loadCurrentSessionData(): Promise<void> {
    const sid = this.store.getState().currentSessionID
    if (!sid) {
      this.store.setContextUsage(undefined)
      this.store.setSessionDiffs([])
      return
    }
    this.store.setMessageLimit(sid, this.currentMessageLimit(sid))
    await this.loadMessages(sid)
    await this.loadSessionTodos(sid)
    await this.loadSessionDiff(sid)
  }

  private async loadMessages(sessionID: string): Promise<void> {
    const limit = this.currentMessageLimit(sessionID)
    const messages: Array<MessageWithParts> = await this.api.messages(sessionID, limit)
    this.store.setMessages(sessionID, messages)
    this.store.setHasMoreHistory(sessionID, messages.length >= limit)
    this.recomputeContextUsage()
  }

  private async loadSessionTodos(sessionID: string): Promise<void> {
    const todos = await this.api.todos(sessionID)
    this.store.setSessionTodos(sessionID, todos)
  }

  private async loadSessionDiff(sessionID: string): Promise<void> {
    try {
      this.store.setSessionDiffs(await this.api.sessionDiff(sessionID))
    } catch (_e) {
      this.store.setSessionDiffs([])
    }
  }

  private async loadFileStatuses(): Promise<void> {
    const statuses = await this.api.fileStatus()
    this.store.setFileStatuses(statuses)
  }

  private startSse(): void {
    this.sseClient?.stop()
    this.stopPolling()
    const state = this.store.getState()
    const authHeader = this.buildBasicAuthHeader()
    this.sseClient = new SseClient(state.serverBaseUrl, authHeader)
    this.sseClient.connect(
      (event) => {
        const side = this.store.applySseEvent(event)
        this.handleSideEffect(side)
      },
      async (_error) => {
        this.startPolling()
      }
    )
      .catch((_error) => {
        this.startPolling()
      })
    this.startPolling()
  }

  private startPolling(): void {
    if (this.pollTimer > 0) return
    this.pollTimer = setInterval(async () => {
      try {
        await this.loadSessionStatuses()
        await this.loadCurrentSessionData()
      } catch (_ignore) {
      }
    }, 3000)
  }

  private stopPolling(): void {
    if (this.pollTimer > 0) {
      clearInterval(this.pollTimer)
      this.pollTimer = -1
    }
  }

  dispose(): void {
    this.sseClient?.stop()
    this.stopPolling()
  }

  private handleSideEffect(side: SseSideEffect): void {
    if (side === SseSideEffect.None) return
    if (side === SseSideEffect.FullSync) {
      this.loadSessionStatuses()
      this.loadCurrentSessionData()
    } else if (side === SseSideEffect.ReloadSessions) {
      this.loadSessions()
    } else if (side === SseSideEffect.ReloadMessages) {
      const sid = this.store.getState().currentSessionID
      if (sid) this.loadMessages(sid)
    }
  }

  private currentMessageLimit(sessionID: string): number {
    const current = this.store.getState().messageLimitBySession[sessionID]
    if (!current || current < this.defaultMessageLimit) return this.defaultMessageLimit
    return current
  }

  private selectedModelForSession(sessionID: string): MessageModelInfo {
    return this.selectedModelBySession[sessionID] ?? this.modelPresets[0]
  }

  private recomputeContextUsage(): void {
    const state = this.store.getState()
    const sid = state.currentSessionID
    if (!sid) {
      this.store.setContextUsage(undefined)
      return
    }
    const session = state.sessions.find((x) => x.id === sid)
    const reversed = [...state.messages].reverse()
    const assistant = reversed.find((m) => m.info.role === 'assistant' && m.info.tokens)
    if (!session || !assistant || !assistant.info.tokens) {
      this.store.setContextUsage(undefined)
      return
    }
    const model = assistant.info.model
      ?? (assistant.info.providerID && assistant.info.modelID
        ? { providerID: assistant.info.providerID, modelID: assistant.info.modelID }
        : undefined)
    if (!model) {
      this.store.setContextUsage(undefined)
      return
    }
    const contextLimit = state.providerContextLimits[`${model.providerID}/${model.modelID}`]
    if (!contextLimit || contextLimit <= 0) {
      this.store.setContextUsage(undefined)
      return
    }

    const tokens = assistant.info.tokens
    const input = tokens.input ?? 0
    const output = tokens.output ?? 0
    const reasoning = tokens.reasoning ?? 0
    const cacheRead = tokens.cache?.read ?? 0
    const cacheWrite = tokens.cache?.write ?? 0
    const total = tokens.total ?? (input + output + reasoning + cacheRead + cacheWrite)
    const totalCost = state.messages
      .map((m) => m.info.cost)
      .filter((x): x is number => typeof x === 'number')
      .reduce((sum, v) => sum + v, 0)
    const snapshot: ContextUsageSnapshot = {
      sessionID: sid,
      sessionTitle: session.title,
      providerID: model.providerID,
      modelID: model.modelID,
      contextLimit: contextLimit,
      totalTokens: total,
      inputTokens: input,
      outputTokens: output,
      reasoningTokens: reasoning,
      cacheReadTokens: cacheRead,
      cacheWriteTokens: cacheWrite,
      totalSessionCost: totalCost > 0 ? totalCost : undefined,
      usageRatio: Math.min(1.0, total / contextLimit)
    }
    this.store.setContextUsage(snapshot)
  }

  private normalizeBaseUrl(raw: string): string {
    const trimmed = raw.trim()
    if (trimmed.length === 0) return 'http://127.0.0.1:4096'
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) return trimmed
    return `http://${trimmed}`
  }

  private normalizeSpeechBaseUrl(raw: string): string {
    const trimmed = raw.trim()
    if (trimmed.length === 0) return 'https://space.ai-builders.com/backend'
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) return trimmed
    return `https://${trimmed}`
  }

  private buildBasicAuthHeader(): string | undefined {
    if (!this.authUsername || !this.authPassword) return undefined
    return `Basic ${this.base64Encode(`${this.authUsername}:${this.authPassword}`)}`
  }

  private base64Encode(input: string): string {
    const table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    let output = ''
    let i = 0
    while (i < input.length) {
      const a = input.charCodeAt(i++) & 0xff
      const b = i < input.length ? input.charCodeAt(i++) & 0xff : NaN
      const c = i < input.length ? input.charCodeAt(i++) & 0xff : NaN
      const triple = (a << 16) | ((isNaN(b) ? 0 : b) << 8) | (isNaN(c) ? 0 : c)
      const n1 = (triple >> 18) & 63
      const n2 = (triple >> 12) & 63
      const n3 = (triple >> 6) & 63
      const n4 = triple & 63
      output += table.charAt(n1)
      output += table.charAt(n2)
      output += isNaN(b) ? '=' : table.charAt(n3)
      output += isNaN(c) ? '=' : table.charAt(n4)
    }
    return output
  }

  private notify(state: AppState): void {
    this.listeners.forEach((listener) => listener(state))
  }

  private errorMessage(error: Object): string {
    const maybe = error as Error
    return maybe?.message ?? 'Unknown error'
  }
}

