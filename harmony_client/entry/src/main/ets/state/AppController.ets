import {
  AppState,
  MessageWithParts
} from '../model/Models'
import { ApiClient, ServerConfig } from '../network/ApiClient'
import { SseClient } from '../network/SseClient'
import { SseSideEffect, StateStore } from './StateStore'

export class AppController {
  private store: StateStore
  private api: ApiClient
  private sseClient?: SseClient
  private pollTimer: number = -1
  private authUsername?: string
  private authPassword?: string
  private listeners: Array<(state: AppState) => void> = []

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
    await this.loadCurrentSessionData()
  }

  async createSession(title?: string): Promise<void> {
    const created = await this.api.createSession(title)
    await this.loadSessions()
    this.store.setCurrentSession(created.id)
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
    await this.api.promptAsync(sid, text, agent)
    await this.loadMessages(sid)
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

  async respondPermission(sessionID: string, permissionID: string, response: 'once' | 'always' | 'reject'): Promise<void> {
    await this.api.respondPermission(sessionID, permissionID, response)
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
    if (!sid) return
    await this.loadMessages(sid)
    await this.loadSessionTodos(sid)
  }

  private async loadMessages(sessionID: string): Promise<void> {
    const messages: Array<MessageWithParts> = await this.api.messages(sessionID, 60)
    this.store.setMessages(sessionID, messages)
  }

  private async loadSessionTodos(sessionID: string): Promise<void> {
    const todos = await this.api.todos(sessionID)
    this.store.setSessionTodos(sessionID, todos)
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
    // V1 fallback: keep data reasonably fresh even when SSE transport adapter is unavailable.
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

  private normalizeBaseUrl(raw: string): string {
    const trimmed = raw.trim()
    if (trimmed.length === 0) return 'http://127.0.0.1:4096'
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) return trimmed
    return `http://${trimmed}`
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
