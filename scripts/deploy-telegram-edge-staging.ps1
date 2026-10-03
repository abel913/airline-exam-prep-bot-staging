[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$InformationPreference = 'SilentlyContinue'

$projectName = 'airline-exam-bot-staging'
$projectRef = 'tyciufbhpbjztqsiirgw'
$functionName = 'telegram-webhook'
$requiredSecretNames = @(
  'TELEGRAM_BOT_TOKEN',
  'TELEGRAM_WEBHOOK_SECRET',
  'PHONE_IDENTITY_HMAC_KEY',
  'DATABASE_URL'
)
$sourceNames = @(
  'TELEGRAM_BOT_TOKEN',
  'TELEGRAM_WEBHOOK_SECRET',
  'PHONE_IDENTITY_HMAC_KEY',
  'DB_HOST',
  'DB_USERNAME',
  'DB_PASSWORD',
  'DB_NAME'
)

$secretsConfigured = $false
$functionDeployed = $false
$secretValues = @{}
$temporarySecretDirectory = $null
$failure = $false
$failureStage = 'initialization'

function ConvertFrom-CliJson {
  param([Parameter(Mandatory = $true)][string[]]$Lines)

  $jsonText = $Lines -join [Environment]::NewLine
  if ([string]::IsNullOrWhiteSpace($jsonText)) { throw 'CLI_JSON_EMPTY' }
  return ConvertFrom-Json -InputObject $jsonText -ErrorAction Stop
}

function Get-CliRows {
  param([Parameter(Mandatory)]$Object, [Parameter(Mandatory)][string[]]$CollectionNames)
  if ($Object -is [array]) { return @($Object) }
  foreach ($collectionName in $CollectionNames) {
    $collection = Get-CliProperty -Object $Object -Name $collectionName
    if ($null -ne $collection) { return @($collection) }
  }
  $data = Get-CliProperty -Object $Object -Name 'data'
  if ($null -ne $data) {
    if ($data -is [array]) { return @($data) }
    foreach ($collectionName in $CollectionNames) {
      $collection = Get-CliProperty -Object $data -Name $collectionName
      if ($null -ne $collection) { return @($collection) }
    }
  }
  return @($Object)
}

function Get-CliProperty {
  param(
    [Parameter(Mandatory = $true)]$Object,
    [Parameter(Mandatory = $true)][string]$Name
  )

  if ($null -eq $Object) { return $null }
  $property = $Object.PSObject.Properties[$Name]
  if ($null -eq $property) { return $null }
  return $property.Value
}

try {
  # Check both the CLI's local link and config before making any remote call.
  $linkPath = Join-Path $PSScriptRoot '..\supabase\.temp\project-ref'
  if (-not (Test-Path -LiteralPath $linkPath -PathType Leaf)) { throw 'PROJECT_NOT_LINKED' }
  $linkedRef = (Get-Content -LiteralPath $linkPath -Raw -ErrorAction Stop).Trim()
  if ($linkedRef -cne $projectRef) { throw 'WRONG_LINKED_PROJECT' }

  $configPath = Join-Path $PSScriptRoot '..\supabase\config.toml'
  if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'PROJECT_CONFIG_MISSING' }
  $configText = Get-Content -LiteralPath $configPath -Raw -ErrorAction Stop
  if ($configText -notmatch '(?m)^\s*project_id\s*=\s*["'']tyciufbhpbjztqsiirgw["'']\s*$') {
    throw 'WRONG_CONFIGURED_PROJECT'
  }

  # Confirm that the authenticated CLI resolves this ref to the expected project name.
  $failureStage = 'project-list-result-detection'
  $projectOutput = & npx supabase projects list --output json 2>$null
  $cliExitCode = $LASTEXITCODE
  if ($cliExitCode -ne 0) { throw 'PROJECT_LIST_FAILED' }
  $projectJson = ConvertFrom-CliJson -Lines @($projectOutput)
  $projectRows = Get-CliRows -Object $projectJson -CollectionNames @('projects')
  $matchingProjects = @($projectRows | Where-Object {
    $idValue = Get-CliProperty -Object $_ -Name 'id'
    $refValue = Get-CliProperty -Object $_ -Name 'ref'
    $nameValue = Get-CliProperty -Object $_ -Name 'name'
    $rowRef = if ($idValue) { [string]$idValue } elseif ($refValue) { [string]$refValue } else { '' }
    $rowName = if ($nameValue) { [string]$nameValue } else { '' }
    $rowRef -ceq $projectRef -and $rowName -ceq $projectName
  })
  if ($matchingProjects.Count -ne 1) { throw 'PROJECT_IDENTITY_MISMATCH' }

  # Read only the approved staging keys. Values remain in memory and are never written to output.
  $envPath = Join-Path $PSScriptRoot '..\.env'
  if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) { throw 'STAGING_ENV_MISSING' }
  $envLines = Get-Content -LiteralPath $envPath -ErrorAction Stop
  foreach ($line in $envLines) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
      $key = $Matches[1]
      if ($sourceNames -ccontains $key) {
        if ($secretValues.ContainsKey($key)) { throw 'DUPLICATE_ENV_KEY' }
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and
            (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
             ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
          $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace($value)) { throw 'EMPTY_ENV_VALUE' }
        $secretValues[$key] = $value
      }
    }
  }
  foreach ($name in $sourceNames) {
    if (-not $secretValues.ContainsKey($name)) { throw 'REQUIRED_ENV_KEY_MISSING' }
  }

  # A Supabase shared pooler identifies the project in the username, not the shared hostname.
  $dbHost = [string]$secretValues['DB_HOST']
  $dbUsername = [string]$secretValues['DB_USERNAME']
  if ($dbHost -notmatch '^[A-Za-z0-9.-]+\.pooler\.supabase\.com$' -or
      -not $dbUsername.EndsWith(".$projectRef", [StringComparison]::Ordinal)) {
    throw 'DATABASE_PROJECT_IDENTITY_MISMATCH'
  }

  $telegramToken = [string]$secretValues['TELEGRAM_BOT_TOKEN']
  $webhookSecret = [string]$secretValues['TELEGRAM_WEBHOOK_SECRET']
  $phoneKey = [string]$secretValues['PHONE_IDENTITY_HMAC_KEY']
  if ($webhookSecret -notmatch '^[A-Za-z0-9_-]{1,256}$') { throw 'WEBHOOK_SECRET_INVALID' }
  if ($telegramToken -match '\s') { throw 'TELEGRAM_TOKEN_INVALID' }
  if ($phoneKey -notmatch '^[A-Za-z0-9+/]+={0,2}$') { throw 'PHONE_KEY_INVALID' }
  try {
    $phoneKeyBytes = [Convert]::FromBase64String($phoneKey)
    if ($phoneKeyBytes.Length -lt 32) { throw 'PHONE_KEY_INVALID' }
  } catch {
    throw 'PHONE_KEY_INVALID'
  }

  $escapedUsername = [Uri]::EscapeDataString($dbUsername)
  $escapedPassword = [Uri]::EscapeDataString([string]$secretValues['DB_PASSWORD'])
  $escapedDatabase = [Uri]::EscapeDataString([string]$secretValues['DB_NAME'])
  $databaseUrl = 'postgresql://{0}:{1}@{2}:6543/{3}?sslmode=require' -f `
    $escapedUsername, $escapedPassword, $dbHost, $escapedDatabase
  $databaseUri = [Uri]$databaseUrl
  if ($databaseUri.Port -ne 6543 -or $databaseUri.Scheme -cne 'postgresql') {
    throw 'DATABASE_URL_INVALID'
  }

  # The temporary env file is restricted to this Windows user and contains only the four Edge secrets.
  $temporarySecretDirectory = Join-Path ([IO.Path]::GetTempPath()) ('telegram-edge-' + [Guid]::NewGuid().ToString('N'))
  [void][IO.Directory]::CreateDirectory($temporarySecretDirectory)
  $directorySecurity = New-Object System.Security.AccessControl.DirectorySecurity
  $directorySecurity.SetAccessRuleProtection($true, $false)
  $currentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $directoryRule = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $currentUserSid,
    [System.Security.AccessControl.FileSystemRights]::FullControl,
    ([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),
    [System.Security.AccessControl.PropagationFlags]::None,
    [System.Security.AccessControl.AccessControlType]::Allow
  )
  [void]$directorySecurity.AddAccessRule($directoryRule)
  [IO.Directory]::SetAccessControl($temporarySecretDirectory, $directorySecurity)

  $temporarySecretFile = Join-Path $temporarySecretDirectory 'edge-secrets.env'
  $secretFileLines = @(
    "TELEGRAM_BOT_TOKEN=$telegramToken"
    "TELEGRAM_WEBHOOK_SECRET=$webhookSecret"
    "PHONE_IDENTITY_HMAC_KEY=$phoneKey"
    "DATABASE_URL=$databaseUrl"
  )
  [IO.File]::WriteAllLines($temporarySecretFile, $secretFileLines, (New-Object System.Text.UTF8Encoding($false)))
  $secretFileLines = $null

  # Explicit ref prevents the CLI's active link/profile from redirecting this write elsewhere.
  $failureStage = 'secret-update'
  & npx supabase secrets set --env-file $temporarySecretFile --project-ref $projectRef 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'SECRET_UPDATE_FAILED' }
  $secretsConfigured = $true

  # Deploy one function only, through the Supabase API, with the custom Telegram header auth.
  $failureStage = 'function-deploy'
  & npx supabase functions deploy $functionName --project-ref $projectRef --use-api --no-verify-jwt 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'FUNCTION_DEPLOY_FAILED' }
  $functionDeployed = $true

  $failureStage = 'function-list-result-detection'
  $functionOutput = & npx supabase functions list --project-ref $projectRef --output json 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'FUNCTION_LIST_FAILED' }
  $functionJson = ConvertFrom-CliJson -Lines @($functionOutput)
  $functionRows = Get-CliRows -Object $functionJson -CollectionNames @('functions')
  $deployedFunction = @($functionRows | Where-Object {
    $nameValue = Get-CliProperty -Object $_ -Name 'name'
    $slugValue = Get-CliProperty -Object $_ -Name 'slug'
    $candidate = if ($nameValue) { [string]$nameValue } elseif ($slugValue) { [string]$slugValue } else { '' }
    $statusValue = Get-CliProperty -Object $_ -Name 'status'
    $candidate -ceq $functionName -and (!$statusValue -or [string]$statusValue -ceq 'ACTIVE')
  })
  if ($deployedFunction.Count -lt 1) { throw 'FUNCTION_NOT_LISTED' }

  # CLI output is captured and inspected in memory; only required secret names are compared.
  $failureStage = 'secret-list-result-detection'
  $secretOutput = & npx supabase secrets list --project-ref $projectRef --output json 2>$null
  if ($LASTEXITCODE -ne 0) { throw 'SECRET_LIST_FAILED' }
  $secretJson = ConvertFrom-CliJson -Lines @($secretOutput)
  $secretRows = Get-CliRows -Object $secretJson -CollectionNames @('secrets')
  $listedSecretNames = @($secretRows | ForEach-Object {
    if ($_ -is [string]) { [string]$_ }
    else {
      $nameValue = Get-CliProperty -Object $_ -Name 'name'
      $keyValue = Get-CliProperty -Object $_ -Name 'key'
      if ($nameValue) { [string]$nameValue } elseif ($keyValue) { [string]$keyValue }
    }
  })
  foreach ($name in $requiredSecretNames) {
    if ($listedSecretNames -cnotcontains $name) { throw 'REQUIRED_SECRET_NOT_LISTED' }
  }
} catch {
  $failure = $true
  $failureType = $_.Exception.GetType().Name
} finally {
  if ($temporarySecretDirectory -and (Test-Path -LiteralPath $temporarySecretDirectory)) {
    Remove-Item -LiteralPath $temporarySecretDirectory -Recurse -Force -ErrorAction SilentlyContinue
  }
  if ($secretValues) { $secretValues.Clear() }
  $telegramToken = $null
  $webhookSecret = $null
  $phoneKey = $null
  $phoneKeyBytes = $null
  $databaseUrl = $null
  $dbUsername = $null
  $dbHost = $null
  $escapedUsername = $null
  $escapedPassword = $null
  $escapedDatabase = $null
}

if ($failure) {
  $status = 'FAIL'
} else {
  $status = 'PASS'
}
$secretStatus = if ($secretsConfigured) { 'YES' } else { 'NO' }
$functionStatus = if ($functionDeployed) { 'YES' } else { 'NO' }
Write-Output "STAGING EDGE DEPLOYMENT: $status"
Write-Output "Project: $projectName ($projectRef)"
Write-Output "Function: $functionName"
Write-Output "Secrets configured: $secretStatus"
Write-Output "Function deployed: $functionStatus"
Write-Output 'Telegram webhook changed: NO'
if ($failure) {
  Write-Output "Failure stage: $failureStage"
  Write-Output "Failure category: $failureType"
}

if ($failure) { exit 1 }
exit 0
