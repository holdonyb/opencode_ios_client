# SSE Event Semantics

## Envelope

Each SSE `data:` payload is JSON:

```json
{
  "directory": "/abs/path/or/global",
  "payload": {
    "type": "message.part.updated",
    "properties": {}
  }
}
```

## Event Handling Rules

1. Ignore unknown event types.
2. Ignore unknown properties within known events.
3. Process events idempotently when possible.
4. Apply session-scoped events only to matching `sessionID`.
5. Prefer "running evidence" over transient `session.status=idle`.

## Known Types

- `server.connected`
  - Action: trigger one full sync (`/session/status`, current session messages, todos, permissions).

- `session.status`
  - Required fields: `sessionID`, `status`
  - Reducer:
    - Update `sessionStatuses[sessionID]`.
    - If status becomes non-busy and no running evidence remains, clear streaming draft state.

- `session.updated`
  - Action: upsert a session by id.
  - Must respect selected project filter except currently opened session.

- `session.deleted`
  - Action: remove session and clear session-scoped caches.

- `message.updated`
  - Action: reload current session messages and diffs.

- `message.part.updated`
  - Fields: `sessionID`, `part`, optional `delta`
  - Reducer:
    - if `delta` exists: append to in-memory part text using key `messageID:partID`
    - else: fallback to full message reload.

- `permission.asked`
  - Action: append pending permission if not duplicated.

- `permission.replied`
  - Action: remove from pending permissions.

- `todo.updated`
  - Fields: `sessionID`, `todos`
  - Action: replace todo list cache for that session.

## Reconnect Strategy

1. SSE disconnect triggers exponential backoff reconnect.
2. Backoff: 1s, 2s, 4s, 8s, 16s, max 30s.
3. On reconnect success:
  - fetch latest session statuses
  - fetch current session messages
  - rebuild in-memory running evidence.

