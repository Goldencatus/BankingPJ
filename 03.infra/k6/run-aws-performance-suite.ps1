[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'https://d31wz1cs5xx9g9.cloudfront.net/api',
    [ValidateSet('read-rps50', 'read-rps100', 'read-rps200', 'transfer-general', 'transfer-hot', 'idempotency-replay')]
    [string]$StartAt = 'read-rps50'
)

$ErrorActionPreference = 'Stop'
$script:Rows = [System.Collections.Generic.List[object]]::new()
$script:ResultDir = Join-Path $PSScriptRoot ('results\aws-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$script:PasswordFile = Join-Path $PSScriptRoot '.env.aws.local'
$script:Scenarios = @('read-rps50', 'read-rps100', 'read-rps200', 'transfer-general', 'transfer-hot', 'idempotency-replay')

# CloudFront API 주소가 운영 부하 테스트 조건에 맞는지 검증한다.
function Assert-AwsApiUrl([string]$Value) {
    $uri = [Uri]$Value.TrimEnd('/')
    if (-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or
        $uri.Host -eq 'localhost' -or -not $uri.Host.EndsWith('.cloudfront.net') -or
        $uri.AbsolutePath.TrimEnd('/') -ne '/api') {
        throw 'ApiBaseUrl은 /api로 끝나는 HTTPS CloudFront URL이어야 합니다.'
    }
    return $uri
}

# 설치된 k6 실행 파일을 PATH와 기본 설치 위치에서 찾는다.
function Find-K6 {
    $command = Get-Command k6 -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $fallback = 'C:\Program Files\k6\k6.exe'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw 'k6 실행 파일을 찾을 수 없습니다.'
}

# 재실행 가능한 AWS 테스트 비밀번호를 Git 제외 로컬 파일에서 읽거나 생성한다.
function Get-AwsTestPassword {
    $fromEnvironment = [Environment]::GetEnvironmentVariable('AWS_TEST_PASSWORD', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($fromEnvironment)) { return $fromEnvironment }
    if (Test-Path -LiteralPath $script:PasswordFile) {
        $line = Get-Content -LiteralPath $script:PasswordFile | Where-Object { $_ -match '^AWS_TEST_PASSWORD=' } | Select-Object -First 1
        if ($line) { return $line.Substring($line.IndexOf('=') + 1) }
    }
    $generated = 'AwsK6-' + [Guid]::NewGuid().ToString('N') + '!'
    [IO.File]::WriteAllText($script:PasswordFile, "AWS_TEST_PASSWORD=$generated", [Text.UTF8Encoding]::new($false))
    return $generated
}

# 인증 Header를 생성한다.
function New-BearerHeaders([string]$Token) {
    return @{ Authorization = "Bearer $Token"; Accept = 'application/json' }
}

# 사용자가 없으면 가입하고 기존 사용자면 중복 오류만 허용한다.
function Ensure-AwsUser([string]$Email, [string]$Password, [string]$Name) {
    $body = @{ email=$Email; password=$Password; name=$Name } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Method Post -Uri "$script:ApiBase/api/auth/signup" -ContentType 'application/json' -Body $body | Out-Null
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne 409) { throw "AWS 테스트 사용자 준비에 실패했습니다. emailMarker=$($Email.Split('@')[0]) status=$status" }
    }
}

# 테스트 사용자로 로그인하고 Access Token만 메모리에 보관한다.
function Invoke-AwsLogin([string]$Email, [string]$Password) {
    $body = @{ email=$Email; password=$Password } | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$script:ApiBase/api/auth/login" -ContentType 'application/json' -Body $body
    } catch {
        throw "AWS 테스트 사용자 로그인에 실패했습니다. emailMarker=$($Email.Split('@')[0])"
    }
    if (-not $response.success -or [string]::IsNullOrWhiteSpace($response.data.accessToken)) {
        throw "AWS 로그인 응답에 Access Token이 없습니다. emailMarker=$($Email.Split('@')[0])"
    }
    return $response.data.accessToken
}

# ACTIVE 계좌가 두 개가 될 때까지만 REST API로 생성한다.
function Ensure-AwsAccounts([string]$Token) {
    $headers = New-BearerHeaders $Token
    $response = Invoke-RestMethod -Method Get -Uri "$script:ApiBase/api/accounts" -Headers $headers
    $active = @($response.data | Where-Object status -eq 'ACTIVE')
    while ($active.Count -lt 2) {
        Invoke-RestMethod -Method Post -Uri "$script:ApiBase/api/accounts" -Headers $headers | Out-Null
        $response = Invoke-RestMethod -Method Get -Uri "$script:ApiBase/api/accounts" -Headers $headers
        $active = @($response.data | Where-Object status -eq 'ACTIVE')
    }
    return @($active | Sort-Object accountId | Select-Object -First 2)
}

# source 계좌가 목표 잔액보다 작을 때 부족한 금액만 REST API로 입금한다.
function Ensure-AwsBalance([string]$Token, $Account, [decimal]$TargetBalance) {
    $current = [decimal]$Account.balance
    if ($current -ge $TargetBalance) { return }
    $amount = ($TargetBalance - $current).ToString('0.0000', [Globalization.CultureInfo]::InvariantCulture)
    $headers = New-BearerHeaders $Token
    $body = @{ amount=$amount } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$script:ApiBase/api/accounts/$($Account.accountId)/deposits" `
        -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
}

# 고정 prefix 사용자를 생성 또는 재사용하고 계좌와 최소 잔액을 준비한다.
function Ensure-AwsTestActor([string]$Email, [string]$Password, [decimal]$Balance) {
    Ensure-AwsUser $Email $Password 'AWS k6 test'
    $token = Invoke-AwsLogin $Email $Password
    $accounts = Ensure-AwsAccounts $token
    Ensure-AwsBalance $token $accounts[0] $Balance
    return [pscustomobject]@{ email=$Email; token=$token; source=$accounts[0]; destination=$accounts[1] }
}

# k6 summary JSON에서 메트릭 값을 안전하게 읽는다.
function Get-MetricValue($Metrics, [string]$Name, [string]$Property, [double]$Default = 0) {
    $metric = $Metrics.PSObject.Properties[$Name].Value
    if ($null -eq $metric) { return $Default }
    $value = $metric.PSObject.Properties[$Property].Value
    if ($null -eq $value) { return $Default }
    return [double]$value
}

# 시나리오 summary를 최종 CSV 행으로 변환한다.
function Add-AwsSummaryRow([string]$Name, [int]$TargetRps, [string]$JsonPath, [int]$ExitCode) {
    $summary = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json
    $metrics = $summary.metrics
    $script:Rows.Add([pscustomobject]@{
        scenario=$Name; target_rps=$TargetRps
        requests=[int64](Get-MetricValue $metrics 'scenario_requests' 'count')
        actual_rps=[math]::Round((Get-MetricValue $metrics 'scenario_requests' 'rate'),3)
        p50_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'med'),3)
        p90_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(90)'),3)
        p95_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(95)'),3)
        p99_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'p(99)'),3)
        max_ms=[math]::Round((Get-MetricValue $metrics 'scenario_duration' 'max'),3)
        http_failure_rate=[math]::Round((Get-MetricValue $metrics 'http_req_failed' 'value'),6)
        scenario_failure_rate=[math]::Round((Get-MetricValue $metrics 'scenario_failed' 'value'),6)
        check_rate=[math]::Round((Get-MetricValue $metrics 'scenario_checks' 'value'),6)
        dropped_iterations=[int64](Get-MetricValue $metrics 'dropped_iterations' 'count')
        transfer_id_match=[math]::Round((Get-MetricValue $metrics 'transfer_id_match' 'value'),6)
        duplicate_prevented=[math]::Round((Get-MetricValue $metrics 'duplicate_transfer_prevented' 'value'),6)
        exit_code=$ExitCode
    })
}

# 시나리오를 실행하고 실패하면 후속 부하를 즉시 중단한다.
function Invoke-AwsK6([string]$Name, [string]$ScriptName, [hashtable]$Variables, [int]$TargetRps, [switch]$Warmup) {
    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add('run')
    if ($Warmup) { $arguments.Add('--summary-mode=disabled') }
    else {
        $arguments.Add('--summary-export'); $arguments.Add((Join-Path $script:ResultDir "$Name.json"))
        $arguments.Add('--summary-trend-stats'); $arguments.Add('avg,min,med,p(90),p(95),p(99),max')
        $arguments.Add('--summary-time-unit'); $arguments.Add('ms')
    }
    foreach ($entry in $Variables.GetEnumerator()) {
        $arguments.Add('-e'); $arguments.Add("$($entry.Key)=$($entry.Value)")
    }
    $arguments.Add((Join-Path $PSScriptRoot $ScriptName))
    $log = Join-Path $script:ResultDir $(if ($Warmup) { "$Name-warmup.log" } else { "$Name.log" })
    Write-Host $(if ($Warmup) { "[AWS WARMUP] $Name" } else { "[AWS RUN] $Name" })
    & $script:K6 @arguments 2>&1 | Tee-Object -FilePath $log
    $exitCode = $LASTEXITCODE
    if ($Warmup) {
        if ($exitCode -ne 0) { throw "$Name warm-up이 실패했습니다. 후속 부하를 중단합니다." }
    } else {
        $json = Join-Path $script:ResultDir "$Name.json"
        if (-not (Test-Path -LiteralPath $json)) { throw "$Name 결과 JSON이 생성되지 않았습니다." }
        Add-AwsSummaryRow $Name $TargetRps $json $exitCode
        $row = $script:Rows[-1]
        if ($exitCode -ne 0 -or $row.scenario_failure_rate -ne 0 -or $row.check_rate -ne 1) {
            throw "$Name 시나리오가 실패했습니다. 후속 부하를 중단합니다."
        }
    }
}

# AWS E2E 실측치만 사용해 CSV와 보고서를 생성한다.
function Write-AwsReport {
    $script:Rows | Export-Csv -LiteralPath (Join-Path $script:ResultDir 'summary.csv') -NoTypeInformation -Encoding utf8
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# AWS Deployment E2E Performance'); $lines.Add('')
    $lines.Add('- 경로: CloudFront → EC2/Nginx → Spring Boot Docker → RDS MySQL')
    $lines.Add("- 실행 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')"); $lines.Add('')
    $lines.Add('| scenario | target RPS | actual RPS | requests | p50 | p90 | p95 | p99 | max | HTTP failure | checks | dropped |')
    $lines.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
    foreach ($row in $script:Rows) {
        $lines.Add("| $($row.scenario) | $($row.target_rps) | $($row.actual_rps) | $($row.requests) | $($row.p50_ms)ms | $($row.p90_ms)ms | $($row.p95_ms)ms | $($row.p99_ms)ms | $($row.max_ms)ms | $($row.http_failure_rate) | $($row.check_rate) | $($row.dropped_iterations) |")
    }
    $reads = @($script:Rows | Where-Object scenario -like 'read-rps*')
    if ($reads.Count -eq 3) {
        $maintained = @($reads | Where-Object { $_.scenario_failure_rate -ne 0 -or $_.dropped_iterations -ne 0 }).Count -eq 0
        $lines.Add(''); $lines.Add('## Read throughput'); $lines.Add('')
        $lines.Add("50/100/200 RPS 목표 부하 유지 여부: $(if($maintained){'유지'}else{'미유지'}). 이는 현재 AWS 측정 범위의 결과이며 최대 처리량을 의미하지 않습니다.")
    }
    $general = $script:Rows | Where-Object scenario -eq 'transfer-general'
    $hot = $script:Rows | Where-Object scenario -eq 'transfer-hot'
    if ($general -and $hot) {
        $lines.Add(''); $lines.Add('## General vs Hot Account'); $lines.Add('')
        $lines.Add("General p95/p99=$($general.p95_ms)/$($general.p99_ms)ms, Hot p95/p99=$($hot.p95_ms)/$($hot.p99_ms)ms입니다. 목표 RPS가 20과 10으로 다르므로 RPS 차이를 Lock 성능 차이로 해석하지 않습니다.")
    }
    $idem = $script:Rows | Where-Object scenario -eq 'idempotency-replay'
    if ($idem) {
        $lines.Add(''); $lines.Add('## Idempotency Replay'); $lines.Add('')
        $lines.Add("성공률=$((1-[double]$idem.scenario_failure_rate)*100)%, transferId 동일=$($idem.transfer_id_match), 중복 Ledger 방지=$($idem.duplicate_prevented).")
    }
    $lines.Add(''); $lines.Add('## 측정 목적 구분'); $lines.Add('')
    $lines.Add('Local 결과는 LARGE dataset에서 DB 성능과 로컬 API 기준선을 확인한 결과입니다. AWS 결과는 실제 CloudFront→EC2/Nginx→Spring→RDS 전체 경로를 측정하며 데이터셋이 다르므로 직접적인 우열 비교를 하지 않습니다.')
    Set-Content -LiteralPath (Join-Path $script:ResultDir 'report.md') -Value $lines -Encoding utf8
}

$apiUri = Assert-AwsApiUrl $ApiBaseUrl
$script:ApiBase = $apiUri.AbsoluteUri.TrimEnd('/')
$script:OriginBase = "$($apiUri.Scheme)://$($apiUri.Host)"
$script:K6 = Find-K6
New-Item -ItemType Directory -Path $script:ResultDir -Force | Out-Null

$health = Invoke-RestMethod -Uri "$script:ApiBase/actuator/health" -TimeoutSec 15
if ($health.status -ne 'UP') { throw 'AWS health preflight가 UP이 아닙니다.' }
Write-Host '[AWS PREFLIGHT] CloudFront HTTPS health=UP, localhost 아님'

$password = Get-AwsTestPassword
$read = Ensure-AwsTestActor 'k6-aws-read@bankingpj.test' $password 100
$general = @()
foreach ($index in 1..6) {
    $general += Ensure-AwsTestActor ("k6-aws-general-{0:D2}@bankingpj.test" -f $index) $password 5000
}
$hot = Ensure-AwsTestActor 'k6-aws-hot@bankingpj.test' $password 5000
$idem = Ensure-AwsTestActor 'k6-aws-idempotency@bankingpj.test' $password 100
Write-Host '[AWS PREFLIGHT] REST API 테스트 사용자 재사용/준비, 로그인, ACTIVE 계좌 확인 완료'

$startIndex = [Array]::IndexOf($script:Scenarios, $StartAt)
for ($index = $startIndex; $index -lt $script:Scenarios.Count; $index++) {
    $name = $script:Scenarios[$index]
    if ($name -like 'read-rps*') {
        $target = [int]($name -replace 'read-rps','')
        $vars = @{ BASE_URL=$script:ApiBase; TEST_EMAIL=$read.email; TEST_PASSWORD=$password; ACCOUNT_ID=[string]$read.source.accountId; TARGET_RPS=[string]$target; DURATION='5s' }
        Invoke-AwsK6 $name 'read-throughput.js' $vars $target -Warmup
        $vars.DURATION='45s'; Invoke-AwsK6 $name 'read-throughput.js' $vars $target
    } elseif ($name -eq 'transfer-general') {
        $vars = @{ BASE_URL=$script:ApiBase; TEST_EMAIL=$general[0].email; TEST_EMAILS=(($general.email) -join ','); TEST_PASSWORD=$password; MODE='general'; TARGET_RPS='20'; DURATION='60s'; AMOUNT='1.0000' }
        Invoke-AwsK6 $name 'transfer-load.js' $vars 20
    } elseif ($name -eq 'transfer-hot') {
        $vars = @{ BASE_URL=$script:ApiBase; TEST_EMAIL=$hot.email; TEST_PASSWORD=$password; MODE='hot'; TARGET_RPS='10'; DURATION='60s'; AMOUNT='1.0000'; FROM_ACCOUNT_ID=[string]$hot.source.accountId; TO_ACCOUNT_ID=[string]$hot.destination.accountId }
        Invoke-AwsK6 $name 'transfer-load.js' $vars 10
    } else {
        $vars = @{ BASE_URL=$script:ApiBase; TEST_EMAIL=$idem.email; TEST_PASSWORD=$password; TARGET_RPS='20'; DURATION='45s' }
        Invoke-AwsK6 $name 'idempotency-replay.js' $vars 20
    }
}

Write-AwsReport
Write-Host "[AWS RESULT] $script:ResultDir"
