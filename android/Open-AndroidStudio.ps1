$ErrorActionPreference = 'Stop'
$studioExe = 'C:/Program Files/Android/Android Studio/bin/studio64.exe'
if (-not (Test-Path -LiteralPath $studioExe)) { throw 'Android Studio was not found in Program Files.' }
$profileFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'android-studio.properties'
if (Test-Path -LiteralPath $profileFile) { $env:STUDIO_PROPERTIES = $profileFile }
Start-Process -FilePath $studioExe -ArgumentList ('"' + $PSScriptRoot + '"') -WindowStyle Normal
