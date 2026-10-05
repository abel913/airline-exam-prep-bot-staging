[CmdletBinding()]
param(
    [ValidateSet('Phase4AndRemoval','RemovalOnly')]
    [string]$PostgresTestSet = 'Phase4AndRemoval',
    [switch]$JavaOnly,
    [ValidateSet('airline_exam_bot_phase5_test','airline_exam_bot_phase5_v20_fresh_test','airline_exam_bot_phase5_v20_final_test','airline_exam_bot_phase5_v20_verify_test')]
    [string]$DatabaseName = 'airline_exam_bot_phase5_v20_verify_test'
)

$ErrorActionPreference = 'Stop'
$dbName = $DatabaseName
$runner = 'airline_exam_bot_phase5_runner'
$adminDb = 'postgres'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testFile = Join-Path $root 'supabase/functions/telegram-webhook/tests/phase4.postgres.test.ts'
$practiceTestFile = Join-Path $root 'supabase/functions/telegram-webhook/tests/practice.postgres.test.ts'
$mockTestFile = Join-Path $root 'supabase/functions/telegram-webhook/tests/mock.postgres.test.ts'
$javaTests = @(
    (Join-Path $root 'src/test/java/com/airlineprep/bot/payment/Phase4PostgresIT.java'),
    (Join-Path $root 'src/test/java/com/airlineprep/bot/payment/PaymentPostgresIT.java'),
    (Join-Path $root 'src/test/java/com/airlineprep/bot/admin/RegisteredUserRemovalPostgresIT.java')
)
$oldEnv = @{}
$envNames = @('PGPASSWORD','PGHOST','PGPORT','PGUSER','PGDATABASE','DATABASE_URL','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_FLYWAY_URL','SPRING_FLYWAY_USER','SPRING_FLYWAY_PASSWORD','SPRING_APPLICATION_JSON','SPRING_PROFILES_ACTIVE','FLYWAY_URL','FLYWAY_USER','FLYWAY_PASSWORD','EDGE_TEST_DATABASE_URL','PHASE4_PG_HOST','PHASE4_PG_DATABASE','PHASE4_PG_TEST_USER','PHASE4_PG_TEST_PASSWORD','DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','TELEGRAM_BOT_TOKEN','TELEGRAM_WEBHOOK_SECRET','PHONE_IDENTITY_HMAC_KEY','TELEGRAM_ADMIN_ID')
$pgPassword = $null
$runnerPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$dbUrl = $null
$dataApiRoot = 'http://127.0.0.1'
$psql = $null
$deno = $null
$maven = Join-Path $root 'mvnw.cmd'
$targetRef = 'tyciufbhpbjztqsiirgw'
$failure = $null
$failureStage = 'initialization'
$failureLine = 0
$testsPassed = $false

function Stop-Safely([string]$Message) { throw [InvalidOperationException]::new($Message) }

function Protect-ProcessText([string]$Text) {
    $safe = [string]$Text
    foreach ($sensitive in @($script:pgPassword, $script:runnerPassword, $script:dbUrl)) {
        if (-not [string]::IsNullOrEmpty([string]$sensitive)) { $safe = $safe.Replace([string]$sensitive, '[REDACTED]') }
    }
    return [regex]::Replace($safe, '(?i)(postgres(?:ql)?://)[^@\s]+@', '$1[REDACTED_CREDENTIALS]@')
}

function Invoke-LocalPsql([string]$Database, [string]$Sql, [switch]$Quiet) {
    if ($Database -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') { Stop-Safely 'Refusing an invalid local database identifier.' }
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:psql
    # ProcessStartInfo.ArgumentList is unavailable in Windows PowerShell 5.1
    # (.NET Framework), where it is null and .Add() throws the reported error.
    # Each interpolated value is a validated identifier or numeric loopback port.
    $psi.Arguments = "-X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $script:port -U $script:adminUser -d $Database -A -t"
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $psi
    if (-not $process.Start()) { Stop-Safely 'Could not start the local PostgreSQL client.' }
    $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd(); $err = $process.StandardError.ReadToEnd(); $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        # psql diagnostics can contain the submitted SQL (including generated
        # credentials during role provisioning). Surface only a sanitized
        # PostgreSQL diagnostic class/message, never raw stdout/stderr.
        $diagnostic = 'PostgreSQL reported an unspecified local error.'
        if ($err -match '(?im)(?:FATAL|ERROR|PANIC):\s*(?<message>[^\r\n]+)') {
            $message = $Matches.message.Trim()
            $message = [regex]::Replace($message, "'[^']*'", "'[value]'" )
            $message = [regex]::Replace($message, '(?i)(password|token|secret|credential)\s*[:=]?\s*\S+', '$1 [REDACTED]')
            if ($message.Length -gt 240) { $message = $message.Substring(0, 240) }
            $diagnostic = "PostgreSQL $($Matches[0].Split(':')[0]) [sanitized]: $message"
        } elseif ($err -match '(?i)no password supplied|password authentication failed|could not translate host name|connection refused') {
            $diagnostic = [regex]::Match($err, '(?i)no password supplied|password authentication failed|could not translate host name|connection refused').Value
        }
        Stop-Safely "Local PostgreSQL operation failed (exit $($process.ExitCode)); $diagnostic"
    }
    return $output.Trim()
}

try {
    $failureStage = 'environment isolation'
    foreach ($name in $envNames) {
        $oldEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $failureStage = 'staging project allowlist'
    $config = Join-Path $root 'supabase/config.toml'
    if (-not (Test-Path -LiteralPath $config -PathType Leaf) -or
        (Get-Content -Raw -LiteralPath $config) -notmatch '(?m)^project_id\s*=\s*"tyciufbhpbjztqsiirgw"\s*$') {
        Stop-Safely 'Repository Supabase project reference does not match the staging allowlist.'
    }
    $failureStage = 'Flyway migration inventory'
    if (-not (Test-Path -LiteralPath $testFile) -or -not (Test-Path -LiteralPath $practiceTestFile) -or
        -not (Test-Path -LiteralPath $mockTestFile) -or
        @($javaTests | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -gt 0) { Stop-Safely 'Phase 5 integration test source is missing.' }
    $sqlMigrations = @(Get-ChildItem (Join-Path $root 'src/main/resources/db/migration') -Filter 'V*__*.sql' -File |
        ForEach-Object { if ($_.Name -match '^V([0-9]+)__') { [int]$Matches[1] } } | Sort-Object -Unique)
    $javaMigrations = @(Get-ChildItem (Join-Path $root 'src/main/java/db/migration') -Filter 'V*__*.java' -File |
        ForEach-Object { if ($_.Name -match '^V([0-9]+)__') { [int]$Matches[1] } } | Sort-Object -Unique)
    if (($sqlMigrations + $javaMigrations | Sort-Object -Unique).Count -ne 20 -or
        ($sqlMigrations + $javaMigrations | Sort-Object -Unique)[-1] -ne 20 -or
        16 -notin $javaMigrations -or 17 -notin $javaMigrations -or 18 -notin $javaMigrations -or 19 -notin $javaMigrations) { Stop-Safely 'Expected the complete SQL and Java Flyway migration set V1–V20.' }

    $failureStage = 'local PostgreSQL service and client discovery'
    $service = Get-Service -Name 'postgresql-x64-18' -ErrorAction SilentlyContinue
    if (-not $service -or $service.Status -ne 'Running') { Stop-Safely 'Local PostgreSQL 18 service is not running.' }
    $port = 5432
    if ($env:PHASE4_PG_PORT) {
        if ($env:PHASE4_PG_PORT -notmatch '^\d{1,5}$' -or [int]$env:PHASE4_PG_PORT -lt 1 -or [int]$env:PHASE4_PG_PORT -gt 65535) { Stop-Safely 'PHASE4_PG_PORT must be a valid local TCP port.' }
        $port = [int]$env:PHASE4_PG_PORT
    }
    if ($port -ne 5432) { Stop-Safely 'Phase 4 local database tests are restricted to port 5432.' }
    $psqlCandidate = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($psqlCandidate) { $psql = $psqlCandidate.Source }
    else {
        $pgBin = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
        if (Test-Path -LiteralPath $pgBin) { $psql = $pgBin } else { Stop-Safely 'PostgreSQL psql client is not installed.' }
    }
    $isReady = Join-Path (Split-Path $psql) 'pg_isready.exe'
    & $isReady -h 127.0.0.1 -p $port *> $null
    if ($LASTEXITCODE -ne 0) { Stop-Safely 'PostgreSQL is not accepting connections on the configured loopback port.' }

    $failureStage = 'local PostgreSQL authentication'
    $adminUser = $env:PHASE4_PG_ADMIN_USER
    if (-not $adminUser) { $adminUser = Read-Host 'Local PostgreSQL admin role (usually postgres)' }
    if ($adminUser -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') { Stop-Safely 'PHASE4_PG_ADMIN_USER must be a simple PostgreSQL role name.' }
    $pgPassword = $env:PHASE4_PG_ADMIN_PASSWORD
    if (-not $pgPassword) {
        $secure = Read-Host 'Local PostgreSQL admin password (input hidden)' -AsSecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $pgPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    if (-not $pgPassword) { Stop-Safely 'Local PostgreSQL admin credentials are required.' }
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $pgPassword, 'Process')
    Write-Output "Verified target project ref: $targetRef (local tests only; no hosted database connection)."
    Write-Output "Local PostgreSQL service: $($service.Name); host 127.0.0.1; port $port."

    $failureStage = 'dedicated test-role verification/provisioning'
    $roleExists = Invoke-LocalPsql $adminDb "SELECT COUNT(*) FROM pg_roles WHERE rolname='$runner';" -Quiet
    if ($roleExists -eq '0') {
        $pwLiteral = $runnerPassword.Replace("'", "''")
        Invoke-LocalPsql $adminDb "CREATE ROLE $runner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$pwLiteral';" -Quiet | Out-Null
    } else {
        $roleSafe = Invoke-LocalPsql $adminDb "SELECT (NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication) FROM pg_roles WHERE rolname='$runner';" -Quiet
        if ($roleSafe -ne 't') { Stop-Safely 'Existing Phase 4 test role is not a restricted non-superuser role.' }
        $pwLiteral = $runnerPassword.Replace("'", "''")
        Invoke-LocalPsql $adminDb "ALTER ROLE $runner PASSWORD '$pwLiteral';" -Quiet | Out-Null
    }
    $failureStage = 'dedicated test-database existence and ownership verification'
    $dbExists = Invoke-LocalPsql $adminDb "SELECT COUNT(*) FROM pg_database WHERE datname='$dbName';" -Quiet
    if ($dbExists -eq '0') { Invoke-LocalPsql $adminDb "CREATE DATABASE $dbName OWNER $runner;" -Quiet | Out-Null }
    $dbOwner = Invoke-LocalPsql $adminDb "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$dbName';" -Quiet
    if ($dbOwner -cne $runner) { Stop-Safely 'The named Phase 5 database exists but is not owned by the dedicated test role; refusing to use or modify it.' }
    $publicTables = Invoke-LocalPsql $dbName "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history');" -Quiet
    if ([int]$publicTables -gt 0) {
        $hasFlyway = Invoke-LocalPsql $dbName "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history';" -Quiet
        if ($hasFlyway -ne '1') { Stop-Safely 'The existing named test database contains tables but no Flyway history; refusing to overwrite unknown data.' }
        $version = Invoke-LocalPsql $dbName "SELECT COALESCE(MAX(version::int),0) FROM flyway_schema_history WHERE success;" -Quiet
        if ([int]$version -gt 20) { Stop-Safely 'The isolated test database has a migration newer than V20.' }
        if ([int]$version -notin @(17,18,19,20)) { Stop-Safely 'The isolated test database must be at Flyway V17, V18, V19, or V20 before synthetic cleanup is considered.' }

        # The integration fixtures are rooted only by their exact synthetic exam
        # markers. Deno uses phase4- plus eight hex digits; Spring uses the fixed
        # phase4test code. Related rows are removed only through those exam/user/
        # question/payment relationships and the exact fixture author strings.
        # Counts are emitted before deletes. All changes are one local transaction.
        $failureStage = 'counting and removing exact integration synthetic fixtures'
        $cleanupSql = @'
BEGIN;
CREATE TEMP TABLE _p4_exam_ids AS
  SELECT id FROM exam_types
  WHERE (code='phase4test' AND name='Synthetic Phase 4 Exam')
     OR (code ~ '^phase4-[0-9a-f]{8}$' AND name='Synthetic Phase 4 Test Exam')
     OR (code ~ '^edge-[0-9a-f]{8}$' AND name='Synthetic Edge Test Exam');
CREATE TEMP TABLE _p4_user_ids AS
  SELECT u.id FROM bot_users u JOIN _p4_exam_ids e ON e.id=u.selected_exam_type_id
  WHERE u.registration_status='COMPLETED' AND u.phone_identity_hash ~ '^[0-9a-f]{64}$'
    AND EXISTS (SELECT 1 FROM access_entitlements ae WHERE ae.user_id=u.id AND ae.grant_source='REGISTRATION');
CREATE TEMP TABLE _p4_question_ids AS
  SELECT DISTINCT q.id FROM questions q JOIN question_versions v ON v.question_id=q.id
  JOIN _p4_exam_ids e ON e.id=v.exam_type_id
  WHERE (q.created_by IN ('phase4-test','phase4-local-test')
    AND v.created_by IN ('phase4-test','phase4-local-test')
    AND ((v.source_title='Synthetic Phase 4 test' AND v.exam_name='Synthetic Phase 4 Test Exam')
      OR (v.source_title='Synthetic local integration fixture' AND v.exam_name='Synthetic Phase 4 Exam')))
    OR (q.created_by='edge-test' AND v.created_by='edge-test'
      AND v.source_title='Synthetic Edge fixtures' AND v.exam_name='Synthetic Edge Test Exam'
      AND v.exam_type_id IN (SELECT id FROM _p4_exam_ids));
CREATE TEMP TABLE _p4_attempt_ids AS
  SELECT id FROM mock_attempts WHERE user_id IN (SELECT id FROM _p4_user_ids);
CREATE TEMP TABLE _p4_request_ids AS
  SELECT id FROM payment_requests WHERE user_id IN (SELECT id FROM _p4_user_ids);
CREATE TEMP TABLE _p4_delivery_ids AS
  SELECT id FROM practice_deliveries WHERE user_id IN (SELECT id FROM _p4_user_ids);
CREATE TEMP TABLE _p4_method_ids AS
  SELECT id FROM payment_methods WHERE
    (display_name='STAGING TEST ONLY' AND account_name='TEST ONLY' AND destination='NO REAL DESTINATION'
      AND instructions IN ('Synthetic local test instructions','Synthetic test instructions'));
SELECT 'PHASE4_PRECOUNT|exam_types|' || count(*) FROM _p4_exam_ids;
SELECT 'PHASE4_PRECOUNT|categories|' || count(*) FROM categories WHERE exam_type_id IN (SELECT id FROM _p4_exam_ids) AND ((code='mock-a' AND name IN ('Synthetic Category A','Synthetic Mock Category A')) OR (code='mock-b' AND name IN ('Synthetic Category B','Synthetic Mock Category B')));
SELECT 'PHASE4_PRECOUNT|bot_users|' || count(*) FROM _p4_user_ids;
SELECT 'PHASE4_PRECOUNT|access_entitlements|' || count(*) FROM access_entitlements WHERE user_id IN (SELECT id FROM _p4_user_ids);
SELECT 'PHASE4_PRECOUNT|questions|' || count(*) FROM _p4_question_ids;
SELECT 'PHASE4_PRECOUNT|question_versions|' || count(*) FROM question_versions WHERE question_id IN (SELECT id FROM _p4_question_ids);
SELECT 'PHASE4_PRECOUNT|question_options|' || count(*) FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id IN (SELECT id FROM _p4_question_ids));
SELECT 'PHASE4_PRECOUNT|question_import_rows|' || count(*) FROM question_import_rows WHERE question_id IN (SELECT id FROM _p4_question_ids);
SELECT 'PHASE4_PRECOUNT|mock_attempts|' || count(*) FROM mock_attempts WHERE id IN (SELECT id FROM _p4_attempt_ids);
SELECT 'PHASE4_PRECOUNT|mock_items|' || count(*) FROM mock_items WHERE attempt_id IN (SELECT id FROM _p4_attempt_ids);
SELECT 'PHASE4_PRECOUNT|practice_update_receipts|' || count(*) FROM practice_update_receipts WHERE user_id IN (SELECT id FROM _p4_user_ids);
SELECT 'PHASE4_PRECOUNT|practice_sessions|' || count(*) FROM practice_sessions WHERE user_id IN (SELECT id FROM _p4_user_ids);
SELECT 'PHASE4_PRECOUNT|practice_usage|' || count(*) FROM practice_usage WHERE user_id IN (SELECT id FROM _p4_user_ids);
SELECT 'PHASE4_PRECOUNT|practice_deliveries|' || count(*) FROM practice_deliveries WHERE id IN (SELECT id FROM _p4_delivery_ids);
SELECT 'PHASE4_PRECOUNT|payment_requests|' || count(*) FROM payment_requests WHERE id IN (SELECT id FROM _p4_request_ids);
SELECT 'PHASE4_PRECOUNT|payment_notifications|' || count(*) FROM payment_notifications WHERE request_id IN (SELECT id FROM _p4_request_ids);
SELECT 'PHASE4_PRECOUNT|payment_audit_events|' || count(*) FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM _p4_request_ids);
SELECT 'PHASE4_PRECOUNT|lifetime_access_grants|' || count(*) FROM lifetime_access_grants WHERE user_id IN (SELECT id FROM _p4_user_ids);
SELECT 'PHASE4_PRECOUNT|payment_methods_exact_marker|' || count(*) FROM _p4_method_ids;
SELECT 'PHASE5_FK|'||child.relname||'|'||parent.relname||'|'||con.conname
  FROM pg_constraint con
  JOIN pg_class child ON child.oid=con.conrelid
  JOIN pg_namespace child_ns ON child_ns.oid=child.relnamespace
  JOIN pg_class parent ON parent.oid=con.confrelid
  JOIN pg_namespace parent_ns ON parent_ns.oid=parent.relnamespace
 WHERE con.contype='f' AND child_ns.nspname='public' AND parent_ns.nspname='public'
   AND (child.relname IN ('bot_users','access_entitlements','practice_deliveries','practice_usage','practice_sessions',
       'practice_update_receipts','mock_attempts','mock_items','payment_requests','payment_notifications','lifetime_access_grants')
     OR parent.relname IN ('bot_users','access_entitlements','practice_deliveries','practice_usage','practice_sessions',
       'practice_update_receipts','mock_attempts','mock_items','payment_requests','payment_notifications','lifetime_access_grants'))
 ORDER BY parent.relname,child.relname,con.conname;
-- Remove all children of the known test users before the V18 composite
-- entitlement foreign keys are reached. Restrict each operation to IDs rooted
-- in the exact synthetic exam and registration entitlement markers above.
DELETE FROM practice_update_receipts WHERE user_id IN (SELECT id FROM _p4_user_ids);
DELETE FROM practice_sessions WHERE user_id IN (SELECT id FROM _p4_user_ids);
DELETE FROM practice_usage WHERE user_id IN (SELECT id FROM _p4_user_ids);
UPDATE practice_deliveries SET next_delivery_id=NULL,review_delivery_id=NULL
  WHERE id IN (SELECT id FROM _p4_delivery_ids);
DELETE FROM practice_deliveries WHERE id IN (SELECT id FROM _p4_delivery_ids);
DELETE FROM mock_items WHERE attempt_id IN (SELECT id FROM _p4_attempt_ids);
DELETE FROM mock_attempts WHERE id IN (SELECT id FROM _p4_attempt_ids);
DELETE FROM payment_notifications WHERE request_id IN (SELECT id FROM _p4_request_ids);
DELETE FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM _p4_request_ids);
DELETE FROM lifetime_access_grants WHERE user_id IN (SELECT id FROM _p4_user_ids);
DELETE FROM payment_requests WHERE id IN (SELECT id FROM _p4_request_ids);
DELETE FROM payment_methods WHERE id IN (SELECT id FROM _p4_method_ids)
  AND NOT EXISTS (SELECT 1 FROM payment_requests p WHERE p.method_id=payment_methods.id);
DELETE FROM access_entitlements WHERE user_id IN (SELECT id FROM _p4_user_ids);
DELETE FROM bot_users WHERE id IN (SELECT id FROM _p4_user_ids);
DELETE FROM question_import_rows WHERE question_id IN (SELECT id FROM _p4_question_ids);
UPDATE questions SET current_version_id=NULL,status='DRAFT' WHERE id IN (SELECT id FROM _p4_question_ids);
DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id IN (SELECT id FROM _p4_question_ids));
DELETE FROM question_versions WHERE question_id IN (SELECT id FROM _p4_question_ids);
DELETE FROM questions WHERE id IN (SELECT id FROM _p4_question_ids);
DELETE FROM categories WHERE exam_type_id IN (SELECT id FROM _p4_exam_ids);
DELETE FROM exam_types WHERE id IN (SELECT id FROM _p4_exam_ids);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM exam_types WHERE id IN (SELECT id FROM _p4_exam_ids))
    OR EXISTS (SELECT 1 FROM bot_users WHERE id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM access_entitlements WHERE user_id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM practice_update_receipts WHERE user_id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM practice_sessions WHERE user_id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM practice_usage WHERE user_id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM practice_deliveries WHERE id IN (SELECT id FROM _p4_delivery_ids))
    OR EXISTS (SELECT 1 FROM mock_items WHERE attempt_id IN (SELECT id FROM _p4_attempt_ids))
    OR EXISTS (SELECT 1 FROM questions WHERE id IN (SELECT id FROM _p4_question_ids))
    OR EXISTS (SELECT 1 FROM question_versions WHERE question_id IN (SELECT id FROM _p4_question_ids))
    OR EXISTS (SELECT 1 FROM mock_attempts WHERE id IN (SELECT id FROM _p4_attempt_ids))
    OR EXISTS (SELECT 1 FROM payment_notifications WHERE request_id IN (SELECT id FROM _p4_request_ids))
    OR EXISTS (SELECT 1 FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM _p4_request_ids))
    OR EXISTS (SELECT 1 FROM lifetime_access_grants WHERE user_id IN (SELECT id FROM _p4_user_ids))
    OR EXISTS (SELECT 1 FROM payment_requests WHERE id IN (SELECT id FROM _p4_request_ids))
    OR EXISTS (SELECT 1 FROM payment_methods WHERE id IN (SELECT id FROM _p4_method_ids))
  THEN RAISE EXCEPTION 'targeted Phase 4 fixture cleanup verification failed'; END IF;
END $$;
COMMIT;
'@
        if ($cleanupSql -match '(?i)\b(DROP|TRUNCATE|CASCADE)\b') {
            Stop-Safely 'Cleanup SQL contains a prohibited schema/data removal keyword.'
        }
        foreach ($statement in ($cleanupSql -split ';')) {
            $sqlStatement = [regex]::Replace($statement, '(?m)--[^\r\n]*$', '').Trim()
            if ($sqlStatement -match '(?is)^DELETE\s+FROM\b') {
                if ($sqlStatement -notmatch '(?is)\bWHERE\b' -or $sqlStatement -notmatch '(?i)_p4_') {
                    Stop-Safely 'Cleanup SQL contains a DELETE without an exact Phase 4 fixture scope.'
                }
            }
            if ($sqlStatement -match '(?is)^UPDATE\s+\w+') {
                $isSettingsRestore = $sqlStatement -match '(?is)^UPDATE\s+app_settings\b' -and $sqlStatement -match '(?is)\bWHERE\s+id\s*=\s*1\b'
                if ($sqlStatement -notmatch '(?is)\bWHERE\b' -or
                    ($sqlStatement -notmatch '(?i)_p4_' -and -not $isSettingsRestore)) {
                    Stop-Safely 'Cleanup SQL contains an UPDATE without an exact fixture or settings-row scope.'
                }
            }
        }
        $cleanupReport = Invoke-LocalPsql $dbName $cleanupSql -Quiet
        foreach ($line in ($cleanupReport -split "`r?`n")) {
            if ($line -match '^(PHASE4_PRECOUNT|PHASE5_FK)\|') { Write-Output $line }
        }
        $cleanupVerify = @'
SELECT (SELECT COUNT(*) FROM exam_types WHERE (code='phase4test' AND name='Synthetic Phase 4 Exam') OR (code ~ '^phase4-[0-9a-f]{8}$' AND name='Synthetic Phase 4 Test Exam') OR (code ~ '^edge-[0-9a-f]{8}$' AND name='Synthetic Edge Test Exam'))
+ (SELECT COUNT(*) FROM questions q JOIN question_versions v ON v.question_id=q.id WHERE (q.created_by IN ('phase4-test','phase4-local-test') AND v.created_by IN ('phase4-test','phase4-local-test') AND ((v.source_title='Synthetic Phase 4 test' AND v.exam_name='Synthetic Phase 4 Test Exam') OR (v.source_title='Synthetic local integration fixture' AND v.exam_name='Synthetic Phase 4 Exam'))) OR (q.created_by='edge-test' AND v.source_title='Synthetic Edge fixtures' AND v.exam_name='Synthetic Edge Test Exam'))
+ (SELECT COUNT(*) FROM payment_methods WHERE display_name='STAGING TEST ONLY' AND account_name='TEST ONLY' AND destination='NO REAL DESTINATION' AND instructions IN ('Synthetic local test instructions','Synthetic test instructions'));
'@
        $remainingMarkers = Invoke-LocalPsql $dbName $cleanupVerify -Quiet
        if ([int]$remainingMarkers -ne 0) { Stop-Safely 'Known integration synthetic markers remain after targeted cleanup; refusing to continue.' }
        $emptyCheck = @'
DO $$ DECLARE t record; n bigint; BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','app_settings') LOOP
    EXECUTE format('SELECT count(*) FROM public.%I', t.tablename) INTO n;
    IF n <> 0 THEN RAISE EXCEPTION 'test database contains pre-existing rows'; END IF;
  END LOOP;
END $$;
'@
        Invoke-LocalPsql $dbName $emptyCheck -Quiet | Out-Null
    }

    $env:PHASE4_PG_HOST = '127.0.0.1'; $env:PHASE4_PG_PORT = [string]$port
    $env:PHASE4_PG_DATABASE = $dbName; $env:PHASE4_PG_TEST_USER = $runner; $env:PHASE4_PG_TEST_PASSWORD = $runnerPassword
    $env:DB_HOST = '127.0.0.1'; $env:DB_PORT = [string]$port; $env:DB_NAME = $dbName
    $env:DB_USERNAME = $runner; $env:DB_PASSWORD = $runnerPassword
    $edgeDbUriBuilder = [System.UriBuilder]::new('postgresql', '127.0.0.1', $port, "/$dbName")
    $edgeDbUriBuilder.UserName = [System.Uri]::EscapeDataString($runner)
    $edgeDbUriBuilder.Password = [System.Uri]::EscapeDataString($runnerPassword)
    $edgeDbUriBuilder.Query = 'sslmode=disable'
    $edgeDbUri = $edgeDbUriBuilder.Uri
    if ($edgeDbUri.Scheme -ne 'postgresql' -or $edgeDbUri.Host -ne '127.0.0.1' -or
        $edgeDbUri.Port -ne 5432 -or $edgeDbUri.AbsolutePath -cne "/$dbName") {
        Stop-Safely 'Constructed Edge database URL failed the local database target check.'
    }
    $dbUrl = $edgeDbUri.AbsoluteUri
    Write-Output "Validated Deno database target: host=$($edgeDbUri.Host) port=$($edgeDbUri.Port) database=$dbName"
    $env:EDGE_TEST_DATABASE_URL = $dbUrl
    # The admin credential is needed only for the psql provisioning calls above.
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $null, 'Process')

    $failureStage = 'Spring Boot PostgreSQL integration tests and Flyway migrations'
    Write-Output 'Running Spring Boot PostgreSQL integration tests; Spring Boot Flyway applies the repository SQL and Java migrations.'
    Push-Location $root
    try {
        $mavenStartedAt = [DateTime]::UtcNow
        if ($PostgresTestSet -eq 'RemovalOnly') {
            $mavenTestSelector = 'RegisteredUserRemovalPostgresIT'
            $reportNames = @('TEST-com.airlineprep.bot.admin.RegisteredUserRemovalPostgresIT.xml')
        } else {
            $mavenTestSelector = 'Phase4PostgresIT,PaymentPostgresIT,RegisteredUserRemovalPostgresIT'
            $reportNames = @(
                'TEST-com.airlineprep.bot.payment.Phase4PostgresIT.xml',
                'TEST-com.airlineprep.bot.payment.PaymentPostgresIT.xml',
                'TEST-com.airlineprep.bot.admin.RegisteredUserRemovalPostgresIT.xml'
            )
        }
        $previousErrorPreference = $ErrorActionPreference
        try {
            # Windows PowerShell 5.1 can promote native stderr to a terminating
            # NativeCommandError under ErrorActionPreference=Stop. Temporarily
            # continue while capturing streams; the native exit code and fresh
            # Surefire report determine success below.
            $ErrorActionPreference = 'Continue'
            $mvnOutput = @(& $maven -q "-Dtest=$mavenTestSelector" test 2>&1)
            $mvnExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorPreference
        }
        $mavenStdout = [Collections.Generic.List[string]]::new()
        $mavenStderr = [Collections.Generic.List[string]]::new()
        foreach ($entry in $mvnOutput) {
            if ($entry -is [Management.Automation.ErrorRecord]) { $mavenStderr.Add([string]$entry) }
            else { $mavenStdout.Add([string]$entry) }
        }
        foreach ($line in $mavenStdout) {
            if (-not [string]::IsNullOrWhiteSpace($line)) { Write-Output (Protect-ProcessText $line) }
        }
        foreach ($line in $mavenStderr) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $safeLine = Protect-ProcessText $line
            if ($line -match '(?i)\bwarning\b|currently self-attaching') { Write-Output "WARNING: $safeLine" }
            elseif ($mvnExitCode -eq 0) { Write-Output "STDERR (Maven exit code 0): $safeLine" }
            else { Write-Output "MAVEN STDERR: $safeLine" }
        }

        $surefireTests = 0; $surefireFailures = 0; $surefireErrors = 0; $surefireSkipped = 0
        $surefireFailureText = @()
        $freshReports = 0
        foreach ($reportName in $reportNames) {
            $surefirePath = Join-Path (Join-Path $root 'target/surefire-reports') $reportName
            if (-not (Test-Path -LiteralPath $surefirePath -PathType Leaf) -or
                (Get-Item -LiteralPath $surefirePath).LastWriteTimeUtc -lt $mavenStartedAt.AddSeconds(-2)) { continue }
            try {
                [xml]$surefire = Get-Content -Raw -LiteralPath $surefirePath
                $suite = $surefire.testsuite
                $surefireTests += [int]$suite.tests; $surefireFailures += [int]$suite.failures
                $surefireErrors += [int]$suite.errors; $surefireSkipped += [int]$suite.skipped
                $freshReports++
                foreach ($case in @($suite.testcase)) {
                    if ($case.failure) { $surefireFailureText += [string]$case.failure.message }
                    if ($case.error) { $surefireFailureText += [string]$case.error.message }
                }
            } catch {
                Write-Output "WARNING: Fresh Surefire report '$reportName' could not be parsed; Maven exit code remains authoritative for process status."
            }
        }
        if ($freshReports -gt 0) {
            Write-Output "Surefire ($PostgresTestSet PostgreSQL classes): tests=$surefireTests failures=$surefireFailures errors=$surefireErrors skipped=$surefireSkipped"
            foreach ($failureText in $surefireFailureText) { Write-Output "SUREFIRE FAILURE: $(Protect-ProcessText $failureText)" }
        } else { Write-Output 'Surefire: no fresh selected PostgreSQL integration reports found for this Maven invocation.' }

        if ($mvnExitCode -ne 0) {
            Write-Output "ERROR: Maven process failed with exit code $mvnExitCode."
            foreach ($line in @($mavenStdout | Select-Object -Last 25)) { Write-Output "MAVEN OUTPUT: $(Protect-ProcessText $line)" }
            foreach ($line in @($mavenStderr | Select-Object -Last 25)) { Write-Output "MAVEN STDERR: $(Protect-ProcessText $line)" }
            Stop-Safely 'Spring Boot Phase 5 PostgreSQL test or Flyway migration failed; see sanitized Maven/Surefire context above.'
        }
        if ($freshReports -ne $reportNames.Count -or $surefireTests -lt 1 -or $surefireFailures -gt 0 -or $surefireErrors -gt 0) {
            Stop-Safely 'Maven exited successfully but the fresh selected PostgreSQL Surefire reports are absent or contain failed tests.'
        }
        if ($JavaOnly) {
            Write-Output 'Java-only verification requested; skipping Deno Edge tests.'
        } else {
        $denoCommand = Get-Command deno -ErrorAction SilentlyContinue
        if (-not $denoCommand) {
            $winget = Get-Command winget -ErrorAction SilentlyContinue
            if (-not $winget) { Stop-Safely 'Deno is missing and winget is unavailable; install Deno then rerun the local integration helper.' }
            Write-Output 'Installing Deno for the current Windows user to run the Edge PostgreSQL tests.'
            & $winget.Source install --id DenoLand.Deno --exact --scope user --accept-source-agreements --accept-package-agreements --silent *> $null
            if ($LASTEXITCODE -ne 0) { Stop-Safely 'User-scoped Deno installation failed.' }
            $env:Path = "$env:LOCALAPPDATA\Microsoft\WinGet\Links;$env:LOCALAPPDATA\Programs\Deno;$env:Path"
            $denoCommand = Get-Command deno -ErrorAction SilentlyContinue
            if (-not $denoCommand) { Stop-Safely 'Deno installed but is unavailable in this PowerShell session.' }
        }
        $failureStage = 'Deno dependency resolution and local PostgreSQL Edge integration test'
        $denoFiles = @($practiceTestFile, $testFile, $mockTestFile)
        $denoTotalPassed = 0
        $denoTotalFailed = 0
        $denoTotalFiles = 0
        foreach ($denoFile in $denoFiles) {
            $denoName = Split-Path -Leaf $denoFile
            $denoPreviousErrorPreference = $ErrorActionPreference
            try {
                # Run each PostgreSQL test file in its own completed process to
                # avoid Deno's Windows test-worker IPC failure with multi-file runs.
                $ErrorActionPreference = 'Continue'
                $denoOutput = @(& $denoCommand.Source test --allow-env=EDGE_TEST_DATABASE_URL --allow-net=127.0.0.1,localhost $denoFile 2>&1)
                $denoExitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $denoPreviousErrorPreference
            }
            $denoStdout = [Collections.Generic.List[string]]::new()
            $denoStderr = [Collections.Generic.List[string]]::new()
            foreach ($entry in $denoOutput) {
                if ($entry -is [Management.Automation.ErrorRecord]) { $denoStderr.Add([string]$entry) }
                else { $denoStdout.Add([string]$entry) }
            }
            foreach ($line in $denoStdout) {
                if (-not [string]::IsNullOrWhiteSpace($line)) { Write-Output "DENO $denoName`: $(Protect-ProcessText $line)" }
            }
            foreach ($line in $denoStderr) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $safeLine = Protect-ProcessText $line
                if ($line -match '(?i)\bwarning\b') { Write-Output "DENO WARNING $denoName`: $safeLine" }
                elseif ($denoExitCode -eq 0) { Write-Output "DENO STDERR $denoName (exit code 0): $safeLine" }
                else { Write-Output "DENO STDERR $denoName`: $safeLine" }
            }
            Write-Output "Deno process exit code ($denoName): $denoExitCode"
            $denoSummary = @($denoStdout + $denoStderr | Where-Object { $_ -match '(?i)test result:|^\s*(?:ok|FAILED)\s*\||\d+\s+passed' })
            $denoPassed = 0; $denoFailed = 0
            foreach ($summaryLine in $denoSummary) {
                if ([string]$summaryLine -match '(?i)(?<count>\d+)\s+passed') { $denoPassed += [int]$Matches.count }
                if ([string]$summaryLine -match '(?i)(?<count>\d+)\s+failed') { $denoFailed += [int]$Matches.count }
                if ([string]$summaryLine -match '(?i)^\s*FAILED\s*\||test result:\s*FAILED') { $denoFailed = [Math]::Max(1, $denoFailed) }
            }
            if ($denoExitCode -ne 0 -or $denoSummary.Count -eq 0 -or $denoPassed -lt 1 -or $denoFailed -gt 0) {
                Write-Output "DENO_POSTGRES_FILE|$denoName|FAIL"
                if ($denoSummary.Count -eq 0) { Write-Output "DENO ASSERTIONS EXECUTED ($denoName): 0 (no test summary was emitted)" }
                foreach ($line in @($denoStdout | Select-Object -Last 30)) { Write-Output "DENO OUTPUT $denoName`: $(Protect-ProcessText $line)" }
                foreach ($line in @($denoStderr | Select-Object -Last 30)) { Write-Output "DENO STDERR $denoName`: $(Protect-ProcessText $line)" }
                Stop-Safely "Deno PostgreSQL test file '$denoName' failed or did not report executed assertions (exit code $denoExitCode)."
            }
            $denoTotalFiles++
            $denoTotalPassed += $denoPassed
            $denoTotalFailed += $denoFailed
            Write-Output "DENO_POSTGRES_FILE|$denoName|PASS"
        }
        Write-Output "DENO POSTGRESQL INTEGRATION: PASS files=$denoTotalFiles tests=$denoTotalPassed failures=$denoTotalFailed"
        }
    } finally { Pop-Location }
    $failureStage = 'Flyway final-version and synthetic-fixture cleanup verification'
    # The Deno handoff clears PGPASSWORD before tests run. Restore the local
    # admin credential only for these read-only final database checks.
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $pgPassword, 'Process')
    $latest = Invoke-LocalPsql $dbName "SELECT COALESCE(MAX(version::int),0) FROM flyway_schema_history WHERE success;" -Quiet
    if ($latest -ne '20') { Stop-Safely 'Flyway did not finish at V20.' }
    $left = Invoke-LocalPsql $dbName "SELECT (SELECT COUNT(*) FROM exam_types)+(SELECT COUNT(*) FROM categories)+(SELECT COUNT(*) FROM bot_users)+(SELECT COUNT(*) FROM questions)+(SELECT COUNT(*) FROM payment_requests)+(SELECT COUNT(*) FROM mock_attempts)+(SELECT COUNT(*) FROM payment_methods)+(SELECT COUNT(*) FROM access_entitlements)+(SELECT COUNT(*) FROM practice_deliveries)+(SELECT COUNT(*) FROM practice_usage)+(SELECT COUNT(*) FROM practice_sessions)+(SELECT COUNT(*) FROM practice_update_receipts);" -Quiet
    if ([int]$left -ne 0) { Stop-Safely 'Synthetic fixture cleanup check found remaining test rows; database is preserved for inspection.' }
    $testsPassed = $true
} catch {
    $record = $_
    $failureLine = [int]$record.InvocationInfo.ScriptLineNumber
    if ($failureLine -le 0 -and $record.ScriptStackTrace -match ':(?:line )(?<n>\d+)') { $failureLine = [int]$Matches.n }
    $failure = [string]$record.Exception.Message
    if ([string]::IsNullOrWhiteSpace($failure)) { $failure = 'PowerShell reported an unspecified failure.' }
    foreach ($sensitive in @($pgPassword, $runnerPassword, $dbUrl)) {
        if (-not [string]::IsNullOrEmpty([string]$sensitive)) { $failure = $failure.Replace([string]$sensitive, '[REDACTED]') }
    }
    $failure = [regex]::Replace($failure, '(?i)(postgres(?:ql)?://)[^@\s]+@', '$1[REDACTED_CREDENTIALS]@')
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $oldEnv[$name], 'Process') }
    $pgPassword = $null; $runnerPassword = $null; $dbUrl = $null
}

if (-not $testsPassed) {
Write-Output 'PHASE 5 LOCAL POSTGRES INTEGRATION: BLOCKED / FAIL'
    Write-Output '- target: local 127.0.0.1 only'
    Write-Output "- database: $dbName (preserved; no DROP, TRUNCATE, or Flyway clean)"
    Write-Output "- stage: $failureStage"
    Write-Output "- line: $failureLine"
    [Console]::Error.WriteLine($failure)
    exit 1
}
Write-Output 'PHASE 5 LOCAL POSTGRES INTEGRATION: PASS'
Write-Output "POSTGRES: PostgreSQL 18 local loopback; dedicated $dbName; synthetic fixtures cleaned."
Write-Output 'FLYWAY: V1–V20 applied by Spring Boot; SQL and Java migrations included; final version 20.'
Write-Output 'PHASE 4 MOCK TESTS: creation, first answer charge, duplicate callback, resume, timer, frozen versions, scoring, zero-answer expiry PASS.'
Write-Output 'PHASE 4 PAYMENT TESTS: request creation, duplicate reference, duplicate update, approval idempotency, rejection, entitlement visibility PASS.'
Write-Output 'No staging or production database was contacted.'
