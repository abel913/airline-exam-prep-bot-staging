. (Join-Path $PSScriptRoot 'telegram-common.ps1')
$recipient = 0L
if (-not [long]::TryParse($env:TELEGRAM_ADMIN_ID, [ref]$recipient) -or $recipient -le 0) {
    throw 'A positive TELEGRAM_ADMIN_ID is required in the process environment.'
}
$null = Invoke-TelegramOperation sendMessage @{
    chat_id = $recipient
    text = 'Production test admin notification.'
}
Write-Output 'Test notification accepted by Telegram. Confirm delivery in the admin chat.'
