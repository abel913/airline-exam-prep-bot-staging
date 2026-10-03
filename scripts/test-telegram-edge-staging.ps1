[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'staging-telegram-common.ps1')

$expectedRef = 'tyciufbhpbjztqsiirgw'
$edgeUrl = 'https://tyciufbhpbjztqsiirgw.supabase.co/functions/v1/telegram-webhook'
if ($expectedRef -cne $script:StagingProjectRef -or $edgeUrl -cne $script:StagingEdgeUrl) { throw 'Staging project identity or URL mismatch.' }
$secrets = Read-StagingEnv -Names @('TELEGRAM_WEBHOOK_SECRET')
$correctSecret = [string]$secrets['TELEGRAM_WEBHOOK_SECRET']
if ($correctSecret -cnotmatch '^[A-Za-z0-9_-]{1,256}$') { throw 'Local staging webhook secret has invalid format.' }

function Invoke-EdgeProbe {
  param([string]$Label, [hashtable]$Headers, [string]$Body)
  try {
    $response = Invoke-WebRequest -Method Post -Uri $edgeUrl -Headers $Headers -ContentType 'application/json' -Body $Body `
      -TimeoutSec 30 -MaximumRedirection 0 -SkipHttpErrorCheck
    return [int]$response.StatusCode
  } catch { Write-Output "$Label request failed at transport level."; return 0 }
}

$missingStatus = Invoke-EdgeProbe -Label 'Missing-secret' -Headers @{} -Body '{"update_id":0}'
$wrongStatus = Invoke-EdgeProbe -Label 'Wrong-secret' -Headers @{ 'X-Telegram-Bot-Api-Secret-Token' = 'clearly-wrong-staging-secret' } -Body '{"update_id":0}'
# Deliberately malformed JSON is harmless and reaches the handler only after authentication/config checks.
$validStatus = Invoke-EdgeProbe -Label 'Correct-secret' -Headers @{ 'X-Telegram-Bot-Api-Secret-Token' = $correctSecret } -Body '{'
$missingPass = $missingStatus -eq 403
$wrongPass = $wrongStatus -eq 403
$validPass = $validStatus -eq 400
Write-Output "Project ref verified: $expectedRef"
Write-Output "Function URL: $edgeUrl"
Write-Output "Missing secret rejected: $(if ($missingPass) {'YES (403)'} else {"NO (HTTP $missingStatus)"})"
Write-Output "Wrong secret rejected: $(if ($wrongPass) {'YES (403)'} else {"NO (HTTP $wrongStatus)"})"
Write-Output "Correct secret reached handler: $(if ($validPass) {'YES (400 malformed JSON)'} else {"NO (HTTP $validStatus)"})"

# GET is intentionally unauthenticated in this function and performs only Postgres healthCheck(); it makes no writes.
try {
  $health = Invoke-WebRequest -Method Get -Uri $edgeUrl -TimeoutSec 30 -MaximumRedirection 0 -SkipHttpErrorCheck
  $dbPass = [int]$health.StatusCode -eq 200
} catch { $dbPass = $false; $health = $null }
Write-Output "Staging PostgreSQL health check: $(if ($dbPass) {'PASS (HTTP 200)'} elseif ($health) {"FAIL (HTTP $($health.StatusCode))"} else {'BLOCKED (transport error)'})"

# Spring Actuator's overall health includes its JDBC database indicator while withholding component details.
$renderHealthUrl = 'https://airline-exam-prep-bot-staging.onrender.com/actuator/health'
try {
  $renderResponse = Invoke-WebRequest -Method Get -Uri $renderHealthUrl -TimeoutSec 120 -MaximumRedirection 0 -SkipHttpErrorCheck
  $renderBody = [Text.Encoding]::UTF8.GetString([byte[]]$renderResponse.Content)
  $renderHealth = ConvertFrom-Json -InputObject $renderBody -ErrorAction Stop
  $renderDbPass = [int]$renderResponse.StatusCode -eq 200 -and [string]$renderHealth.status -ceq 'UP'
  if ($renderDbPass) { Write-Output 'Staging Render/Spring JDBC health: PASS (Actuator UP)' }
  else { Write-Output "Staging Render/Spring JDBC health: FAIL (HTTP $([int]$renderResponse.StatusCode))" }
} catch {
  $renderDbPass = $false
  Write-Output "Staging Render/Spring JDBC health: UNAVAILABLE ($($_.Exception.GetType().Name))"
}

$cli = Get-Command supabase -ErrorAction SilentlyContinue
if (-not $cli) { Write-Output 'Edge secret names: BLOCKED (Supabase CLI unavailable locally)' }
else {
  $raw = & supabase secrets list --project-ref $expectedRef --output json 2>$null
  $exitCode = $LASTEXITCODE
  $listedNames = @()
  if ($exitCode -eq 0) {
    try {
      $parsed = ConvertFrom-Json -InputObject ($raw -join [Environment]::NewLine)
      $rows = if ($parsed -is [array]) { @($parsed) } elseif ($parsed.secrets) { @($parsed.secrets) } elseif ($parsed.data) { @($parsed.data) } else { @($parsed) }
      $listedNames = @($rows | ForEach-Object { if ($_ -is [string]) { $_ } elseif ($_.name) { [string]$_.name } elseif ($_.key) { [string]$_.key } })
    } catch { $exitCode = 1 }
  }
  foreach ($name in @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','PHONE_IDENTITY_HMAC_KEY','DATABASE_URL')) {
    Write-Output "Edge secret $name`: $(if ($listedNames -ccontains $name) {'PRESENT'} else {'MISSING/UNVERIFIED'})"
  }
}

if (-not ($missingPass -and $wrongPass -and $validPass -and $dbPass -and $renderDbPass)) { exit 1 }
