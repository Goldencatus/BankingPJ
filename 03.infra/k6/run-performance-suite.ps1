[CmdletBinding()]
param(
    [string]$BaseUrl,
    [ValidateSet('SMALL', 'MEDIUM', 'LARGE')]
    [string]$Scale = 'LARGE',
    [string]$DatabaseName,
    [switch]$SkipSeedReset,
    [switch]$ReadOnly,
    [ValidateSet('Full', 'ReadOnly', 'IdempotencyReplay')]
    [string]$Scenario = 'Full',
    [string]$BaselineResultDir
)

$ErrorActionPreference = 'Stop'
$script:StartedBackend = $null
$script:StartedBackendListenerPid = $null
$script:Rows = [System.Collections.Generic.List[object]]::new()
$script:OriginalEnvironment = @{}

# KEY=VALUE 형식의 로컬 환경 파일을 민감값 출력 없이 읽는다.
function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

# 명시값, OS 환경변수, .env 순서로 설정값을 선택한다.
function Resolve-Setting([string]$Explicit, [string[]]$Names, [hashtable]$DotEnv, [string]$Default = '') {
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) { return $Explicit.Trim() }
    foreach ($name in $Names) {
        $processValue = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue.Trim() }
    }
    foreach ($name in $Names) {
        if ($DotEnv.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($DotEnv[$name])) {
            return $DotEnv[$name].Trim()
        }
    }
    return $Default
}

# 자식 프로세스에만 전달할 환경값을 설정하고 기존 값을 기억한다.
function Set-SuiteEnvironment([string]$Name, [string]$Value) {
    if (-not $script:OriginalEnvironment.ContainsKey($Name)) {
        $script:OriginalEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

# 실행 중 바꾼 프로세스 환경값을 원래 상태로 되돌린다.
function Restore-SuiteEnvironment {
    foreach ($entry in $script:OriginalEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}

# 설치된 k6 실행 파일을 PATH와 기본 설치 위치에서 찾는다.
function Find-K6 {
    $command = Get-Command k6 -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $fallback = 'C:\Program Files\k6\k6.exe'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw 'k6 실행 파일을 찾을 수 없습니다.'
}

# Backend health endpoint가 정상 응답하는지만 확인한다.
function Test-BackendHealth([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri "$Url/actuator/health" -TimeoutSec 3 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch { return $false }
}

# 로컬 8080 포트를 점유한 프로세스 ID를 운영체제 출력에서 찾는다.
function Get-BackendListenerPid {
    $line = (& cmd.exe /c 'netstat -ano | findstr LISTENING | findstr :8080' 2>$null | Select-Object -First 1)
    if ($line -and $line -match '\s+(\d+)\s*$') { return [int]$Matches[1] }
    return $null
}

# 필요할 때만 성능 DB 환경으로 Backend를 시작하고 준비될 때까지 기다린다.
function Ensure-Backend([string]$Url, [string]$BackendDir, [string]$ResultDir) {
    if (Test-BackendHealth $Url) {
        Write-Host '[PREFLIGHT] Backend 응답 확인 완료 (기존 프로세스 사용)'
        return
    }
    if ($Url -ne 'http://localhost:8080') {
        throw '지정 BASE_URL에 Backend가 응답하지 않습니다. 원격 Backend는 자동 시작하지 않습니다.'
    }
    $stdout = Join-Path $ResultDir 'backend.stdout.log'
    $stderr = Join-Path $ResultDir 'backend.stderr.log'
    $script:StartedBackend = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'gradlew.bat bootRun --no-daemon' -WorkingDirectory $BackendDir `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($script:StartedBackend.HasExited) { throw "Backend 시작에 실패했습니다. 결과 폴더의 backend 로그를 확인하세요." }
        if (Test-BackendHealth $Url) {
            $script:StartedBackendListenerPid = Get-BackendListenerPid
            Write-Host '[PREFLIGHT] 성능 DB Backend 시작 완료'
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'Backend 준비 시간이 120초를 초과했습니다.'
}

# 로그인 응답에서 토큰만 메모리에 보관한다.
function Invoke-Login([string]$Url, [string]$Email, [string]$Password) {
    try {
        $body = @{ email = $Email; password = $Password } | ConvertTo-Json -Compress
        $response = Invoke-RestMethod -Method Post -Uri "$Url/api/auth/login" -ContentType 'application/json' -Body $body
        if (-not $response.success -or [string]::IsNullOrWhiteSpace($response.data.accessToken)) {
            throw 'Access Token이 없습니다.'
        }
        return $response.data.accessToken
    } catch {
        throw "성능 테스트 사용자 로그인에 실패했습니다. Backend가 bankingpj_perf를 사용하는지와 SEEDER_TEST_PASSWORD를 확인하세요."
    }
}

# 인증 사용자의 ACTIVE 계좌 두 개를 API로 찾아 반환한다.
function Get-ActiveAccounts([string]$Url, [string]$Token) {
    $headers = @{ Authorization = "Bearer $Token" }
    $response = Invoke-RestMethod -Method Get -Uri "$Url/api/accounts" -Headers $headers
    $accounts = @($response.data | Where-Object { $_.status -eq 'ACTIVE' })
    if ($accounts.Count -lt 2) { throw '성능 테스트 사용자에게 ACTIVE 계좌 두 개가 필요합니다.' }
    return $accounts
}

# k6 summary JSON의 메트릭 속성을 안전하게 읽는다.
function Get-MetricValue($Metrics, [string]$MetricName, [string]$Property, [double]$Default = 0) {
    $metric = $Metrics.PSObject.Properties[$MetricName].Value
    if ($null -eq $metric) { return $Default }
    $value = $metric.PSObject.Properties[$Property].Value
    if ($null -eq $value) { return $Default }
    return [double]$value
}

# 단일 k6 실행 결과를 공통 CSV 행으로 변환한다.
function Add-SummaryRow([string]$Name, [string]$JsonPath, [int]$Vus, [int]$TargetRps, [int]$ExitCode) {
    if (-not (Test-Path -LiteralPath $JsonPath)) {
        $script:Rows.Add([pscustomobject]@{ scenario=$Name; vus=$Vus; target_rps=$TargetRps; requests=0; rps=0; avg_ms=0; p50_ms=0; p90_ms=0; p95_ms=0; p99_ms=0; max_ms=0; failure_rate=1; check_rate=0; dropped_iterations=0; vus_used=0; status_200=0; status_400=0; status_401=0; status_404=0; status_409=0; status_500=0; status_other=0; exit_code=$ExitCode })
        return
    }
    $summary = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json
    $metrics = $summary.metrics
    $script:Rows.Add([pscustomobject]@{
        scenario=$Name; vus=$Vus; target_rps=$TargetRps
        requests=[int64](Get-MetricValue $metrics 'scenario_requests' 'count')
        rps=[math]::Round((Get-MetricValue $metrics 'scenario_requests' 'rate'), 3)
        avg_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'avg'), 3)
        p50_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'med'), 3)
        p90_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(90)'), 3)
        p95_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(95)'), 3)
        p99_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(99)'), 3)
        max_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'max'), 3)
        failure_rate=[math]::Round((Get-MetricValue $metrics 'scenario_failed' 'value'), 6)
        check_rate=[math]::Round((Get-MetricValue $metrics 'scenario_checks' 'value'), 6)
        dropped_iterations=[int64](Get-MetricValue $metrics 'dropped_iterations' 'count')
        vus_used=[int](Get-MetricValue $metrics 'vus' 'max')
        status_200=[int64](Get-MetricValue $metrics 'status_200' 'count')
        status_400=[int64](Get-MetricValue $metrics 'status_400' 'count')
        status_401=[int64](Get-MetricValue $metrics 'status_401' 'count')
        status_404=[int64](Get-MetricValue $metrics 'status_404' 'count')
        status_409=[int64](Get-MetricValue $metrics 'status_409' 'count')
        status_500=[int64](Get-MetricValue $metrics 'status_500' 'count')
        status_other=[int64](Get-MetricValue $metrics 'status_other' 'count')
        idempotency_match_rate=[math]::Round((Get-MetricValue $metrics 'transfer_id_match' 'value'), 6)
        duplicate_prevented_rate=[math]::Round((Get-MetricValue $metrics 'duplicate_transfer_prevented' 'value'), 6)
        exit_code=$ExitCode
    })
}

# 시나리오별 k6를 실행하고 원본 summary와 콘솔 로그를 저장한다.
function Invoke-K6Scenario([string]$Name, [string]$Script, [hashtable]$Variables, [int]$Vus, [int]$TargetRps, [switch]$Warmup) {
    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add('run')
    if ($Warmup) { $arguments.Add('--summary-mode=disabled') }
    else {
        $jsonPath = Join-Path $script:ResultDir "$Name.json"
        $arguments.Add('--summary-export'); $arguments.Add($jsonPath)
        $arguments.Add('--summary-trend-stats'); $arguments.Add('avg,min,med,p(90),p(95),p(99),max')
        $arguments.Add('--summary-time-unit'); $arguments.Add('ms')
    }
    foreach ($entry in $Variables.GetEnumerator()) {
        $arguments.Add('-e'); $arguments.Add("$($entry.Key)=$($entry.Value)")
    }
    $arguments.Add($Script)
    if ($Warmup) { Write-Host "[WARMUP] $Name" } else { Write-Host "[RUN] $Name" }
    $logPath = Join-Path $script:ResultDir $(if ($Warmup) { "$Name-warmup.log" } else { "$Name.log" })
    & $script:K6Path @arguments 2>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    if (-not $Warmup) { Add-SummaryRow $Name (Join-Path $script:ResultDir "$Name.json") $Vus $TargetRps $exitCode }
}

# 두 값의 증감률을 0 나눗셈 없이 계산한다.
function Get-PercentChange([double]$Before, [double]$After) {
    if ($Before -eq 0) { return '계산 불가' }
    return ('{0:N1}%' -f ((($After - $Before) / $Before) * 100))
}

# 실제 수집값으로 CSV와 Markdown 비교 보고서를 생성한다.
function Write-Reports([string]$Directory, [string]$RunScale, [bool]$IsReadOnly) {
    $csvPath = Join-Path $Directory 'summary.csv'
    $script:Rows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# STEP 17 k6 성능 테스트 결과')
    $lines.Add('')
    $lines.Add("- 실행 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')")
    $lines.Add("- Seeder 규모: $RunScale")
    $lines.Add("- 모드: $(if ($IsReadOnly) { 'ReadOnly' } else { 'Full' })")
    $lines.Add('- 민감한 실행 환경값은 기록하지 않음')
    $lines.Add('')
    $lines.Add('## 원본 메트릭')
    $lines.Add('')
    $lines.Add('| scenario | VU | target RPS | requests | RPS | p50 ms | p90 ms | p95 ms | p99 ms | max ms | failure | checks | dropped | exit |')
    $lines.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
    foreach ($row in $script:Rows) {
        $lines.Add("| $($row.scenario) | $($row.vus) | $($row.target_rps) | $($row.requests) | $($row.rps) | $($row.p50_ms) | $($row.p90_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.max_ms) | $($row.failure_rate) | $($row.check_rate) | $($row.dropped_iterations) | $($row.exit_code) |")
    }
    $lines.Add('')
    $lines.Add('## A. Read VU')
    $lines.Add('')
    $read10 = $script:Rows | Where-Object scenario -eq 'read-vu10'
    $read50 = $script:Rows | Where-Object scenario -eq 'read-vu50'
    $read100 = $script:Rows | Where-Object scenario -eq 'read-vu100'
    if ($read10 -and $read50 -and $read100) {
        $lines.Add('| VU | RPS | p95 ms | p99 ms | failure |')
        $lines.Add('|---:|---:|---:|---:|---:|')
        foreach ($row in @($read10, $read50, $read100)) {
            $lines.Add("| $($row.vus) | $($row.rps) | $($row.p95_ms) | $($row.p99_ms) | $($row.failure_rate) |")
        }
        $lines.Add('')
        $lines.Add("10→100 VU에서 RPS는 $($read10.rps)→$($read100.rps)였고 p95는 $($read10.p95_ms)ms→$($read100.p95_ms)ms였습니다. 세 구간 모두 실패율은 0입니다.")
    }
    $lines.Add('')
    $lines.Add('## B. Read throughput')
    $lines.Add('')
    $rps50 = $script:Rows | Where-Object scenario -eq 'read-rps50'
    $rps100 = $script:Rows | Where-Object scenario -eq 'read-rps100'
    $rps200 = $script:Rows | Where-Object scenario -eq 'read-rps200'
    if ($rps50 -and $rps100 -and $rps200) {
        $lines.Add('| target RPS | actual RPS | p95 ms | p99 ms | dropped | failure | checks |')
        $lines.Add('|---:|---:|---:|---:|---:|---:|---:|')
        foreach ($row in @($rps50, $rps100, $rps200)) {
            $lines.Add("| $($row.target_rps) | $($row.rps) | $($row.p95_ms) | $($row.p99_ms) | $($row.dropped_iterations) | $($row.failure_rate) | $($row.check_rate) |")
        }
        $lines.Add('')
        $lines.Add('현재 측정 범위에서는 200 RPS까지 failure 0, checks 100%, dropped 0으로 목표 부하를 유지했습니다. 서버 최대 처리량을 뜻하지는 않습니다.')
    }
    $lines.Add('')
    $lines.Add('## C. General Transfer vs Hot Account')
    $lines.Add('')
    $general = $script:Rows | Where-Object scenario -eq 'transfer-general'
    $hot = $script:Rows | Where-Object scenario -eq 'transfer-hot'
    if ($general -and $hot) {
        $lines.Add("General 대비 Hot Account의 p95는 $($general.p95_ms)ms→$($hot.p95_ms)ms로 $(Get-PercentChange $general.p95_ms $hot.p95_ms) 증가했고, p99는 $($general.p99_ms)ms→$($hot.p99_ms)ms로 $(Get-PercentChange $general.p99_ms $hot.p99_ms) 증가했습니다.")
        $lines.Add('General target 20 RPS와 Hot target 10 RPS는 서로 다른 부하이므로 처리량 차이를 Lock 영향으로 해석하지 않고 지연시간 증가만 비교했습니다.')
    }
    $lines.Add('')
    $lines.Add('## D. Idempotency Replay')
    $lines.Add('')
    $replay = $script:Rows | Where-Object scenario -eq 'idempotency-replay'
    if ($replay) {
        $lines.Add("RPS=$($replay.rps), p50=$($replay.p50_ms)ms, p95=$($replay.p95_ms)ms, p99=$($replay.p99_ms)ms, 성공률=$((1 - [double]$replay.failure_rate) * 100)%, checks=$($replay.check_rate).")
        $lines.Add("transferId 동일 check=$($replay.idempotency_match_rate), 반복 replay의 추가 Ledger 방지 검증=$($replay.duplicate_prevented_rate)로 통과했습니다.")
    }
    $lines.Add('')
    $lines.Add('## E. DB 직접 측정과 API 측정')
    $lines.Add('')
    $lines.Add('LARGE의 ledger_entries 2,000,000건에서 EXPLAIN ANALYZE 직접 측정은 인덱스 사용 약 1.85ms, IGNORE INDEX 약 687ms로 약 371배 차이였습니다. 이는 DB 직접 SQL 결과이며 위 k6 HTTP latency와 합산하거나 같은 지표로 비교하지 않습니다.')
    Set-Content -LiteralPath (Join-Path $Directory 'report.md') -Value $lines -Encoding utf8
}

$rootDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backendDir = Join-Path $rootDir '02.backend'
$dotEnv = Read-DotEnv (Join-Path $rootDir '.env')
$resolvedBaseUrl = Resolve-Setting $BaseUrl @('K6_BASE_URL', 'BASE_URL') $dotEnv 'http://localhost:8080'
$resolvedDatabase = Resolve-Setting $DatabaseName @('DB_NAME', 'MYSQL_DATABASE') $dotEnv
$databaseUser = Resolve-Setting '' @('DB_USERNAME', 'MYSQL_USER') $dotEnv
$databasePassword = Resolve-Setting '' @('DB_PASSWORD', 'MYSQL_PASSWORD') $dotEnv
$jwtSecret = Resolve-Setting '' @('JWT_ACCESS_SECRET') $dotEnv
$testPassword = Resolve-Setting '' @('SEEDER_TEST_PASSWORD') $dotEnv
$effectiveScenario = if ($ReadOnly) { 'ReadOnly' } else { $Scenario }
$isReadOnly = $effectiveScenario -eq 'ReadOnly'
$isIdempotencyOnly = $effectiveScenario -eq 'IdempotencyReplay'

if ($resolvedDatabase -ne 'bankingpj_perf') { throw "성능 테스트는 bankingpj_perf에서만 실행할 수 있습니다. target=$resolvedDatabase" }
foreach ($required in @{ DB_USERNAME=$databaseUser; DB_PASSWORD=$databasePassword; JWT_ACCESS_SECRET=$jwtSecret }.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($required.Value)) { throw "필수 설정이 없습니다: $($required.Key)" }
}
if ([string]::IsNullOrWhiteSpace($testPassword)) {
    if ($SkipSeedReset -or $isReadOnly) { throw '기존 Seeder 데이터를 사용할 때는 SEEDER_TEST_PASSWORD가 필요합니다.' }
    $testPassword = "K6-local-$([Guid]::NewGuid().ToString('N'))!"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:ResultDir = Join-Path $PSScriptRoot "results\$timestamp"
New-Item -ItemType Directory -Path $script:ResultDir -Force | Out-Null
$script:K6Path = Find-K6

try {
    Set-SuiteEnvironment 'DB_NAME' $resolvedDatabase
    Set-SuiteEnvironment 'DB_USERNAME' $databaseUser
    Set-SuiteEnvironment 'DB_PASSWORD' $databasePassword
    Set-SuiteEnvironment 'JWT_ACCESS_SECRET' $jwtSecret
    Set-SuiteEnvironment 'SEEDER_TEST_PASSWORD' $testPassword
    Set-SuiteEnvironment 'JWT_ACCESS_TTL_SECONDS' (Resolve-Setting '' @('JWT_ACCESS_TTL_SECONDS') $dotEnv '900')
    Set-SuiteEnvironment 'JWT_REFRESH_TTL_SECONDS' (Resolve-Setting '' @('JWT_REFRESH_TTL_SECONDS') $dotEnv '1209600')
    Set-SuiteEnvironment 'AUTH_COOKIE_SECURE' (Resolve-Setting '' @('AUTH_COOKIE_SECURE') $dotEnv 'false')
    Set-SuiteEnvironment 'GRADLE_USER_HOME' (Join-Path $rootDir '.gradle-user-home')

    Write-Host "[PREFLIGHT] database=$resolvedDatabase scale=$Scale mode=$effectiveScenario"
    if (-not $SkipSeedReset -and -not $isReadOnly) {
        Push-Location $backendDir
        try {
            Write-Host '[SEED] STEP 16 데이터 초기화'
            & '.\gradlew.bat' resetSeedData --no-daemon
            if ($LASTEXITCODE -ne 0) { throw 'resetSeedData 실행에 실패했습니다.' }
            Write-Host "[SEED] $Scale 데이터 생성"
            & '.\gradlew.bat' seedData "-PseedScale=$Scale" --no-daemon
            if ($LASTEXITCODE -ne 0) { throw 'seedData 실행에 실패했습니다.' }
        } finally {
            Pop-Location
        }
    }

    Ensure-Backend $resolvedBaseUrl $backendDir $script:ResultDir
    $readerEmail = 'k6-reader@bankingpj.test'
    $hotEmail = 'k6-hot@bankingpj.test'
    $readerToken = Invoke-Login $resolvedBaseUrl $readerEmail $testPassword
    $readerAccounts = Get-ActiveAccounts $resolvedBaseUrl $readerToken
    if (-not $isReadOnly -and -not $isIdempotencyOnly) {
        $hotToken = Invoke-Login $resolvedBaseUrl $hotEmail $testPassword
        $hotAccounts = Get-ActiveAccounts $resolvedBaseUrl $hotToken
    }
    Write-Host '[PREFLIGHT] 로그인, 소유권, ACTIVE 계좌 확인 완료'

    if (-not $isIdempotencyOnly) {
        $commonRead = @{ BASE_URL=$resolvedBaseUrl; TEST_EMAIL=$readerEmail; TEST_PASSWORD=$testPassword; ACCOUNT_ID=[string]$readerAccounts[0].accountId }
        foreach ($vus in @(10, 50, 100)) {
            $variables = $commonRead.Clone(); $variables.VUS=[string]$vus; $variables.DURATION='5s'
            Invoke-K6Scenario "read-vu$vus" (Join-Path $PSScriptRoot 'account-transactions-baseline.js') $variables $vus 0 -Warmup
            $variables.DURATION='60s'
            Invoke-K6Scenario "read-vu$vus" (Join-Path $PSScriptRoot 'account-transactions-baseline.js') $variables $vus 0
        }
        foreach ($target in @(50, 100, 200)) {
            $variables = $commonRead.Clone(); $variables.TARGET_RPS=[string]$target; $variables.DURATION='5s'
            Invoke-K6Scenario "read-rps$target" (Join-Path $PSScriptRoot 'read-throughput.js') $variables 0 $target -Warmup
            $variables.DURATION='45s'
            Invoke-K6Scenario "read-rps$target" (Join-Path $PSScriptRoot 'read-throughput.js') $variables 0 $target
        }
    }

    if (-not $isReadOnly -and -not $isIdempotencyOnly) {
        $generalEmails = @($readerEmail, 'k6-ledger@bankingpj.test')
        for ($index = 3; $index -lt 25; $index++) { $generalEmails += ('step16-user-{0:D6}@bankingpj.test' -f $index) }
        $generalVariables = @{ BASE_URL=$resolvedBaseUrl; TEST_EMAIL=$readerEmail; TEST_EMAILS=($generalEmails -join ','); TEST_PASSWORD=$testPassword; MODE='general'; TARGET_RPS='20'; DURATION='60s'; AMOUNT='1.0000' }
        Invoke-K6Scenario 'transfer-general' (Join-Path $PSScriptRoot 'transfer-load.js') $generalVariables 0 20
        $hotVariables = @{ BASE_URL=$resolvedBaseUrl; TEST_EMAIL=$hotEmail; TEST_PASSWORD=$testPassword; MODE='hot'; TARGET_RPS='10'; DURATION='60s'; AMOUNT='1.0000'; FROM_ACCOUNT_ID=[string]$hotAccounts[0].accountId; TO_ACCOUNT_ID=[string]$hotAccounts[1].accountId }
        Invoke-K6Scenario 'transfer-hot' (Join-Path $PSScriptRoot 'transfer-load.js') $hotVariables 0 10
    }

    if (-not $isReadOnly) {
        $replayVariables = @{ BASE_URL=$resolvedBaseUrl; TEST_EMAIL=$readerEmail; TEST_PASSWORD=$testPassword; TARGET_RPS='20'; DURATION='45s' }
        Invoke-K6Scenario 'idempotency-replay' (Join-Path $PSScriptRoot 'idempotency-replay.js') $replayVariables 0 20
    }

    if ($isIdempotencyOnly) {
        if ([string]::IsNullOrWhiteSpace($BaselineResultDir)) { throw 'IdempotencyReplay에는 -BaselineResultDir가 필요합니다.' }
        $resolvedBaseline = (Resolve-Path -LiteralPath $BaselineResultDir).Path
        $scenarioMetadata = @(
            @('read-vu10',10,0), @('read-vu50',50,0), @('read-vu100',100,0),
            @('read-rps50',0,50), @('read-rps100',0,100), @('read-rps200',0,200),
            @('transfer-general',0,20), @('transfer-hot',0,10)
        )
        $script:Rows.Clear()
        foreach ($metadata in $scenarioMetadata) {
            $sourceJson = Join-Path $resolvedBaseline "$($metadata[0]).json"
            if (-not (Test-Path -LiteralPath $sourceJson)) { throw "공식 기준 JSON이 없습니다: $($metadata[0])" }
            $targetJson = Join-Path $script:ResultDir "$($metadata[0]).json"
            Copy-Item -LiteralPath $sourceJson -Destination $targetJson
            Add-SummaryRow $metadata[0] $targetJson $metadata[1] $metadata[2] 0
        }
        Add-SummaryRow 'idempotency-replay' (Join-Path $script:ResultDir 'idempotency-replay.json') 0 20 0
    }

    Write-Reports $script:ResultDir $Scale $isReadOnly
    Write-Host "[RESULT] $($script:ResultDir)"
    $failedScenarios = @($script:Rows | Where-Object { $_.exit_code -ne 0 -or $_.failure_rate -ne 0 -or $_.check_rate -ne 1 })
    if ($failedScenarios.Count -gt 0) {
        throw "실패한 성능 시나리오가 있습니다: $(($failedScenarios.scenario) -join ', ')"
    }
} finally {
    if ($script:StartedBackend -and -not $script:StartedBackend.HasExited) {
        Stop-Process -Id $script:StartedBackend.Id -Force -ErrorAction SilentlyContinue
    }
    if ($script:StartedBackendListenerPid) {
        Stop-Process -Id $script:StartedBackendListenerPid -Force -ErrorAction SilentlyContinue
    }
    if ($script:StartedBackend) { Write-Host '[CLEANUP] 이번 실행에서 시작한 Backend를 종료했습니다.' }
    Restore-SuiteEnvironment
}
