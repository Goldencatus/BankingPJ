# FinCore 개인 금융 플랫폼
## 개발 기획 및 구현 가이드 v1.0

---

# 1. 프로젝트 개요

## 1.1 프로젝트 목적

사용자가 계좌를 보유하고 계좌 간 자금이체, 거래내역 조회, 금융상품 조회 등의 업무를 수행할 수 있는 **인터넷뱅킹 형태의 금융 풀스택 서비스**를 구축한다.

본 프로젝트의 목적은 단순 CRUD 구현이 아니라 다음 금융 IT 핵심 요소를 실제 코드로 구현하고 검증하는 것이다.

- 금융 데이터 정합성
- DB Transaction
- 동시성 제어
- 이체 중복 처리 방지
- 멱등성 보장
- 인증 및 인가
- 거래 추적
- 감사 로그
- 장애 상황 처리
- 대량 요청 처리
- 대량 데이터 처리
- 성능 테스트
- 모니터링
- CI/CD 및 배포

---

# 2. 프로젝트 개발 원칙

본 프로젝트에서는 기능을 빠르게 추가하는 것보다 다음 원칙을 우선한다.

### ① 돈은 정확해야 한다.

금액 계산에 `float`, `double`을 사용하지 않는다.

Java:

```java
BigDecimal
```

DB:

```sql
DECIMAL
```

을 사용한다.

---

### ② 이체는 하나의 Transaction이다.

A 계좌 출금과 B 계좌 입금은 반드시 하나의 DB Transaction으로 처리한다.

```text
BEGIN

A 계좌 검증
↓
A 계좌 잔액 차감
↓
B 계좌 잔액 증가
↓
거래 원장 기록
↓
이체 완료

COMMIT
```

중간에 하나라도 실패하면:

```text
ROLLBACK
```

되어야 한다.

---

### ③ 거래 기록은 추적 가능해야 한다.

단순히 Account.balance만 변경하지 않는다.

별도의 거래 원장(Ledger)을 남긴다.

```text
Transfer
   ↓
┌─────────────────┐
│ Ledger Entry #1 │  A -100,000
├─────────────────┤
│ Ledger Entry #2 │  B +100,000
└─────────────────┘
```

따라서 장애가 발생했을 때

> 누가 / 언제 / 얼마를 / 어떤 계좌에서 / 어떤 계좌로 / 어떤 요청을 통해 처리했는가

를 추적할 수 있어야 한다.

---

### ④ 동일 요청이 두 번 들어와도 돈은 한 번만 움직여야 한다.

모든 이체 요청에 `Idempotency-Key`를 사용한다.

```text
POST /api/transfers

Idempotency-Key:
550e8400-e29b-41d4-a716-446655440000
```

동일 Key로 같은 요청이 다시 들어오면 새로운 이체를 만들지 않는다.

---

### ⑤ 정상 상황보다 비정상 상황을 먼저 생각한다.

항상 다음 상황을 고려한다.

```text
잔액 부족
동일 계좌 이체
없는 계좌
정지 계좌
동시 출금
이체 버튼 중복 클릭
네트워크 타임아웃
DB 오류
서버 재시작
Deadlock
API 중복 호출
```

---

# 3. 전체 시스템 구성

```text
┌───────────────────────────────┐
│           Browser             │
│                               │
│ React + TypeScript            │
│ TanStack Query                │
└───────────────┬───────────────┘
                │
             HTTPS
                │
                ▼
┌───────────────────────────────┐
│             Nginx             │
│                               │
│ Reverse Proxy                 │
└───────────────┬───────────────┘
                │
                ▼
┌──────────────────────────────────────┐
│           Spring Boot                │
│                                      │
│ Spring MVC                           │
│ Spring Security                      │
│ Service                              │
│ Transaction                          │
│ JPA / Hibernate                      │
└───────────┬──────────────┬───────────┘
            │              │
            ▼              ▼
     ┌────────────┐  ┌────────────┐
     │   MySQL    │  │   Redis    │
     │            │  │            │
     │ Account    │  │ Token      │
     │ Transfer   │  │ Cache      │
     │ Ledger     │  │ Idempotency│
     └────────────┘  └────────────┘

             │
             ▼
┌───────────────────────────────┐
│ Monitoring                    │
│                               │
│ Actuator                      │
│ Prometheus                    │
│ Grafana                       │
└───────────────────────────────┘
```

---

# 4. 최종 기술 스택

## Frontend

| 기술 | 용도 |
|---|---|
| React | SPA 구축 |
| TypeScript | 타입 안정성 |
| Vite | 개발/빌드 |
| TanStack Query | Server State 관리 |
| Axios | HTTP API |
| React Router | 라우팅 |
| React Hook Form | Form |
| Zod | 입력값 Schema 검증 |
| MUI 또는 Tailwind | UI |
| Vitest | 테스트 |
| React Testing Library | Component 테스트 |

Redux는 초기에는 사용하지 않는다.

전역 상태가 실제로 복잡해질 경우에만 Zustand 또는 Redux를 검토한다.

---

# 5. Backend

| 기술 | 용도 |
|---|---|
| Java LTS | Backend Language |
| Spring Boot | Application Framework |
| Spring MVC | REST API |
| Spring Security | 인증 / 인가 |
| Spring Data JPA | DB 접근 |
| Hibernate | ORM |
| Bean Validation | 요청 검증 |
| JWT | 인증 토큰 |
| Flyway | DB Migration |
| Gradle | Build |
| Spring Actuator | Monitoring |
| Micrometer | Metrics |

---

# 6. Database

## Main DB

```text
MySQL
```

사용 데이터:

```text
회원
계좌
계좌잔액
이체
거래원장
금융상품
감사로그
```

## Cache / Auxiliary Storage

```text
Redis
```

초기 사용 목적:

```text
Refresh Token
Idempotency
OTP
Cache
Rate Limit
```

Redis를 단순히 "사용해봤다"는 목적으로 넣지 않는다.

반드시 역할을 정의하고 사용한다.

---

# 7. Infrastructure

```text
Docker
Docker Compose
Nginx
AWS EC2
AWS RDS
GitHub Actions
```

초기 개발에서는:

```text
Docker Compose
 ├─ MySQL
 ├─ Redis
 ├─ Prometheus
 └─ Grafana
```

로 구성한다.

---

# 8. 개발 도구

## 필수

```text
IntelliJ IDEA
VS Code
Git
GitHub
Docker Desktop
DBeaver
Postman 또는 Bruno
```

## Backend Test

```text
JUnit 5
Mockito
Spring Boot Test
Testcontainers
```

특히 Testcontainers 사용을 권장한다.

실제 MySQL 환경에서 Repository 및 Transaction 테스트가 가능하기 때문이다.

---

# 9. API 문서

```text
OpenAPI
Swagger UI
```

모든 API는 최소한 다음을 문서화한다.

```text
Request
Response
HTTP Status
Error Code
Authentication
Example
```

---

# 10. 성능 테스트

```text
k6
```

또는

```text
JMeter
```

를 사용한다.

개인 프로젝트에서는 우선 k6를 권장한다.

---

# 11. 모니터링

```text
Spring Actuator
Micrometer
Prometheus
Grafana
```

확인할 주요 Metrics:

```text
TPS
Response Time
p95
p99

HTTP Error Rate

CPU
Memory
JVM Heap
GC

DB Connection Pool
DB Query Time

Redis
```

---

# 12. 프로젝트 디렉터리

Repository는 다음과 같이 구성한다.

```text
fincore/
│
├─ backend/
│
├─ frontend/
│
├─ infra/
│   ├─ docker/
│   ├─ nginx/
│   ├─ prometheus/
│   └─ grafana/
│
├─ load-test/
│   └─ k6/
│
├─ docs/
│   ├─ architecture/
│   ├─ api/
│   ├─ erd/
│   └─ performance/
│
├─ docker-compose.yml
│
├─ .env.example
├─ .gitignore
└─ README.md
```

---

# 13. STEP 0 — 개발 환경 구축

코드를 작성하기 전에 환경부터 고정한다.

## 설치

```text
JDK
Node.js LTS
Git
Docker
IntelliJ
VS Code
DBeaver
Postman/Bruno
```

확인:

```bash
java --version
node --version
npm --version
git --version
docker --version
```

---

# 14. STEP 1 — Git Repository 구성

GitHub Repository 생성:

```text
fincore
```

Branch:

```text
main
develop

feature/*
fix/*
refactor/*
```

예:

```text
feature/auth
feature/account
feature/transfer
feature/ledger
```

Commit convention 예:

```text
feat:
fix:
refactor:
test:
docs:
chore:
```

예:

```text
feat: 계좌 이체 API 구현
fix: 동시 출금 시 잔액 정합성 문제 수정
test: 이체 동시성 테스트 추가
```

---

# 15. STEP 2 — Backend Skeleton 생성

우선 Backend만 생성한다.

Dependencies:

```text
Spring Web
Spring Data JPA
Spring Security
Validation
MySQL Driver
Redis
Actuator
Flyway
Lombok
Testcontainers
```

Package 구조:

```text
com.fincore

├─ common
│   ├─ config
│   ├─ exception
│   ├─ response
│   └─ security
│
├─ user
│   ├─ controller
│   ├─ service
│   ├─ repository
│   ├─ domain
│   └─ dto
│
├─ account
│
├─ transfer
│
├─ ledger
│
└─ product
```

기능 기준 Package 구조를 사용한다.

다음과 같은 구조는 피한다.

```text
controller/
service/
repository/
entity/
```

프로젝트가 커질수록 업무 영역 파악이 어려워진다.

---

# 16. STEP 3 — Docker 개발 환경

먼저 다음만 Container로 실행한다.

```text
MySQL
Redis
```

Docker Compose:

```text
docker-compose.yml
```

환경변수:

```text
MYSQL_DATABASE
MYSQL_USER
MYSQL_PASSWORD

REDIS_HOST
REDIS_PORT
```

Git에 절대로 올리지 않는 것:

```text
.env
Password
JWT Secret
AWS Secret
DB Password
```

Git에는:

```text
.env.example
```

만 저장한다.

---

# 17. STEP 4 — DB 설계

기능 구현 전에 ERD부터 작성한다.

초기 핵심 Entity:

```text
USER

ACCOUNT

TRANSFER

LEDGER_ENTRY

IDEMPOTENCY_KEY

AUDIT_LOG
```

추후:

```text
PRODUCT
PRODUCT_SUBSCRIPTION
```

---

# 18. 핵심 ERD 개념

```text
USER
 │
 │ 1:N
 ▼
ACCOUNT

ACCOUNT
 │
 │
 ▼
LEDGER_ENTRY

TRANSFER
 │
 ├── Debit Ledger
 │
 └── Credit Ledger
```

---

# 19. Account

예:

```text
account_id

user_id

account_number

balance

status

created_at

updated_at
```

상태:

```text
ACTIVE
SUSPENDED
CLOSED
```

금액:

```sql
DECIMAL(19, 4)
```

등의 명확한 Decimal 정책을 사용한다.

---

# 20. Transfer

```text
transfer_id

from_account_id

to_account_id

amount

status

idempotency_key

created_at

completed_at
```

상태:

```text
REQUESTED

PROCESSING

COMPLETED

FAILED
```

---

# 21. Ledger Entry

거래 기록은 가능한 한 수정하지 않는 방향으로 설계한다.

```text
ledger_entry_id

transfer_id

account_id

type

amount

balance_after

created_at
```

Type:

```text
DEBIT
CREDIT
```

예:

```text
A → B 100,000원

Ledger

A
DEBIT
-100000

B
CREDIT
+100000
```

---

# 22. STEP 5 — Flyway 적용

DB Table을 Hibernate가 자동 생성하게 두지 않는다.

개발 단계부터 Flyway Migration을 사용한다.

예:

```text
V1__create_user.sql

V2__create_account.sql

V3__create_transfer.sql

V4__create_ledger.sql
```

운영 DB Schema 변경 경험을 보여주기 좋은 요소다.

---

# 23. STEP 6 — 공통 Backend 기능

실제 업무 기능 전에 다음을 구현한다.

## Response

예:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

## Error

```json
{
  "success": false,
  "error": {
    "code": "ACCOUNT_001",
    "message": "계좌를 찾을 수 없습니다."
  }
}
```

Error Code를 정의한다.

예:

```text
AUTH_001
USER_001

ACCOUNT_001
ACCOUNT_002

TRANSFER_001
TRANSFER_002
```

---

# 24. Correlation ID

모든 API Request에 추적 ID를 붙인다.

```text
X-Request-ID
```

예:

```text
a87b14fa-...
```

로그:

```text
requestId=a87b...
userId=381
transferId=12381
```

장애 발생 시 하나의 요청을 추적할 수 있게 한다.

---

# 25. STEP 7 — 회원가입 / 인증

개발 순서:

```text
회원가입
↓
로그인
↓
Token 발급
↓
인증 Filter
↓
권한 확인
↓
로그아웃
```

비밀번호는 평문 저장 금지.

```text
BCrypt
또는
Argon2
```

---

# 26. Token 설계

```text
Access Token
Refresh Token
```

Browser에서 Refresh Token은 가능한 한:

```text
HttpOnly
Secure
SameSite
```

Cookie 정책을 사용한다.

Refresh Token 자체를 DB나 Redis에 평문으로 장기간 저장하는 설계는 피한다.

---

# 27. STEP 8 — 계좌 기능

먼저 가장 단순한 Account 기능을 완성한다.

API 예:

```text
POST
/api/accounts

GET
/api/accounts

GET
/api/accounts/{accountId}
```

검증:

```text
본인 계좌인가?

계좌 상태가 ACTIVE인가?

없는 계좌인가?
```

---

# 28. STEP 9 — 입금 / 출금 기능

본격적인 Transaction 구현 전에 입금과 출금부터 작성한다.

출금:

```text
Account 조회

↓
상태 검증

↓
잔액 검증

↓
잔액 감소

↓
Ledger 기록

↓
COMMIT
```

테스트:

```text
정상 출금

잔액 부족

0원 출금

음수 출금

정지 계좌
```

---

# 29. STEP 10 — 계좌 이체

프로젝트의 핵심 기능이다.

```java
@Transactional
public void transfer(...) {
}
```

논리:

```text
Idempotency 확인

↓
출금 계좌 조회

↓
입금 계좌 조회

↓
권한 확인

↓
계좌 상태 확인

↓
잔액 확인

↓
출금

↓
입금

↓
Transfer 생성

↓
Debit Ledger 생성

↓
Credit Ledger 생성

↓
COMMIT
```

---

# 30. 매우 중요한 포인트 — Lock

같은 계좌에서 동시에 출금 요청이 들어올 수 있다.

```text
잔액 100만원

Thread A
80만원 출금

Thread B
80만원 출금
```

이를 반드시 방지해야 한다.

초기 구현에서는:

```text
Pessimistic Lock
SELECT ... FOR UPDATE
```

방식을 추천한다.

---

# 31. Deadlock 방지

A → B와 B → A가 동시에 발생할 수 있다.

따라서 Account Lock 순서를 일관되게 만든다.

예:

```text
accountId가 작은 계좌부터 Lock
```

즉:

```text
min(accountA, accountB)

↓

max(accountA, accountB)
```

순서로 Lock한다.

이 부분은 금융 프로젝트에서 매우 좋은 기술 설명 포인트가 된다.

---

# 32. STEP 11 — 멱등성

Frontend에서:

```text
UUID 생성
```

↓

```text
Idempotency-Key
```

Header에 전달.

Backend:

```text
Idempotency-Key 조회

없음
↓
거래 수행
↓
저장

있음
↓
기존 결과 반환
```

DB에는 반드시:

```text
UNIQUE INDEX
```

를 두어 Race Condition도 막는다.

Application 로직만으로 중복 처리를 막으면 안 된다.

---

# 33. STEP 12 — 거래내역

API:

```text
GET /api/accounts/{accountId}/transactions
```

Pagination 필수.

하지 말아야 할 것:

```text
SELECT *
```

전체 데이터 반환.

사용:

```text
page
size
cursor
```

데이터가 많아질 것을 고려하면 이후 Cursor Pagination도 실험한다.

---

# 34. Index 설계

예:

```text
account_id

created_at

transfer_id

idempotency_key
```

복합 Index를 실제 Query 기준으로 설계한다.

무작정 Index를 많이 추가하지 않는다.

`EXPLAIN`으로 Execution Plan을 확인한다.

---

# 35. STEP 13 — Frontend 시작

Backend 핵심 API가 안정화된 뒤 Frontend를 본격적으로 만든다.

페이지:

```text
/login

/dashboard

/accounts

/accounts/:id

/transfer

/transactions

/products

/admin
```

---

# 36. 화면 개발 순서

```text
Login
↓
Layout
↓
Account List
↓
Account Detail
↓
Transfer
↓
Transaction History
↓
Dashboard
```

---

# 37. Frontend에서 반드시 처리할 것

```text
Loading

Error

Empty

Success

Unauthorized

Forbidden
```

정상 화면만 만들지 않는다.

특히 이체 버튼은:

```text
요청 중 Disable
```

처리한다.

그러나 이것만으로 중복 이체를 막았다고 생각하면 안 된다.

Backend Idempotency가 최종 방어선이다.

---

# 38. STEP 14 — 테스트 자동화

테스트는 개발 마지막에 하는 것이 아니다.

각 업무 기능과 같이 작성한다.

Test Pyramid:

```text
             E2E
              ▲
         Integration
              ▲
           Unit
```

---

# 39. 반드시 필요한 Backend Test

## Account

```text
계좌 생성

정상 출금

잔액 부족

정지 계좌
```

## Transfer

```text
정상 이체

잔액 부족

동일 계좌 이체

정지 계좌

없는 계좌

Transaction Rollback
```

## Idempotency

```text
동일 요청 10회

↓

실제 Transfer 1건
```

---

# 40. 반드시 해야 할 Transaction 테스트

일부러 오류를 발생시킨다.

```text
A 계좌 -100,000

↓

Exception 발생

↓

B 계좌 입금 이전 오류
```

검증:

```text
A 잔액 = 기존값

B 잔액 = 기존값

Ledger = 생성되지 않음
```

즉 Rollback을 실제 테스트한다.

---

# 41. STEP 15 — 동시성 테스트

예:

```text
Account

Balance
1,000,000원
```

100개의 Thread가 동시에:

```text
10,000원
```

출금.

정상 결과:

```text
잔액 = 0
```

또는 잔액을 초과하는 요청이라면 성공/실패 건수가 정확해야 한다.

---

# 42. Thread 기반 Integration Test

Java:

```text
ExecutorService

CountDownLatch
```

등을 사용해서 실제 동시 요청을 만들어본다.

이 단계에서:

```text
Lock 없음

vs

Pessimistic Lock
```

결과를 비교한다.

---

# 43. STEP 16 — 대량 데이터 준비

대량 테스트용 Data Seeder를 만든다.

예:

```text
User 100,000

Account 200,000

Transfer 1,000,000

Ledger 2,000,000
```

데이터의 절대량보다 중요한 것은:

```text
데이터 증가에 따른 Query 성능 변화
```

를 기록하는 것이다.

---

# 44. STEP 17 — k6 Load Test

다음 Scenario를 각각 분리한다.

## Scenario A

계좌 조회

```text
GET /accounts
```

## Scenario B

거래내역 조회

```text
GET /transactions
```

## Scenario C

이체

```text
POST /transfers
```

## Scenario D

Hot Account

```text
같은 Account에 다수 요청
```

## Scenario E

중복 요청

```text
같은 Idempotency-Key 요청
```

---

# 45. Load 증가

단계적으로 올린다.

```text
10 VU

50 VU

100 VU

300 VU

500 VU

1000 VU
```

여기서 VU 숫자 자체를 목표로 삼지 않는다.

환경별 Hardware가 다르기 때문이다.

---

# 46. 측정 항목

```text
TPS

Average Response

p95

p99

Error Rate

CPU

Memory

GC

DB Connection

DB Query Time
```

---

# 47. 성능 테스트보다 더 중요한 금융 검증

Load Test 후:

```sql
SELECT SUM(balance)
FROM account;
```

확인.

이체만 발생했다면:

```text
전체 자산 총합

Before

=

After
```

여야 한다.

그리고:

```text
Debit 합

=

Credit 합
```

도 검증한다.

이 검증을 자동화한다.

---

# 48. STEP 18 — DB Performance

Slow Query를 찾는다.

확인:

```text
EXPLAIN

Index

N+1

Pagination

Connection Pool
```

특히 JPA에서 N+1 문제를 확인한다.

---

# 49. Connection Pool

Spring Boot의 기본적인 HikariCP를 사용한다.

검토 항목:

```text
maximumPoolSize

connectionTimeout

idleTimeout

maxLifetime
```

숫자를 인터넷에서 그대로 복사하지 않는다.

부하 테스트 결과를 기준으로 변경한다.

---

# 50. STEP 19 — Redis 적용

처음부터 모든 데이터를 Redis에 Cache하지 않는다.

실제 병목을 발견한 후 도입한다.

좋은 사용 예:

```text
Refresh Token

OTP

Rate Limiting

일부 조회 Cache

Idempotency 보조 처리
```

금융 원장의 Source of Truth는 Redis가 아닌 MySQL로 유지한다.

---

# 51. STEP 20 — Batch 기능

여기까지 완료됐다면 대량 데이터 처리 기능을 추가한다.

사용:

```text
Spring Batch
```

예제 업무:

> 매일 전체 계좌의 이자를 계산한다.

```text
1,000,000 Account

↓

Read

↓

Interest 계산

↓

Transaction 생성

↓

Ledger 생성
```

---

# 52. Batch 비교 테스트

다음과 같이 비교한다.

```text
일반 반복문

vs

Chunk 100

vs

Chunk 1,000

vs

Chunk 5,000
```

결과:

```text
처리 건수
처리 시간
CPU
Memory
DB Load
```

를 기록한다.

---

# 53. STEP 21 — Monitoring 구축

Spring:

```text
Actuator

+

Micrometer
```

↓

```text
Prometheus
```

↓

```text
Grafana
```

Dashboard:

```text
Request/sec

Latency

Error Rate

JVM

Heap

GC

DB Connection Pool
```

---

# 54. STEP 22 — Logging

금융 데이터는 로그에 함부로 남기지 않는다.

금지:

```text
Password

전체 JWT

주민번호

전체 계좌번호

카드번호

OTP
```

계좌번호를 출력해야 한다면 Masking한다.

```text
123-****-789
```

---

# 55. Audit Log

일반 Application Log와 Audit Log를 구분한다.

Audit:

```text
userId

action

resource

result

timestamp

requestId
```

예:

```text
userId=3821

action=TRANSFER

resource=ACCOUNT

result=SUCCESS
```

---

# 56. STEP 23 — Security

확인:

```text
Authentication

Authorization

CORS

CSRF

XSS

SQL Injection

Rate Limiting

Secret Management

Password Hashing

Sensitive Data Masking
```

Frontend 입력 검증만 믿지 않는다.

Backend에서 반드시 다시 검증한다.

---

# 57. STEP 24 — Dockerize

이제 Backend와 Frontend도 Container화한다.

```text
Nginx

Frontend

Backend

MySQL

Redis

Prometheus

Grafana
```

Local:

```bash
docker compose up
```

한 번으로 전체 서비스를 실행할 수 있게 한다.

---

# 58. STEP 25 — CI

GitHub Actions:

```text
Push / Pull Request

↓

Backend Compile

↓

Backend Test

↓

Frontend Build

↓

Frontend Test

↓

Docker Image Build
```

Test 실패 시 Merge하지 않는 흐름을 만든다.

---

# 59. STEP 26 — CD

초기 포트폴리오는:

```text
GitHub

↓

GitHub Actions

↓

Docker Image

↓

AWS EC2

↓

Docker Container
```

정도로 충분하다.

DB는 가능하면:

```text
AWS RDS
```

를 분리한다.

---

# 60. STEP 27 — 운영 장애 테스트

일부러 장애를 만든다.

예:

```text
Backend Restart

Redis Shutdown

DB Connection 부족

잘못된 API Request

Timeout

Duplicate Request

Deadlock
```

그리고 시스템이 어떻게 반응하는지 기록한다.

이게 금융 IT 프로젝트의 완성도를 크게 높인다.

---

# 61. 구현 우선순위

반드시 다음 순서로 진행한다.

```text
1. 개발 환경

↓

2. Git

↓

3. Backend Skeleton

↓

4. Docker MySQL / Redis

↓

5. ERD

↓

6. Flyway

↓

7. 공통 Response / Exception

↓

8. 회원 / 인증

↓

9. 계좌

↓

10. 입출금

↓

11. Transfer

↓

12. Ledger

↓

13. Transaction

↓

14. Lock

↓

15. Idempotency

↓

16. 거래내역

↓

17. Frontend

↓

18. Unit Test

↓

19. Integration Test

↓

20. Concurrency Test

↓

21. 대량 데이터 생성

↓

22. k6 Load Test

↓

23. DB 최적화

↓

24. Redis

↓

25. Spring Batch

↓

26. Prometheus / Grafana

↓

27. Security 점검

↓

28. Docker

↓

29. CI/CD

↓

30. AWS

↓

31. 장애 테스트

↓

32. 결과 문서화
```

---

# 62. 초기에는 하지 않을 것

초반부터 다음 기술을 넣지 않는다.

```text
Kafka

Kubernetes

MSA

Elasticsearch

MongoDB

CQRS

Event Sourcing
```

이 기술들이 나쁘다는 뜻이 아니다.

현재 규모에서는 사용 이유를 설명하기 어렵기 때문이다.

성능이나 구조상의 실제 문제가 발생하면 그때 도입한다.

---

# 63. 특히 Kafka

단순히:

> 금융권에서 Kafka를 많이 쓴다고 해서

넣지 않는다.

추후:

```text
이체 완료

↓

이벤트 발생

↓

알림 시스템

↓
Push

↓
Email

↓
통계

↓
Fraud Detection
```

같은 비동기 요구사항이 생겼을 때 Kafka 도입을 검토한다.

---

# 64. 개발하면서 반드시 남길 문서

`docs/`에 다음을 남긴다.

```text
ERD

Architecture

API Specification

Transaction 설계

Lock 설계

Idempotency 설계

Index 설계

성능 테스트

장애 테스트

Troubleshooting
```

---

# 65. 성능 개선 문서 예시

좋은 포트폴리오 문서는 다음과 같은 형태다.

```text
문제

거래내역 100만 건에서
조회 API p95가 1.8초 발생


원인

account_id + created_at
Index 없음


분석

EXPLAIN 결과 Full Scan


개선

Composite Index 추가


결과

p95

1.8s
↓

240ms
```

숫자는 반드시 본인의 실제 측정 결과를 사용한다.

---

# 66. 동시성 개선 문서 예시

```text
문제

같은 계좌에 100개의
동시 출금 요청 발생


결과

Lost Update 발생


원인

Read → Modify → Write 과정에서
동시 접근 제어 없음


대안

Optimistic Lock

Pessimistic Lock


선택

Pessimistic Lock


이유

동일 계좌에 대한 충돌 상황에서
명확한 직렬화가 필요


추가 개선

Account ID 순서대로 Lock하여
Deadlock 위험 감소
```

이런 내용이 금융권 면접에서 강력한 설명 소재가 된다.

---

# 67. 개발 완료 체크리스트

## Backend

- [ ] 회원가입
- [ ] 로그인
- [ ] 인증/인가
- [ ] 계좌 생성
- [ ] 계좌 조회
- [ ] 입금
- [ ] 출금
- [ ] 이체
- [ ] 거래 원장
- [ ] 거래내역
- [ ] Transaction
- [ ] Lock
- [ ] Idempotency
- [ ] Validation
- [ ] Exception
- [ ] Audit

## Frontend

- [ ] Login
- [ ] Dashboard
- [ ] Account
- [ ] Transfer
- [ ] Transaction History
- [ ] Loading
- [ ] Error Handling
- [ ] Authentication

## DB

- [ ] ERD
- [ ] FK
- [ ] UNIQUE
- [ ] INDEX
- [ ] Migration
- [ ] Transaction 확인

## Test

- [ ] Unit Test
- [ ] Integration Test
- [ ] Transaction Test
- [ ] Concurrency Test
- [ ] Duplicate Test
- [ ] Load Test
- [ ] Batch Test

## Infrastructure

- [ ] Docker
- [ ] Docker Compose
- [ ] Nginx
- [ ] GitHub Actions
- [ ] AWS

## Monitoring

- [ ] Actuator
- [ ] Prometheus
- [ ] Grafana
- [ ] Logging
- [ ] Request ID

## Security

- [ ] Password Hash
- [ ] Secret 분리
- [ ] Token 보안
- [ ] Input Validation
- [ ] Authorization
- [ ] Log Masking
- [ ] Rate Limiting 검토

---

# 68. 이 프로젝트에서 개발자가 가장 신경 써야 하는 부분

우선순위는 다음과 같다.

```text
1순위
금액 데이터 정합성

2순위
Transaction

3순위
동시성 제어

4순위
중복 거래 방지

5순위
거래 추적

6순위
인증 / 인가 / 보안

7순위
DB Query 및 Index

8순위
성능

9순위
장애 대응

10순위
UI
```

금융 프로젝트에서는 UI가 멋진 것보다:

> 동시에 1,000건의 요청이 들어와도 돈이 틀리지 않는다.

라는 사실이 훨씬 중요하다.

---

# 69. 최종 포트폴리오 목표

프로젝트 완료 후 다음 질문에 답할 수 있어야 한다.

### Q. 이체 도중 서버 오류가 발생하면?

```text
DB Transaction을 이용하여 전체 Rollback
```

### Q. 이체 버튼을 두 번 누르면?

```text
Idempotency-Key + UNIQUE Constraint
```

### Q. 같은 계좌에서 동시에 출금하면?

```text
Pessimistic Lock을 통한 동시성 제어
```

### Q. Deadlock은?

```text
Account Lock 순서를 일관되게 유지
```

### Q. 100만 건 데이터에서 조회가 느리다면?

```text
EXPLAIN을 통해 Query 분석
→ Index 설계
→ 개선 전후 성능 측정
```

### Q. 서버가 어느 정도의 요청을 처리할 수 있나?

```text
k6 Load Test

+

Prometheus / Grafana

↓

TPS / p95 / p99 / Error Rate 측정
```

### Q. 대량 데이터를 한 번에 처리해야 한다면?

```text
Spring Batch

+

Chunk Processing
```

### Q. 제대로 처리됐다는 걸 어떻게 증명했나?

```text
Integration Test

Concurrency Test

Load Test

Balance 검증

Debit/Credit 검증
```

이 질문들에 코드와 실제 측정 결과를 근거로 답할 수 있게 만드는 것이 이 프로젝트의 최종 목표다.