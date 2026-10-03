[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'staging-telegram-common.ps1')
if ($script:StagingProjectRef -cne 'tyciufbhpbjztqsiirgw' -or $script:StagingEdgeUrl -cne 'https://tyciufbhpbjztqsiirgw.supabase.co/functions/v1/telegram-webhook') {
  throw 'Staging project identity or function URL mismatch.'
}
$secrets = Read-StagingEnv -Names @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET')
$token = [string]$secrets['TELEGRAM_BOT_TOKEN']
$webhookSecret = [string]$secrets['TELEGRAM_WEBHOOK_SECRET']
if ($webhookSecret -cnotmatch '^[A-Za-z0-9_-]{1,256}$') { throw 'Staging webhook secret format is invalid.' }

Assert-TestBotIdentity -Token $token
$before = Invoke-TestBotTelegram -Token $token -Method 'getWebhookInfo'
Write-Output ('Current test bot webhook: ' + (Get-SafeTelegramWebhookUrl -WebhookInfo $before.result -Secrets $secrets))
Write-Output "Rollback URL: $script:StagingRenderUrl"
if ([string]$before.result.url -cne $script:StagingRenderUrl) {
  throw 'Current webhook is not the expected staging Render URL; no webhook change was made.'
}

$body = @{ url = $script:StagingEdgeUrl; secret_token = $webhookSecret; allowed_updates = @('message','callback_query'); max_connections = 1; drop_pending_updates = $false }
$changed = Invoke-TestBotTelegram -Token $token -Method 'setWebhook' -Body $body
if (-not $changed.ok -or $changed.result -ne $true) { throw 'Telegram did not confirm webhook configuration.' }
Assert-TestBotIdentity -Token $token
$after = Invoke-TestBotTelegram -Token $token -Method 'getWebhookInfo'
$actualUrl = [string]$after.result.url
if ($actualUrl -cne $script:StagingEdgeUrl) { throw 'Post-change webhook URL verification failed.' }
Write-Output 'Active webhook count: 1 (Telegram bot API maintains one webhook per bot)'
Write-Output ('Final test bot webhook: ' + (Get-SafeTelegramWebhookUrl -WebhookInfo $after.result -Secrets $secrets))
Write-Output 'Staging test bot switched successfully.'
