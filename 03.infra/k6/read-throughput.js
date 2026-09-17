import http from 'k6/http';
import { check } from 'k6';
import {
  baseUrl, hasValidPageResponse, hasValidTransactionItems, login,
  numberEnvironment, ownedAccounts, recordScenarioResult, requireActiveAccount,
  requiredEnvironment, responseJson,
} from './lib/common.js';

const BASE_URL = baseUrl();
const TEST_EMAIL = requiredEnvironment('TEST_EMAIL').trim();
const TEST_PASSWORD = requiredEnvironment('TEST_PASSWORD');
const ACCOUNT_ID = requiredEnvironment('ACCOUNT_ID').trim();
const TARGET_RPS = numberEnvironment('TARGET_RPS', 50);
const DURATION = __ENV.DURATION || '45s';

export const options = {
  scenarios: {
    read_throughput: {
      executor: 'constant-arrival-rate', rate: TARGET_RPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(20, TARGET_RPS), maxVUs: Math.max(100, TARGET_RPS * 2),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { scenario_failed: ['rate==0'], scenario_checks: ['rate==1'] },
};

// 로그인과 계좌 소유권을 실행 전에 한 번 검증한다.
export function setup() {
  const accessToken = login(BASE_URL, TEST_EMAIL, TEST_PASSWORD);
  requireActiveAccount(ownedAccounts(BASE_URL, accessToken), ACCOUNT_ID);
  return { accessToken };
}

// 지정한 도착률로 거래내역 첫 페이지를 반복 조회한다.
export default function readTransactions(data) {
  const response = http.get(`${BASE_URL}/api/accounts/${ACCOUNT_ID}/transactions?page=0&size=20`, {
    headers: { Authorization: `Bearer ${data.accessToken}`, Accept: 'application/json' },
    tags: { name: 'GET /api/accounts/:accountId/transactions', workload: 'read-throughput' },
  });
  const body = responseJson(response);
  const succeeded = check(response, {
    'transactions status is 200': (result) => result.status === 200,
    'transactions page response is valid': () => hasValidPageResponse(body),
    'transactions item structure is valid': () => hasValidTransactionItems(body),
  });
  recordScenarioResult(response, succeeded, { workload: 'read-throughput' });
}
