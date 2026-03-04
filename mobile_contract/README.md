# Mobile Contract Baseline

This folder is the single source of truth for OpenCode mobile clients.

It is shared by:
- `android_client` (Kotlin + Compose)
- `harmony_client` (ArkTS + ArkUI)

## Contents

- `openapi_snapshot.json`: endpoint snapshot used by mobile apps
- `event_types.md`: SSE event semantics and reducer rules
- `model_schema/`: JSON Schema files for portable DTOs
- `golden/`: cross-platform golden vectors for parsing and state transitions

## Rules

1. Any wire format change must update this folder first.
2. Android and Harmony must both pass the same golden vectors.
3. Unknown fields must be ignored by clients (forward compatibility).
4. Variant payloads (array vs map, field aliases) must keep working.

