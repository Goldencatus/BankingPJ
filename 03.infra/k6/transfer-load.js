import { check } from 'k6';
import {
  baseUrl, login, newIdempotencyKey, numberEnvironment, ownedAccounts,
  postTransfer, recordScenarioResult, requiredEnvironment, responseJson,
} from './lib/common.js';

const BASE_URL = baseUrl();
const TEST_PASSWORD = requiredEnvironment('TEST_PASSWORD');
const MODE = __ENV.MODE || 'general';
const RATE = numberEnvironment('TARGET_RPS', MODE === 'hot' ? 10 : 20);
const DURATION = __ENV.DURATION || '60s';
const AMOUNT = __ENV.AMOUNT || '1.0000';

export const options = {
  scenarios: {
    transfer_load: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE), maxVUs: Math.max(100, RATE * 4),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { scenario_failed: ['rate==0'], scenario_checks: ['rate==1'] },
};

// 시나리오 사용자의 두 ACTIVE 계좌와 인증 정보를 준비한다.
export function setup() {
  const emails = (__ENV.TEST_EMAILS || requiredEnvironment('TEST_EMAIL'))
    .split(',').map((email) => email.trim()).filter(Boolean);
  const pairs = emails.map((email) => {
    const accessToken = login(BASE_URL, email, TEST_PASSWORD);
    const accounts = ownedAccounts(BASE_URL, accessToken).filter((item) => item.status === 'ACTIVE');
    const sourceId = __ENV.FROM_ACCOUNT_ID;
    const destinationId = __ENV.TO_ACCOUNT_ID;
    const source = sourceId ? accounts.find((item) => String(item.accountId) === sourceId) : accounts[0];
    const destination = destinationId ? accounts.find((item) => String(item.accountId) === destinationId) : accounts[1];
    if (!source || !destination || source.accountId === destination.accountId) {
      return null;
    }
    return { accessToken, sourceId: source.accountId, destinationNumber: destination.accountNumber };
  }).filter((pair) => pair !== null);
  if (pairs.length === 0) {
    throw new Error('이체 테스트에는 서로 다른 ACTIVE 소유 계좌 두 개가 필요합니다.');
  }
  return { pairs };
}

// 매 반복마다 새 키로 이체하고 최종 응답을 기록한다.
export default function transferLoad(data) {
  const pair = data.pairs[(__VU + __ITER) % data.pairs.length];
  const key = newIdempotencyKey(MODE);
  const payload = { fromAccountId: pair.sourceId, toAccountNumber: pair.destinationNumber, amount: AMOUNT };
  const response = postTransfer(BASE_URL, pair.accessToken, payload, key, { workload: `transfer-${MODE}` });
  const body = responseJson(response);
  const succeeded = check(response, {
    'transfer status is 200': (result) => result.status === 200,
    'transfer response is completed': () => body?.success === true && body.data?.status === 'COMPLETED',
  });
  recordScenarioResult(response, succeeded, { workload: `transfer-${MODE}` });
}
