Param(
    [string]$JavaHome = "E:\Program Files\Android\Android Studio\jbr",
    [string]$ProjectDir = "E:\Work\opencode_mobile\opencode_ios_client\android_client"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $JavaHome)) {
    throw "JAVA_HOME path not found: $JavaHome"
}

if (-not (Test-Path $ProjectDir)) {
    throw "Project dir not found: $ProjectDir"
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"

Push-Location $ProjectDir
try {
    .\gradlew.bat :core:testDebugUnitTest --no-daemon
    .\gradlew.bat :app:assembleDebug --no-daemon
    .\gradlew.bat :app:lintDebug --no-daemon
} finally {
    Pop-Location
}

