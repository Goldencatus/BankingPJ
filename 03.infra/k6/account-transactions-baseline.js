import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  baseUrl, hasValidPageResponse, hasValidTransactionItems, login,
  numberEnvironment, ownedAccounts, recordScenarioResult, requireActiveAccount,
  requiredEnvironment, responseJson,
} from './lib/common.js';

const BASE_URL = baseUrl();
const TEST_EMAIL = requiredEnvironment('TEST_EMAIL').trim();
const TEST_PASSWORD = requiredEnvironment('TEST_PASSWORD');
const ACCOUNT_ID = requiredEnvironment('ACCOUNT_ID').trim();
const VUS = numberEnvironment('VUS', 10);
const DURATION = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    read_latency: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { scenario_failed: ['rate==0'], scenario_checks: ['rate==1'] },
};

// 로그인과 조회 계좌의 소유권 및 상태를 실행 전에 확인한다.
export function setup() {
  const accessToken = login(BASE_URL, TEST_EMAIL, TEST_PASSWORD);
  requireActiveAccount(ownedAccounts(BASE_URL, accessToken), ACCOUNT_ID);
  return { accessToken };
}

// 각 VU가 거래내역 첫 페이지를 반복 조회해 지연시간을 측정한다.
export default function transactionsBaseline(data) {
  const response = http.get(`${BASE_URL}/api/accounts/${ACCOUNT_ID}/transactions?page=0&size=20`, {
    headers: { Authorization: `Bearer ${data.accessToken}`, Accept: 'application/json' },
    tags: { name: 'GET /api/accounts/:accountId/transactions', workload: 'read-latency' },
  });
  const body = responseJson(response);
  const succeeded = check(response, {
    'transactions status is 200': (result) => result.status === 200,
    'transactions page response is valid': () => hasValidPageResponse(body),
    'transactions item structure is valid': () => hasValidTransactionItems(body),
  });
  recordScenarioResult(response, succeeded, { workload: 'read-latency' });
  sleep(1);
}
