# Makes ONE local test account that has unlimited credits ON YOUR PC ONLY (local database, billing off).
# Use a made-up email such as tester@example.invalid and a made-up password of 16+ characters.
. "$PSScriptRoot\_level3_env.ps1"
Assert-SetupDone
Push-Location $BackendDir
try { & $VenvPython -m scripts.bootstrap_owner } finally { Pop-Location }
