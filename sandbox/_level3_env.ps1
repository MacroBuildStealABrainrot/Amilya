# Builds the Level 3 settings. Safety values are forced here and cannot be overridden by level3.env.
. "$PSScriptRoot\_common.ps1"
$envFile = Join-Path $SandboxDir 'level3.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Copy level3.env.example to level3.env and add your own AI keys first.' }
$workDir = Join-Path $SandboxDir 'work'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$secretFile = Join-Path $workDir 'level3-secret.txt'
if (-not (Test-Path -LiteralPath $secretFile)) {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [System.IO.File]::WriteAllText($secretFile, [Convert]::ToBase64String($bytes))
}
if (-not ((Get-Content -LiteralPath $envFile) -match '^OPENROUTER_API_KEY=.+')) { Write-Host 'Note: OPENROUTER_API_KEY is empty in level3.env, so report writing will not work.' -ForegroundColor Yellow }
$env:WOUNDCOPILOT_ENV_FILE = $envFile
$env:APP_SECRET = (Get-Content -LiteralPath $secretFile -Raw).Trim()
$env:ENVIRONMENT = 'development'
$env:DATABASE_URL = 'sqlite:///' + ((Join-Path $workDir 'level3-test.db') -replace '\\', '/')
$env:PUBLIC_BASE_URL = 'http://127.0.0.1:18766'
$env:TRUSTED_HOSTS = '127.0.0.1,localhost'
$env:SECURE_COOKIES = 'false'
$env:ALLOW_SIGNUP = 'true'
$env:AUTO_CREATE_SCHEMA = 'false'
$env:BILLING_ENABLED = 'false'
$env:STRIPE_SECRET_KEY = ''
$env:CLINICAL_DATA_ENABLED = 'false'     # fake data only
$env:PROVIDER_EXECUTION_ENABLED = 'true'  # the only thing Level 3 turns on: real AI for fake data
Push-Location $BackendDir
try {
    & $VenvPython -m alembic upgrade head
    if ($LASTEXITCODE -ne 0) { throw 'Test database setup failed.' }
} finally { Pop-Location }
