param([int]$Port = 8766)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Choose a local port between 1024 and 65535.' }
$taskPython = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) {
    python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 or newer is required.' }
    & $taskPython -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Python dependencies could not be installed.' }
}
if (-not (Test-Path -LiteralPath '.env')) {
    $taskSecretBytes = New-Object byte[] 48
    $taskGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskGenerator.GetBytes($taskSecretBytes) } finally { $taskGenerator.Dispose() }
    $taskSecret = [Convert]::ToBase64String($taskSecretBytes)
    $taskEnvironment = "APP_SECRET=$taskSecret`nENVIRONMENT=development`nPUBLIC_BASE_URL=http://127.0.0.1:$Port`nDATABASE_URL=sqlite:///./work/woundcopilot.db`nPROVIDER_EXECUTION_ENABLED=false`nCLINICAL_DATA_ENABLED=false`nBILLING_ENABLED=false`n"
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $taskEnvironment, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Created local synthetic-evaluation settings. Optional services remain paused.'
}
if (-not (Test-Path -LiteralPath 'frontend/dist/index.html')) {
    Push-Location frontend
    try {
        npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependencies could not be installed.' }
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
$env:WOUNDCOPILOT_ENV_FILE = Join-Path $PSScriptRoot '.env'
$env:PUBLIC_BASE_URL = "http://127.0.0.1:$Port"
& $taskPython -m alembic upgrade head
if ($LASTEXITCODE -ne 0) { throw 'Database migration failed; application was not started.' }
Write-Host "Open http://127.0.0.1:$Port — press Ctrl+C here to stop."
& $taskPython -m uvicorn server.main:app --host 127.0.0.1 --port $Port --no-access-log
