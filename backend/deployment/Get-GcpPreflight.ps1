# Read-only inventory. No API enablement, billing linkage, secret access, or deployment.
[CmdletBinding()]
param(
  [ValidatePattern('^[a-z][a-z0-9-]{4,28}[a-z0-9]$')][string]$Project = 'woundcopilot-20260905',
  [ValidatePattern('^([A-Fa-f0-9]{6}-[A-Fa-f0-9]{6}-[A-Fa-f0-9]{6})?$')][string]$BillingAccount = '',
  [string]$GcloudPath = 'gcloud',
  [string]$OutputPath = 'deployment/preflight.local.json'
)
$ErrorActionPreference = 'Stop'
$cli = Get-Command $GcloudPath -ErrorAction Stop
$checks = [System.Collections.Generic.List[object]]::new()
function Read-Gcloud([string]$Name, [string[]]$Arguments, [string]$Fields = '') {
  $formatArg = if ($Fields) { "--format=json($Fields)" } else { '--format=json' }
  $cliArgs = @($Arguments) + @('--quiet', $formatArg)
  $raw = & $cli.Source @cliArgs 2>$null
  if ($LASTEXITCODE -ne 0) {
    $checks.Add(@{name=$Name;status='unavailable';reason='Permission, disabled API, or CLI failure; this is not a passed check'})
    return $null
  }
  try { $data = ($raw -join "`n") | ConvertFrom-Json }
  catch { $checks.Add(@{name=$Name;status='unavailable';reason='Unexpected response format'}); return $null }
  $checks.Add(@{name=$Name;status='read';data=$data})
  return $data
}
$null = Read-Gcloud 'target_project' @('projects','describe',$Project)
$null = Read-Gcloud 'target_billing_link' @('billing','projects','describe',$Project)
$scope = @($Project)
if ($BillingAccount) {
  $linked = Read-Gcloud 'billing_account_projects' @('billing','projects','list',"--billing-account=$BillingAccount")
  $scope = @(@($Project) + @($linked | ForEach-Object { $_.projectId }) | Where-Object { $_ } | Sort-Object -Unique)
}
$since = (Get-Date).ToUniversalTime().ToString('yyyy-MM-01T00:00:00Z')
foreach ($projectId in $scope) {
  $projectArg = "--project=$projectId"
  $null = Read-Gcloud "$projectId/enabled_apis" @('services','list','--enabled',$projectArg) 'config.name'
  $null = Read-Gcloud "$projectId/instances" @('compute','instances','list',$projectArg) 'name,zone,machineType,status,creationTimestamp,disks,networkInterfaces'
  $null = Read-Gcloud "$projectId/disks" @('compute','disks','list',$projectArg)
  $null = Read-Gcloud "$projectId/external_addresses" @('compute','addresses','list',$projectArg)
  $null = Read-Gcloud "$projectId/cloud_run" @('run','services','list','--platform=managed',$projectArg) 'metadata.name,metadata.namespace,status.url,spec.template.spec.containerConcurrency,spec.template.spec.containers.resources'
  $repos = Read-Gcloud "$projectId/repositories" @('artifacts','repositories','list',$projectArg)
  foreach ($repo in $repos) {
    if ($repo.format -ne 'DOCKER') { continue }
    $parts = $repo.name -split '/'
    if ($parts.Count -ne 6) { continue }
    $imagePath = "$($parts[3])-docker.pkg.dev/$projectId/$($parts[5])"
    $null = Read-Gcloud "$projectId/images/$($parts[3])/$($parts[5])" @('artifacts','docker','images','list',$imagePath,'--include-tags',$projectArg)
  }
  $secrets = Read-Gcloud "$projectId/secret_metadata" @('secrets','list',$projectArg) 'name,replication,createTime'
  foreach ($secret in $secrets) {
    $secretId = ($secret.name -split '/')[-1]
    $null = Read-Gcloud "$projectId/secret_versions/$secretId" @('secrets','versions','list',$secretId,$projectArg) 'name,state,createTime,destroyTime'
  }
  foreach ($buildRegion in @('global','us-central1')) {
    $null = Read-Gcloud "$projectId/builds/$buildRegion" @('builds','list',"--region=$buildRegion","--filter=createTime>=$since",$projectArg) 'id,status,createTime,startTime,finishTime,options.machineType'
  }
  $null = Read-Gcloud "$projectId/buckets" @('storage','buckets','list',$projectArg)
}
$report = @{
  created_at=(Get-Date).ToUniversalTime().ToString('o'); status='INVENTORY_ONLY'; mutations=0; secret_values_accessed=0
  project=$Project; billing_scope_requested=[bool]$BillingAccount; projects=$scope; checks=$checks
  zero_cost_verified=$false
  manual_checks=@('Review month-to-date and forecast usage by SKU across the complete billing account',
    'Repository image totals may count shared layers more than once; reconcile actual billed storage',
    'Inspect build locations beyond global/us-central1 and retained source/build-log buckets',
    'Enabled AND disabled secret versions consume the free active-version allowance',
    'Storage listing is not a backup-size or operation-count check; confirm restore capacity and costs',
    'Incomplete permissions or disabled APIs are unresolved, never zero usage',
    'Budget alerts and delayed service spend caps cannot guarantee a zero invoice')
}
$absoluteOutput = [IO.Path]::GetFullPath($OutputPath)
$null = New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($absoluteOutput))
$report | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $absoluteOutput -Encoding utf8
Write-Output "Read-only inventory saved to $absoluteOutput. Zero-cost eligibility remains unverified."
