Param(
    [string]$ProjectDir = "E:\Work\opencode_mobile\opencode_ios_client\harmony_client"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ProjectDir)) {
    throw "Project dir not found: $ProjectDir"
}

Push-Location $ProjectDir
try {
    if (Test-Path ".\hvigorw.bat") {
        .\hvigorw.bat --mode module -p product=default assembleHap
    } else {
        Write-Host "hvigorw.bat not found. Open the project in DevEco Studio and run Build > Build Hap(s)/APP(s)." -ForegroundColor Yellow
    }
} finally {
    Pop-Location
}

