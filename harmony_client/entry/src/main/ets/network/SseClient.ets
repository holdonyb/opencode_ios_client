import { SseEvent } from '../model/Models'

export type SseEventListener = (event: SseEvent) => void
export type SseErrorListener = (error: Error) => void

export class SseClient {
  private baseUrl: string
  private authHeader?: string
  private running: boolean = false

  constructor(baseUrl: string, authHeader?: string) {
    this.baseUrl = baseUrl
    this.authHeader = authHeader
  }

  // HarmonyOS NEXT note:
  // This V1 client keeps SSE handling abstract so transport can be swapped
  // between fetch-stream and platform HTTP stream APIs.
  async connect(onEvent: SseEventListener, onError: SseErrorListener): Promise<void> {
    this.running = true
    let retries: number = 0

    while (this.running) {
      try {
        await this.connectOnce(onEvent)
        retries = 0
      } catch (e) {
        const err = e as Error
        onError(err)
        retries += 1
        const delayMs = this.backoffMillis(retries)
        await this.sleep(delayMs)
      }
    }
  }

  stop(): void {
    this.running = false
  }

  private async connectOnce(onEvent: SseEventListener): Promise<void> {
    const headers: Record<string, string> = {
      'Accept': 'text/event-stream',
      'Cache-Control': 'no-cache'
    }
    if (this.authHeader) {
      headers['Authorization'] = this.authHeader
    }

    const response = await fetch(`${this.baseUrl}/global/event`, {
      method: 'GET',
      headers: headers
    })
    if (!response.ok) {
      throw new Error(`SSE HTTP ${response.status}`)
    }

    const responseAny = response as Object as Record<string, Object>
    const body = responseAny['body'] as Record<string, Object>
    const canStream = body && typeof (body['getReader']) === 'function'
    if (!canStream) {
      throw new Error('SSE streaming body is unavailable in current runtime')
    }

    const reader = (body['getReader'] as () => Object)() as Record<string, Object>
    const read = reader['read'] as () => Promise<Record<string, Object>>
    const decoder = new TextDecoder()
    let buffered = ''

    while (this.running) {
      const result = await read()
      const done = result['done'] as boolean
      const value = result['value'] as Uint8Array
      if (done) {
        break
      }
      if (!value) {
        continue
      }
      buffered += decoder.decode(value, { stream: true })
      const split = buffered.split('\n')
      buffered = split.pop() ?? ''
      const events = this.parseSseLines(split)
      events.forEach((evt) => onEvent(evt))
    }

    if (buffered.length > 0) {
      const events = this.parseSseLines(buffered.split('\n'))
      events.forEach((evt) => onEvent(evt))
    }
  }

  parseSseLines(lines: Array<string>): Array<SseEvent> {
    const out: Array<SseEvent> = []
    let dataLines: Array<string> = []

    const flush = (): void => {
      if (dataLines.length === 0) return
      const payload = dataLines.join('\n').trim()
      dataLines = []
      if (payload.length === 0 || payload === '[DONE]') return
      try {
        const parsed = JSON.parse(payload) as SseEvent
        out.push(parsed)
      } catch (_ignore) {
      }
    }

    lines.forEach((raw) => {
      const line = raw.endsWith('\r') ? raw.substring(0, raw.length - 1) : raw
      if (line.length === 0) {
        flush()
      } else if (line.startsWith(':')) {
        // keep-alive/comment
      } else if (line.startsWith('data:')) {
        dataLines.push(line.substring(5).trimStart())
      }
    })

    flush()
    return out
  }

  private backoffMillis(retries: number): number {
    const capped = Math.min(retries, 5)
    const power = Math.max(capped - 1, 0)
    return Math.min(30000, 1000 * Math.pow(2, power))
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => {
      setTimeout(() => resolve(), ms)
    })
  }
}
