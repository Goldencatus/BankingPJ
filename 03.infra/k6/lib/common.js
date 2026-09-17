import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const scenarioDuration = new Trend('scenario_duration', true);
export const scenarioFailed = new Rate('scenario_failed');
export const scenarioChecks = new Rate('scenario_checks');
export const scenarioRequests = new Counter('scenario_requests');
export const status200 = new Counter('status_200');
export const status400 = new Counter('status_400');
export const status401 = new Counter('status_401');
export const status404 = new Counter('status_404');
export const status409 = new Counter('status_409');
export const status500 = new Counter('status_500');
export const statusOther = new Counter('status_other');

// 필수 실행 환경값을 읽되 실제 값은 오류에 포함하지 않는다.
export function requiredEnvironment(name) {
  const value = __ENV[name];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return value;
}

// 요청 URL 조합에 사용할 기준 주소를 정규화한다.
export function baseUrl() {
  const value = requiredEnvironment('BASE_URL').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(value)) {
    throw new Error('BASE_URL은 http:// 또는 https://로 시작해야 합니다.');
  }
  return value;
}

// 숫자 실행 옵션을 안전하게 읽는다.
export function numberEnvironment(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) throw new Error(`${name}은 양수여야 합니다.`);
  return value;
}

// JSON이 아닌 응답도 검사 실패로 처리할 수 있게 null을 반환한다.
export function responseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

// 로그인하고 Access Token만 호출자에게 반환한다.
export function login(url, email, password) {
  const response = http.post(`${url}/api/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/auth/login', phase: 'setup' },
  });
  const body = responseJson(response);
  const valid = check(response, {
    'login status is 200': (result) => result.status === 200,
    'login response has access token': () => body && body.success === true
      && typeof body.data?.accessToken === 'string' && body.data.accessToken.length > 0,
  });
  if (!valid) {
    const code = body?.error?.code || 'UNKNOWN';
    fail(`로그인에 실패했습니다. status=${response.status} errorCode=${code}`);
  }
  return body.data.accessToken;
}

// 인증 사용자의 계좌 목록을 조회한다.
export function ownedAccounts(url, token) {
  const response = http.get(`${url}/api/accounts`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    tags: { name: 'GET /api/accounts', phase: 'setup' },
  });
  const body = responseJson(response);
  if (response.status !== 200 || body?.success !== true || !Array.isArray(body.data)) {
    fail(`계좌 조회에 실패했습니다. status=${response.status} errorCode=${body?.error?.code || 'UNKNOWN'}`);
  }
  return body.data;
}

// 계좌가 로그인 사용자 소유이며 ACTIVE인지 확인한다.
export function requireActiveAccount(accounts, accountId) {
  const normalized = String(accountId);
  const account = accounts.find((item) => String(item.accountId) === normalized);
  if (!account) fail('ACCOUNT_ID가 로그인 사용자의 소유 계좌가 아닙니다.');
  if (account.status !== 'ACTIVE') fail('ACCOUNT_ID 계좌가 ACTIVE 상태가 아닙니다.');
  return account;
}

// 거래내역 페이지의 공통 응답 구조를 검증한다.
export function hasValidPageResponse(body) {
  const data = body?.data;
  return body?.success === true && body.error === null && data !== null
    && Array.isArray(data?.content) && data.content.length <= 20
    && data.page === 0 && data.size === 20
    && typeof data.totalElements === 'number' && typeof data.totalPages === 'number'
    && typeof data.first === 'boolean' && typeof data.last === 'boolean';
}

// 거래내역 항목이 공개 응답 계약에 맞는지 검증한다.
export function hasValidTransactionItems(body) {
  return hasValidPageResponse(body) && body.data.content.every((item) => item
    && typeof item.ledgerEntryId === 'number'
    && (item.transferId === null || typeof item.transferId === 'number')
    && (item.type === 'DEBIT' || item.type === 'CREDIT')
    && typeof item.amount === 'number' && typeof item.balanceAfter === 'number'
    && typeof item.createdAt === 'string');
}

// 시나리오별 요청 결과와 HTTP 상태를 공통 메트릭에 기록한다.
export function recordScenarioResult(response, succeeded, tags = {}) {
  scenarioDuration.add(response.timings.duration, tags);
  scenarioFailed.add(!succeeded, tags);
  scenarioChecks.add(succeeded, tags);
  scenarioRequests.add(1, tags);
  const counter = {
    200: status200, 400: status400, 401: status401, 404: status404,
    409: status409, 500: status500,
  }[response.status] || statusOther;
  counter.add(1, tags);
}

// 논리 이체마다 충돌 가능성이 낮은 멱등성 키를 생성한다.
export function newIdempotencyKey(prefix = 'k6') {
  const vu = typeof __VU === 'undefined' ? 0 : __VU;
  const iteration = typeof __ITER === 'undefined' ? 0 : __ITER;
  return `${prefix}-${Date.now()}-${vu}-${iteration}-${Math.random().toString(16).slice(2)}`;
}

// 이체 요청을 보내며 인증 재시도에도 같은 멱등성 키를 사용한다.
export function postTransfer(url, token, payload, idempotencyKey, tags = {}) {
  return http.post(`${url}/api/transfers`, JSON.stringify(payload), {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: { name: 'POST /api/transfers', ...tags },
  });
}
