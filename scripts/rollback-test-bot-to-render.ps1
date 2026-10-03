[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'staging-telegram-common.ps1')
$secrets = Read-StagingEnv -Names @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET')
$token = [string]$secrets['TELEGRAM_BOT_TOKEN']
$webhookSecret = [string]$secrets['TELEGRAM_WEBHOOK_SECRET']
if ($webhookSecret -cnotmatch '^[A-Za-z0-9_-]{1,256}$') { throw 'Staging webhook secret format is invalid.' }

Assert-TestBotIdentity -Token $token
$before = Invoke-TestBotTelegram -Token $token -Method 'getWebhookInfo'
Write-Output ('Current test bot webhook: ' + (Get-SafeTelegramWebhookUrl -WebhookInfo $before.result -Secrets $secrets))
$changed = Invoke-TestBotTelegram -Token $token -Method 'setWebhook' -Body @{
  url = $script:StagingRenderUrl; secret_token = $webhookSecret; allowed_updates = @('message','callback_query'); max_connections = 1; drop_pending_updates = $false
}
if (-not $changed.ok -or $changed.result -ne $true) { throw 'Telegram did not confirm webhook rollback.' }
Assert-TestBotIdentity -Token $token
$after = Invoke-TestBotTelegram -Token $token -Method 'getWebhookInfo'
if ([string]$after.result.url -cne $script:StagingRenderUrl) { throw 'Post-rollback webhook URL verification failed.' }
Write-Output "Rollback webhook restored: $script:StagingRenderUrl"
Write-Output 'Active webhook count: 1 (Telegram bot API maintains one webhook per bot)'
