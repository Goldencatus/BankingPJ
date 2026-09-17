# STEP 17 k6 성능 테스트

## 준비

- Java 21, MySQL, k6가 설치되어 있어야 합니다.
- 대상 DB는 반드시 `bankingpj_perf`여야 합니다.
- DB/JWT 설정은 OS 환경변수를 우선 사용하고, 없으면 루트 `.env`에서 읽습니다.
- 기본 전체 실행은 STEP 16 데이터를 reset한 뒤 지정 규모로 다시 생성합니다.
- 기존 데이터로 실행하려면 Seeder 생성 때 사용한 `SEEDER_TEST_PASSWORD`를 OS 환경변수에 설정합니다.

## 전체 실행

PowerShell에서 다음 명령을 실행합니다. 기본 규모는 `LARGE`입니다.

```powershell
cd C:\BankingPJ
.\03.infra\k6\run-performance-suite.ps1 -DatabaseName bankingpj_perf -Scale LARGE
```

이미 준비된 Seeder 데이터를 유지할 때:

```powershell
$env:SEEDER_TEST_PASSWORD = '<Seeder 실행 때 사용한 로컬 테스트 비밀번호>'
.\03.infra\k6\run-performance-suite.ps1 -DatabaseName bankingpj_perf -Scale SMALL -SkipSeedReset
```

## 읽기 전용 실행

reset과 쓰기 시나리오 없이 읽기 테스트 6개만 실행합니다.

```powershell
$env:SEEDER_TEST_PASSWORD = '<Seeder 실행 때 사용한 로컬 테스트 비밀번호>'
.\03.infra\k6\run-performance-suite.ps1 -DatabaseName bankingpj_perf -Scale SMALL -ReadOnly
```

각 읽기 시나리오는 별도 워밍업 후 실행됩니다. 지연 테스트는 10/50/100 VU로 각 60초, 처리량 테스트는 50/100/200 RPS로 각 45초 실행됩니다. 전체 모드는 일반 이체, Hot Account 이체, 멱등성 재요청도 추가로 실행합니다.

## 멱등성 시나리오만 재실행

공식 기준 결과의 8개 JSON을 보존하고 멱등성 시나리오만 reset/seed 후 측정하여 새 최종 폴더로 합칩니다.

```powershell
.\03.infra\k6\run-performance-suite.ps1 `
  -DatabaseName bankingpj_perf `
  -Scale LARGE `
  -Scenario IdempotencyReplay `
  -BaselineResultDir .\03.infra\k6\results\<공식-기준-timestamp>
```

## 결과

결과는 `03.infra/k6/results/<timestamp>/`에 생성되며 Git에서 제외됩니다.

- `<scenario>.json`: k6 원본 summary
- `<scenario>.log`: 시나리오 콘솔 로그
- `summary.csv`: 시나리오별 p50/p90/p95/p99, RPS, 실패율, 상태 코드, dropped iterations
- `report.md`: 실제 결과 비교와 병목 관찰 항목

읽기 지연 시나리오에는 반복 사이 1초 대기가 있으므로 최대 처리량 판단에는 `read-rps*` 결과를 사용합니다. 비밀번호, JWT, Access/Refresh Token은 결과 파일에 기록하지 않습니다.
