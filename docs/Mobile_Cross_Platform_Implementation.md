# Mobile Cross-Platform Implementation

Last updated: 2026-03-04

## Branch Strategy

- `feature/android-client` created from `master`
- `feature/harmony-client` created from `feature/android-client`
- Current implementation lives on `feature/harmony-client`

## Implemented in this change

### Shared contract (`mobile_contract/`)

- API endpoint snapshot
- SSE event semantics and reducer rules
- JSON schema baseline:
  - health, session, message, part, project, provider, agent, todo, sse_event
- Golden vectors:
  - streaming delta behavior
  - provider payload variants
  - message payload shape variants
  - session switch stale-response race

### Android (`android_client/`)

- Multi-module Gradle structure (`app` + `core`)
- `core`:
  - wire models and variant parsers
  - HTTP API client for key OpenCode endpoints
  - SSE stream parser with reconnect backoff
  - reducer-style `AppStateStore` with side effects
  - unit tests wired to golden vectors
- `app`:
  - Compose app with functional Chat/Files/Settings tabs
  - session chips + create/rename/delete session
  - message send/abort + message part rendering + streaming delta display
  - permission cards (allow once/always/reject)
  - session-scoped draft/model/agent memory
  - session todo panel and session busy status chips
  - file tree loading + file content preview
  - connection/settings form + project selection
  - built-in SSH tunnel (password or key-auth local port forwarding to VPS remote port)
  - ViewModel orchestration with full refresh + SSE side-effect reloads

### Local environment validation (Windows)

- Android Studio path (from registry): `E:\Program Files\Android\Android Studio`
- JDK path used for build: `E:\Program Files\Android\Android Studio\jbr`
- Android SDK path used for build: `E:\Android\Sdk`
- Gradle wrapper added under `android_client/` (copied from a local Android project template)

Verified commands:

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd android_client
.\gradlew.bat :core:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
```

Outputs:
- Unit tests: `BUILD SUCCESSFUL`
- APK: `android_client/app/build/outputs/apk/debug/app-debug.apk`

Helper script:

- `scripts/run_android_checks.ps1` (runs unit tests + apk assemble + lint)
- `scripts/run_harmony_checks.ps1` (runs hvigor build when wrapper exists)
- `scripts/verify_mobile_contract.ps1` (validates contract file completeness and JSON parseability)

### Harmony (`harmony_client/`)

- Stage-model project scaffolding
- ArkTS model, API client, and state reducer expanded to session/message/file/permission scopes
- Added `AppController` orchestration for refresh/session/message/file actions
- Added V1 polling fallback (3s) for status/message sync when SSE transport adapter is unavailable
- `SseClient` now attempts streaming SSE via fetch readable stream, with fallback to polling
- ArkUI entry page upgraded from shell to interactive 3-tab app:
  - Chat: session list/create/rename/delete, send/abort, permission actions
  - Files: path load, node navigation, preview panel
  - Settings: server/apply/refresh, project picker
- External tunnel mode explicitly noted for V1

## Not completed yet

- Android SSH key persistence in secure storage (V1 supports key auth input but does not persist key material)
- Harmony built-in SSH tunnel (deferred by agreed plan)
- Harmony end-to-end device test reports / hap build output (DevEco hvigor wrapper not available in current environment)
