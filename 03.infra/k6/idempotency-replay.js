import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import {
  baseUrl, login, newIdempotencyKey, numberEnvironment, ownedAccounts,
  postTransfer, recordScenarioResult, requiredEnvironment, responseJson,
} from './lib/common.js';

const BASE_URL = baseUrl();
const TEST_EMAIL = requiredEnvironment('TEST_EMAIL').trim();
const TEST_PASSWORD = requiredEnvironment('TEST_PASSWORD');
const RATE = numberEnvironment('TARGET_RPS', 20);
const DURATION = __ENV.DURATION || '45s';
const transferIdMatch = new Rate('transfer_id_match');
const duplicateTransferPrevented = new Rate('duplicate_transfer_prevented');

export const options = {
  scenarios: {
    idempotency_replay: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE), maxVUs: Math.max(100, RATE * 3),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    scenario_failed: ['rate==0'], scenario_checks: ['rate==1'],
    transfer_id_match: ['rate==1'], duplicate_transfer_prevented: ['rate==1'],
  },
};

// 계좌 거래내역의 전체 건수를 중복 생성 검증에 사용한다.
function transactionTotal(accessToken, accountId, phase) {
  const response = http.get(`${BASE_URL}/api/accounts/${accountId}/transactions?page=0&size=1`, {
    headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
    tags: { name: 'GET /api/accounts/:accountId/transactions', phase },
  });
  const body = responseJson(response);
  if (response.status !== 200 || body?.success !== true || typeof body.data?.totalElements !== 'number') {
    throw new Error(`거래내역 건수 조회에 실패했습니다. phase=${phase} status=${response.status}`);
  }
  return body.data.totalElements;
}

// 최초 이체를 만들고 재사용할 요청 본문, 키, transferId를 준비한다.
export function setup() {
  const accessToken = login(BASE_URL, TEST_EMAIL, TEST_PASSWORD);
  const accounts = ownedAccounts(BASE_URL, accessToken).filter((item) => item.status === 'ACTIVE');
  if (accounts.length < 2) throw new Error('멱등성 테스트에는 ACTIVE 소유 계좌 두 개가 필요합니다.');
  const sourceBefore = transactionTotal(accessToken, accounts[0].accountId, 'before-replay');
  const destinationBefore = transactionTotal(accessToken, accounts[1].accountId, 'before-replay');
  const payload = { fromAccountId: accounts[0].accountId, toAccountNumber: accounts[1].accountNumber, amount: '1.0000' };
  const key = newIdempotencyKey('replay');
  const response = postTransfer(BASE_URL, accessToken, payload, key, { workload: 'idempotency-setup' });
  const body = responseJson(response);
  if (response.status !== 200 || body?.success !== true || typeof body.data?.transferId !== 'number') {
    throw new Error(`최초 멱등성 이체에 실패했습니다. status=${response.status}`);
  }
  return {
    accessToken, payload, key, transferId: body.data.transferId,
    sourceId: accounts[0].accountId, destinationId: accounts[1].accountId,
    sourceBefore, destinationBefore,
  };
}

// 동일한 사용자, 본문, 키로 재요청하여 최초 transferId 재사용을 검증한다.
export default function replayTransfer(data) {
  const response = postTransfer(BASE_URL, data.accessToken, data.payload, data.key, { workload: 'idempotency-replay' });
  const body = responseJson(response);
  const matched = response.status === 200 && body?.success === true && body.data?.transferId === data.transferId;
  const succeeded = check(response, {
    'replay status is 200': (result) => result.status === 200,
    'replay transferId matches original': () => matched,
  });
  transferIdMatch.add(matched);
  recordScenarioResult(response, succeeded, { workload: 'idempotency-replay' });
}

// 반복 재요청 뒤 Ledger가 최초 이체 한 건분만 증가했는지 확인한다.
export function teardown(data) {
  const sourceAfter = transactionTotal(data.accessToken, data.sourceId, 'after-replay');
  const destinationAfter = transactionTotal(data.accessToken, data.destinationId, 'after-replay');
  const prevented = sourceAfter === data.sourceBefore + 1 && destinationAfter === data.destinationBefore + 1;
  check(null, { 'replay does not create additional transfer ledger': () => prevented });
  duplicateTransferPrevented.add(prevented);
}
