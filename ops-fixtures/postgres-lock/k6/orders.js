import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const orderLatency = new Trend('order_latency', true);
const duplicateResponses = new Counter('duplicate_responses');
export const options = {
  scenarios: { orders: { executor: 'constant-arrival-rate', rate: 4, timeUnit: '1s', duration: '20s', preAllocatedVUs: 8, maxVUs: 40 } },
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.10'],
    order_latency: ['p(95)<2500'],
  },
};

export default function () {
  const id = `00000000-0000-4000-8000-${String(__VU * 100000 + __ITER).padStart(12, '0')}`;
  const payload = JSON.stringify({ requestId: id, amountCents: 1250 });
  const params = { headers: { 'Content-Type': 'application/json' } };
  const created = http.post(`${__ENV.BASE_URL}/orders`, payload, params);
  orderLatency.add(created.timings.duration);
  check(created, { 'create succeeds': r => r.status === 201 || r.status === 200, 'amount preserved': r => r.json('amountCents') === 1250 });
  const duplicate = http.post(`${__ENV.BASE_URL}/orders`, payload, params);
  if (duplicate.status === 200 && duplicate.json('duplicate') === true) duplicateResponses.add(1);
  check(duplicate, { 'duplicate is idempotent': r => r.status === 200 && r.json('duplicate') === true });
  const queried = http.get(`${__ENV.BASE_URL}/orders/${id}`);
  check(queried, { 'query succeeds': r => r.status === 200, 'query amount preserved': r => r.json('amountCents') === 1250 });
}
