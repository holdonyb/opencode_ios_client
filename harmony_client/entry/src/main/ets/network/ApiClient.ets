import {
  AgentInfo,
  FileContent,
  FileDiff,
  FileNode,
  FileStatusEntry,
  HealthResponse,
  MessageModelInfo,
  MessageWithParts,
  ProvidersResponse,
  Project,
  Session,
  SessionStatus,
  TodoItem
} from '../model/Models'

export interface ServerConfig {
  baseUrl: string
  username?: string
  password?: string
}

export type PermissionResponse = 'once' | 'always' | 'reject'

export class ApiClient {
  private config: ServerConfig

  constructor(config: ServerConfig) {
    this.config = config
  }

  setConfig(config: ServerConfig): void {
    this.config = config
  }

  async health(): Promise<HealthResponse> {
    return this.getJson<HealthResponse>('/global/health')
  }

  async projects(): Promise<Array<Project>> {
    return this.getJson<Array<Project>>('/project')
  }

  async sessions(directory?: string, limit: number = 100): Promise<Array<Session>> {
    let query = `?limit=${limit}`
    if (directory && directory.length > 0) {
      query += `&directory=${encodeURIComponent(directory)}`
    }
    return this.getJson<Array<Session>>(`/session${query}`)
  }

  async createSession(title?: string): Promise<Session> {
    const body: Record<string, string> = {}
    if (title && title.trim().length > 0) {
      body['title'] = title.trim()
    }
    return this.sendJsonAndRead<Session>('/session', 'POST', body)
  }

  async updateSession(sessionID: string, title: string): Promise<Session> {
    return this.sendJsonAndRead<Session>(`/session/${sessionID}`, 'PATCH', { title: title })
  }

  async deleteSession(sessionID: string): Promise<void> {
    await this.sendJson(`/session/${sessionID}`, 'DELETE', {}, 200, true)
  }

  async sessionStatus(): Promise<Record<string, SessionStatus>> {
    return this.getJson<Record<string, SessionStatus>>('/session/status')
  }

  async messages(sessionID: string, limit?: number): Promise<Array<MessageWithParts>> {
    let query = ''
    if (limit && limit > 0) {
      query = `?limit=${limit}`
    }
    const raw = await this.getText(`/session/${sessionID}/message${query}`)
    return this.parseMessagesPayload(raw)
  }

  async promptAsync(sessionID: string, text: string, agent: string, model?: MessageModelInfo): Promise<void> {
    const body: Record<string, Object | string> = {
      parts: [{ type: 'text', text: text }],
      agent: agent
    }
    if (model) {
      body['model'] = model
    }
    await this.sendJson(`/session/${sessionID}/prompt_async`, 'POST', body, 204)
  }

  async abort(sessionID: string): Promise<void> {
    await this.sendJson(`/session/${sessionID}/abort`, 'POST', {}, 200)
  }

  async summarize(sessionID: string): Promise<void> {
    await this.sendJson(`/session/${sessionID}/summarize`, 'POST', {}, 200)
  }

  async providers(): Promise<ProvidersResponse> {
    const raw = await this.getText('/config/providers')
    const parsed = JSON.parse(raw) as Record<string, Object>
    const providersRaw = parsed['providers']
    let providers: Array<Object> = []
    if (Array.isArray(providersRaw)) {
      providers = providersRaw
    } else if (providersRaw && typeof providersRaw === 'object') {
      providers = Object.keys(providersRaw as Record<string, Object>).map((id) => {
        const item = (providersRaw as Record<string, Object>)[id] as Record<string, Object>
        return { id: (item['id'] as string) ?? id, ...item }
      })
    }
    return { providers: providers as any }
  }

  async agents(): Promise<Array<AgentInfo>> {
    return this.getJson<Array<AgentInfo>>('/agent')
  }

  async todos(sessionID: string): Promise<Array<TodoItem>> {
    return this.getJson<Array<TodoItem>>(`/session/${sessionID}/todo`)
  }

  async respondPermission(sessionID: string, permissionID: string, response: PermissionResponse): Promise<void> {
    await this.sendJson(`/session/${sessionID}/permissions/${permissionID}`, 'POST', { response: response }, 200)
  }

  async fileList(path: string): Promise<Array<FileNode>> {
    return this.getJson<Array<FileNode>>(`/file?path=${encodeURIComponent(path)}`)
  }

  async fileContent(path: string): Promise<FileContent> {
    return this.getJson<FileContent>(`/file/content?path=${encodeURIComponent(path)}`)
  }

  async fileStatus(): Promise<Array<FileStatusEntry>> {
    return this.getJson<Array<FileStatusEntry>>('/file/status')
  }

  async sessionDiff(sessionID: string): Promise<Array<FileDiff>> {
    return this.getJson<Array<FileDiff>>(`/session/${sessionID}/diff`)
  }

  async findFile(query: string, limit: number = 50): Promise<Array<string>> {
    return this.getJson<Array<string>>(`/find/file?query=${encodeURIComponent(query)}&limit=${limit}`)
  }

  private async getText(path: string): Promise<string> {
    const url = `${this.config.baseUrl}${path}`
    const response = await fetch(url, {
      method: 'GET',
      headers: this.authHeadersWithAccept()
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    return await response.text()
  }

  private async getJson<T>(path: string): Promise<T> {
    const text = await this.getText(path)
    return JSON.parse(text) as T
  }

  private async sendJson(path: string, method: string, body: Object, expectedStatus: number, allowDelete204: boolean = false): Promise<void> {
    const url = `${this.config.baseUrl}${path}`
    const response = await fetch(url, {
      method: method,
      headers: this.authHeadersWithJson(),
      body: JSON.stringify(body)
    })
    if (!(response.status === expectedStatus || (allowDelete204 && response.status === 204))) {
      throw new Error(`HTTP ${response.status}`)
    }
  }

  private async sendJsonAndRead<T>(path: string, method: string, body: Object): Promise<T> {
    const url = `${this.config.baseUrl}${path}`
    const response = await fetch(url, {
      method: method,
      headers: this.authHeadersWithJson(),
      body: JSON.stringify(body)
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const text = await response.text()
    return JSON.parse(text) as T
  }

  private parseMessagesPayload(raw: string): Array<MessageWithParts> {
    const trimmed = raw.trim()
    if (trimmed.length === 0) return []
    const parsed = JSON.parse(trimmed) as Object | Array<Object>
    if (Array.isArray(parsed)) {
      return parsed.map((p) => this.parseMessageRecord(p))
    }

    const obj = parsed as Record<string, Object>
    if (Array.isArray(obj['messages'])) {
      return (obj['messages'] as Array<Object>).map((p) => this.parseMessageRecord(p))
    }
    if (Array.isArray(obj['data'])) {
      return (obj['data'] as Array<Object>).map((p) => this.parseMessageRecord(p))
    }
    if (Array.isArray(obj['result'])) {
      return (obj['result'] as Array<Object>).map((p) => this.parseMessageRecord(p))
    }
    return [this.parseMessageRecord(obj)]
  }

  private parseMessageRecord(raw: Object): MessageWithParts {
    const obj = raw as Record<string, Object>
    if (obj['info']) {
      return obj as MessageWithParts
    }
    return {
      info: obj as any,
      parts: []
    }
  }

  private authHeadersWithAccept(): Record<string, string> {
    return {
      ...this.authHeaders(),
      'Accept': 'application/json'
    }
  }

  private authHeaders(): Record<string, string> {
    const headers: Record<string, string> = {}
    const auth = this.basicAuth()
    if (auth) {
      headers['Authorization'] = auth
    }
    return headers
  }

  private authHeadersWithJson(): Record<string, string> {
    return {
      ...this.authHeaders(),
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    }
  }

  private basicAuth(): string | undefined {
    if (!this.config.username || !this.config.password) {
      return undefined
    }
    const token = this.base64Encode(`${this.config.username}:${this.config.password}`)
    return `Basic ${token}`
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
}
