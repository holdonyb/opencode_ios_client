Param(
    [string]$Root = "E:\Work\opencode_mobile\opencode_ios_client"
)

$ErrorActionPreference = "Stop"

$contract = Join-Path $Root "mobile_contract"
if (-not (Test-Path $contract)) {
    throw "mobile_contract not found: $contract"
}

$requiredFiles = @(
    "openapi_snapshot.json",
    "event_types.md",
    "model_schema\health.schema.json",
    "model_schema\session.schema.json",
    "model_schema\message.schema.json",
    "model_schema\part.schema.json",
    "model_schema\project.schema.json",
    "model_schema\provider.schema.json",
    "model_schema\agent.schema.json",
    "model_schema\todo.schema.json",
    "model_schema\sse_event.schema.json",
    "golden\streaming_delta.json",
    "golden\provider_variants.json",
    "golden\message_payload_variants.json",
    "golden\session_switch_race.json"
)

foreach ($rel in $requiredFiles) {
    $path = Join-Path $contract $rel
    if (-not (Test-Path $path)) {
        throw "Missing contract file: $rel"
    }
}

Get-ChildItem -Path (Join-Path $contract "model_schema") -Filter *.json | ForEach-Object {
    Get-Content $_.FullName -Raw | ConvertFrom-Json | Out-Null
}

Get-ChildItem -Path (Join-Path $contract "golden") -Filter *.json | ForEach-Object {
    Get-Content $_.FullName -Raw | ConvertFrom-Json | Out-Null
}

Write-Host "mobile_contract verification passed." -ForegroundColor Green

