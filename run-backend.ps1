# Windows PowerShell 5.1 이상에서 실행한다.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

# 빈 줄과 주석을 제외하고 첫 번째 등호를 기준으로 환경설정 파일을 읽는다.
function Read-DotEnv {
    param([string]$Path)

    $settings = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $entry = $line.Trim()
        if ($entry.Length -eq 0 -or $entry.StartsWith('#')) {
            continue
        }

        $separator = $entry.IndexOf('=')
        if ($separator -lt 1) {
            throw 'Invalid .env entry.'
        }

        $key = $entry.Substring(0, $separator).Trim()
        $value = $entry.Substring($separator + 1).Trim()
        if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw 'Invalid .env key.'
        }

        # 양쪽을 감싼 따옴표만 제거하며 변수 치환이나 명령 실행은 하지 않는다.
        if ($value.Length -ge 2 -and (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $settings[$key] = $value
    }
    return $settings
}

$envPath = Join-Path $PSScriptRoot '.env'
$backendPath = Join-Path $PSScriptRoot '02.backend'
$gradlePath = Join-Path $backendPath 'gradlew.bat'

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    Write-Host '.env 파일이 없습니다. 루트의 .env.example을 참고해 생성해 주세요.'
    exit 1
}

try {
    $settings = Read-DotEnv -Path $envPath
} catch {
    Write-Host '.env 파일을 읽을 수 없거나 KEY=VALUE 형식이 잘못되었습니다.'
    exit 1
}

$required = @('MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'JWT_ACCESS_SECRET')
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace($settings[$_]) })
if ($missing.Count -gt 0) {
    Write-Host ('필수 환경변수 누락: ' + ($missing -join ', '))
    exit 1
}

if ($settings['JWT_ACCESS_SECRET'] -match '(?i)<|>|placeholder|your[-_]|replace[-_]|change[-_]?me') {
    Write-Host 'JWT_ACCESS_SECRET에 placeholder 대신 실제 생성한 Base64 키를 설정해 주세요.'
    exit 1
}
try {
    $keyBytes = [Convert]::FromBase64String($settings['JWT_ACCESS_SECRET'])
    if ($keyBytes.Length -lt 32) {
        throw 'JWT key is too short.'
    }
} catch {
    Write-Host 'JWT_ACCESS_SECRET은 최소 32바이트 키를 Base64로 인코딩한 값이어야 합니다.'
    exit 1
} finally {
    if ($null -ne $keyBytes) {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    }
}

if (-not (Test-Path -LiteralPath $gradlePath -PathType Leaf)) {
    Write-Host '02.backend\gradlew.bat 파일을 찾을 수 없습니다.'
    exit 1
}
Write-Host '.env 설정 로드 완료'

$mapping = @{
    DB_NAME = 'MYSQL_DATABASE'
    DB_USERNAME = 'MYSQL_USER'
    DB_PASSWORD = 'MYSQL_PASSWORD'
    JWT_ACCESS_SECRET = 'JWT_ACCESS_SECRET'
    JWT_ACCESS_TTL_SECONDS = 'JWT_ACCESS_TTL_SECONDS'
    JWT_REFRESH_TTL_SECONDS = 'JWT_REFRESH_TTL_SECONDS'
    AUTH_COOKIE_SECURE = 'AUTH_COOKIE_SECURE'
}
$previousEnvironment = @{}
$locationPushed = $false
$exitCode = 1
try {
    foreach ($name in $mapping.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        # 선택 설정이 없으면 기존 셸 값을 제거해 Spring Boot 기본값을 사용한다.
        [Environment]::SetEnvironmentVariable($name, $settings[$mapping[$name]], 'Process')
    }
    Write-Host 'Backend 실행 준비 완료'
    Push-Location -LiteralPath $backendPath
    $locationPushed = $true
    Write-Host 'Spring Boot 실행 시작'
    & $gradlePath bootRun
    $exitCode = $LASTEXITCODE
} catch {
    # 예외 원문에는 환경설정 값이 포함될 수 있으므로 출력하지 않는다.
    Write-Host 'Backend 실행 중 오류가 발생했습니다.'
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
exit $exitCode
