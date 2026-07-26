// M2-2: HOT_ACCOUNT_CONTENTION_V1 load profile
// 85% traffic to hot-0001, 15% to 99 normal accounts
// 20 req/s constant arrival rate, 90s duration
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const orderLatency = new Trend('order_latency', true);
const duplicateResponses = new Counter('duplicate_responses');
const hotAccountOrders = new Counter('hot_account_orders');
const normalAccountOrders = new Counter('normal_account_orders');

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '90s',
      preAllocatedVUs: 20,
      maxVUs: 40,
    },
  },
  thresholds: {
    checks: ['rate>0.90'],
    http_req_failed: ['rate<0.20'],
    order_latency: ['p(95)<5000'],
  },
};

// Account pool: hot-0001 + acct-0001..acct-0099
const HOT_ACCOUNT = 'hot-0001';
const NORMAL_COUNT = 99;
const HOT_PROBABILITY = 0.85;

function selectAccount(vu, iter) {
  // Deterministic selection based on hash of (vu, iter)
  const hash = ((vu * 31337 + iter * 17) ^ (vu << 5)) & 0x7FFFFFFF;
  const normalized = hash / 0x7FFFFFFF;
  if (normalized < HOT_PROBABILITY) {
    hotAccountOrders.add(1);
    return HOT_ACCOUNT;
  }
  normalAccountOrders.add(1);
  // Re-normalize: map [HOT_PROBABILITY, 1.0) → [0, NORMAL_COUNT)
  const normalIdx = Math.floor((normalized - HOT_PROBABILITY) / (1.0 - HOT_PROBABILITY) * NORMAL_COUNT);
  return 'acct-' + String(normalIdx + 1).padStart(4, '0');
}

export default function () {
  const accountId = selectAccount(__VU, __ITER);
  // Deterministic request ID from VU + iteration
  const id = `00000000-0000-4000-8000-${String(__VU * 100000 + __ITER).padStart(12, '0')}`;
  const payload = JSON.stringify({
    requestId: id,
    accountId: accountId,
    amountCents: 1250
  });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const created = http.post(`${__ENV.BASE_URL}/orders`, payload, params);
  orderLatency.add(created.timings.duration);
  check(created, {
    'create succeeds': r => r.status === 201 || r.status === 200,
    'amount preserved': r => r.json('amountCents') === 1250,
  });

  // Verify idempotency
  const duplicate = http.post(`${__ENV.BASE_URL}/orders`, payload, params);
  if (duplicate.status === 200 && duplicate.json('duplicate') === true) duplicateResponses.add(1);
  check(duplicate, {
    'duplicate is idempotent': r => r.status === 200 && r.json('duplicate') === true,
  });
}
