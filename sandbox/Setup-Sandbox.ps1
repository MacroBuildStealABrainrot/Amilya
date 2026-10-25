# One-time setup. Installs Python packages into sandbox\.venv. Touches nothing outside this project folder.
. "$PSScriptRoot\_common.ps1"
$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw 'Python 3.11 or newer is needed. Install it from python.org (tick "Add python.exe to PATH"), then run this again.' }
$version = & python -c "import sys;print('%d.%d' % sys.version_info[:2])"
if ([version]$version -lt [version]'3.11') { throw "Python $version found; 3.11 or newer is needed." }
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue) -or -not (Get-Command ffprobe -ErrorAction SilentlyContinue)) {
    Write-Host 'ffmpeg/ffprobe not found. Install with: winget install Gyan.FFmpeg   then open a NEW PowerShell window. (Needed for audio checks in Level 3.)' -ForegroundColor Yellow
}
if (-not (Test-Path -LiteralPath $VenvPython)) { & python -m venv (Join-Path $SandboxDir '.venv') }
& $VenvPython -m pip install --upgrade pip
& $VenvPython -m pip install -r (Join-Path $BackendDir 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Python packages could not be installed.' }
Write-Host 'Setup finished.' -ForegroundColor Green
