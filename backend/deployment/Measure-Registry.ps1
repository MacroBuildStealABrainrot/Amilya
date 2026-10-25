param([string]$Project = 'woundcopilot-20260905', [string]$Location = 'us-central1', [string]$Repository = 'wcp')
$ErrorActionPreference = 'Stop'
# Read-only inventory. A file is counted once, including blobs shared by revisions.
$taskRaw = gcloud artifacts files list --project=$Project --location=$Location --repository=$Repository --format=json
if ($LASTEXITCODE -ne 0) { throw 'Registry inventory failed; usage is unknown.' }
$taskFiles = $taskRaw | ConvertFrom-Json | Sort-Object name -Unique
$taskBytes = ($taskFiles | Measure-Object -Property sizeBytes -Sum).Sum
[pscustomobject]@{
    project = $Project
    repository = $Repository
    files = @($taskFiles).Count
    retainedBytes = $taskBytes
    retainedMiB = [math]::Round($taskBytes / 1MB, 3)
    billingAccountWide = $false
    note = 'Reconcile other repositories/projects and monthly byte-time usage against the shared Free Tier. This is a current inventory, not an invoice or spending cap.'
} | ConvertTo-Json
