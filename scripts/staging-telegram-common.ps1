Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:StagingProjectRef = 'tyciufbhpbjztqsiirgw'
$script:StagingEdgeUrl = 'https://tyciufbhpbjztqsiirgw.supabase.co/functions/v1/telegram-webhook'
$script:StagingRenderUrl = 'https://airline-exam-prep-bot-staging.onrender.com/api/telegram/webhook'
$script:ExpectedTestBot = 'EthiopianAirlineExamTestBot'

function Read-StagingEnv {
  param([Parameter(Mandatory)][string[]]$Names)
  $path = Join-Path $PSScriptRoot '..\.env'
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Staging .env file is missing.' }
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $path) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
      $key = $Matches[1]
      if ($Names -ccontains $key) {
        if ($values.ContainsKey($key)) { throw "Duplicate .env key: $key" }
        $value = $Matches[2]
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
          $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Empty .env key: $key" }
        $values[$key] = $value
      }
    }
  }
  foreach ($name in $Names) { if (-not $values.ContainsKey($name)) { throw "Missing .env key: $name" } }
  return $values
}

function Invoke-TestBotTelegram {
  param([Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Method, [hashtable]$Body = @{})
  try {
    return Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$Token/$Method" -ContentType 'application/json' `
      -Body ($Body | ConvertTo-Json -Depth 6 -Compress) -TimeoutSec 30 -MaximumRedirection 0
  } catch {
    $httpStatus = 'none'
    try { $httpStatus = [string][int]$_.Exception.Response.StatusCode } catch { }
    $errorType = $_.Exception.GetType().Name
    throw "Telegram $Method request failed (HTTP $httpStatus; $errorType); private response details withheld."
  }
}

function Protect-StagingText {
  param([string]$Text, [hashtable]$Secrets = @{})
  foreach ($name in @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','PHONE_IDENTITY_HMAC_KEY','DB_PASSWORD','DATABASE_URL')) {
    if ($Secrets.ContainsKey($name) -and -not [string]::IsNullOrEmpty([string]$Secrets[$name])) {
      $Text = $Text.Replace([string]$Secrets[$name], '<redacted>')
    }
  }
  return (($Text -replace '[\r\n\x00-\x1f]', ' ') -replace '[0-9]{5,}:[A-Za-z0-9_-]{15,}', '<redacted>')
}

function Get-SafeTelegramWebhookUrl {
  param($WebhookInfo, [hashtable]$Secrets = @{})
  if (-not $WebhookInfo.url) { return '(not configured)' }
  try {
    $uri = [uri]$WebhookInfo.url
    return Protect-StagingText ($uri.GetLeftPart([System.UriPartial]::Authority) + $uri.AbsolutePath) $Secrets
  } catch { return '(invalid URL withheld)' }
}

function Assert-TestBotIdentity {
  param([Parameter(Mandatory)][string]$Token)
  $response = Invoke-TestBotTelegram -Token $Token -Method 'getMe'
  if (-not $response.ok -or -not $response.result.is_bot -or $response.result.username -cne $script:ExpectedTestBot) {
    throw 'Bot identity check failed; no webhook change was made.'
  }
  Write-Output "Verified Telegram identity: @$($script:ExpectedTestBot)"
}
