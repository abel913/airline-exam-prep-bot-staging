# Offline Telegram helper checks. Only fictional values and a mocked HTTP function.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$saved = @{}
foreach ($name in @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','TELEGRAM_ADMIN_ID')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
$operationCalls = [System.Collections.Generic.List[object]]::new()
function Assert-Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Invoke-RestMethod {
    param($Method, $Uri, $ContentType, $Body, $TimeoutSec, $MaximumRedirection)
    $operation = ([uri]$Uri).Segments[-1]
    $operationCalls.Add(@{ Operation=$operation; Body=($Body | ConvertFrom-Json) })
    if ($operation -eq 'getMe') { return @{ok=$true;result=@{is_bot=$true;username='FictionalTestBot'}} }
    if ($operation -eq 'setWebhook') { return @{ok=$true;result=$true} }
    return @{ok=$true;result=[pscustomobject]@{
        url='https://test.invalid/api/telegram/webhook?token=fictional-token';pending_update_count=2
        last_error_date=1;last_error_message=('fictional error ' + $env:TELEGRAM_BOT_TOKEN + ' ' + $env:TELEGRAM_WEBHOOK_SECRET)
    }}
}
try {
    $env:TELEGRAM_BOT_TOKEN='123:fictional-token-for-offline-check'
    $env:TELEGRAM_WEBHOOK_SECRET='fictional-webhook-secret'
    $output = & (Join-Path $PSScriptRoot 'configure-telegram-webhook.ps1') -PublicUrl https://test.invalid
    Assert-Check ($operationCalls.Count -eq 3) 'Expected getMe, setWebhook and getWebhookInfo.'
    $body=$operationCalls[1].Body
    Assert-Check ($body.url -eq 'https://test.invalid/api/telegram/webhook') 'Wrong webhook route.'
    Assert-Check ($body.secret_token -eq $env:TELEGRAM_WEBHOOK_SECRET) 'Secret header setup missing.'
    Assert-Check (-not $body.drop_pending_updates) 'Pending updates must be preserved.'
    Assert-Check ($body.max_connections -eq 1) 'Expected one delivery connection.'
    Assert-Check (($body.allowed_updates -join ',') -eq 'message,callback_query') 'Wrong allowed updates.'
    Assert-Check (-not (($output -join '') -match 'fictional-token|fictional-webhook-secret|\?token=')) 'Helper output leaked private values.'
    $env:TELEGRAM_ADMIN_ID='123'
    $null = & (Join-Path $PSScriptRoot 'send-test-admin-notification.ps1')
    Assert-Check ($operationCalls[3].Operation -eq 'sendMessage') 'Notification API missing.'
    Assert-Check ($operationCalls[3].Body.text -ceq 'Production test admin notification.') 'Unexpected notification text.'
    Assert-Check ($operationCalls[3].Body.chat_id -eq 123) 'Wrong notification destination.'
    $failed=$false
    try { & (Join-Path $PSScriptRoot 'configure-telegram-webhook.ps1') -PublicUrl http://test.invalid | Out-Null } catch { $failed=$true }
    Assert-Check $failed 'HTTP origin must be rejected.'
    $failed=$false
    try { & (Join-Path $PSScriptRoot 'configure-telegram-webhook.ps1') -PublicUrl 'https://test.invalid/?token=unsafe' | Out-Null } catch { $failed=$true }
    Assert-Check $failed 'URL query must be rejected.'
    $env:TELEGRAM_BOT_TOKEN=''
    $failed=$false
    try { & (Join-Path $PSScriptRoot 'check-telegram-webhook.ps1') | Out-Null } catch { $failed=$true }
    Assert-Check $failed 'Missing token must fail.'
    foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
        $tokens=$null;$errors=$null
        $null=[System.Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
        Assert-Check ($errors.Count -eq 0) ('PowerShell syntax error: ' + $file.Name)
    }
    Write-Output 'Operations helper checks PASS (offline; no live Telegram call).'
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name]) }
}
