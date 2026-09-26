Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-TelegramOperation {
    param([Parameter(Mandatory)][ValidateSet('getMe','setWebhook','getWebhookInfo','deleteWebhook','sendMessage')][string]$Method,
          [hashtable]$Body = @{})
    if ([string]::IsNullOrWhiteSpace($env:TELEGRAM_BOT_TOKEN)) { throw 'TELEGRAM_BOT_TOKEN is required in the process environment.' }
    try {
        $response = Invoke-RestMethod -Method Post -Uri ('https://api.telegram.org/bot' + $env:TELEGRAM_BOT_TOKEN + '/' + $Method) `
            -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5 -Compress) -TimeoutSec 60 -MaximumRedirection 0
        if (-not $response.ok) { throw 'API failure' }
        return $response.result
    } catch { throw "Telegram $Method failed; private response and request details withheld." }
}

function Protect-TelegramText {
    param([string]$Text)
    foreach ($name in @('TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','DB_PASSWORD','ADMIN_BOOTSTRAP_PASSWORD','PHONE_IDENTITY_HMAC_KEY')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrEmpty($value)) { $Text = $Text.Replace($value, '<redacted>') }
    }
    $Text = $Text -replace '[0-9]{5,}:[A-Za-z0-9_-]{15,}', '<redacted>'
    return ($Text -replace '[\r\n\x00-\x1f]', ' ')
}

function Show-TelegramWebhookInfo {
    $info = Invoke-TelegramOperation getWebhookInfo
    $safeUrl = '(not configured)'
    if ($info.url) {
        try {
            $uri = [uri]$info.url
            # Only show origin and path. Query strings/userinfo/fragments may contain credentials.
            $safeUrl = Protect-TelegramText ($uri.GetLeftPart([System.UriPartial]::Authority) -replace '://[^/@]+@','://')
            $safeUrl += Protect-TelegramText $uri.AbsolutePath
        } catch { $safeUrl = '(URL withheld: invalid format)' }
    }
    Write-Output "Configured URL: $safeUrl"
    Write-Output "Pending updates: $($info.pending_update_count)"
    if ($info.PSObject.Properties.Name -contains 'last_error_date') { Write-Output "Last error Unix timestamp: $($info.last_error_date)" }
    if ($info.PSObject.Properties.Name -contains 'last_error_message') {
        Write-Output ('Last error: ' + (Protect-TelegramText $info.last_error_message))
    }
}
