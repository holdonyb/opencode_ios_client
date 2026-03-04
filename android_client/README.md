# OpenCode Android Client

Android implementation of the OpenCode mobile client.

## Modules

- `app`: Android UI (Jetpack Compose)
- `core`: API, SSE, reducer/state, contract adapters

## Scope in this implementation

- Contract-aligned wire models
- HTTP client and SSE stream parser
- Session/message reducer baseline
- Compose app shell with Chat / Files / Settings tabs
- Golden-vector tests wired to `../mobile_contract/golden`

## Build (when Android toolchain is installed)

```bash
cd android_client
./gradlew :core:testDebugUnitTest
./gradlew :app:assembleDebug
```

