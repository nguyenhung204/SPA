import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Config — override via -e VAR=value
// ---------------------------------------------------------------------------
const BASE_URL   = __ENV.BASE_URL    || 'http://192.168.10.227';
const SEED_COUNT = Number(__ENV.SEED_COUNT  || 100);
const SEED_STOCK = Number(__ENV.SEED_STOCK  || 1000);
const QUANTITY   = Number(__ENV.QUANTITY    || 1);
const USER_PREFIX = __ENV.USER_PREFIX || 'flash-sale-user';

const TOTAL_STOCK = SEED_COUNT * SEED_STOCK; // 100 × 1000 = 100 000

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------
export const checkoutSuccess        = new Counter('checkout_success_200');
export const checkoutSoldOut        = new Counter('checkout_sold_out_400');
export const checkoutCartEmpty      = new Counter('checkout_cart_empty_400');
export const checkoutBadRequestOther= new Counter('checkout_bad_request_other_400');
export const checkoutServerErrors   = new Counter('checkout_server_errors_5xx');
export const checkoutUnexpected     = new Counter('checkout_unexpected_status');
export const checkoutLatency        = new Trend('checkout_latency_ms', true);
export const cartAddFailures        = new Counter('cart_add_failures');
export const serverErrorRate        = new Rate('server_error_rate');

// ---------------------------------------------------------------------------
// Scenario
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    flash_sale_1000_ccu: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: 1000 },
        { duration: '30s', target: 1000 },
        { duration: '10s', target: 0  },
      ],
      gracefulRampDown: '0s',
    },
  },
  thresholds: {
    // Latency chỉ đo, không fail — comment in khi cần SLA cứng
    // checkout_latency_ms: ['p(95)<2000'],
    server_error_rate:           ['rate==0'],
    checkout_server_errors_5xx:  ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ---------------------------------------------------------------------------
// setup() — runs ONCE before all VUs start
// Seeds 100 products × 1000 stock, returns productIds to all VUs
// ---------------------------------------------------------------------------
export function setup() {
  console.log(`[Setup] Seeding ${SEED_COUNT} products × stock=${SEED_STOCK} (total=${TOTAL_STOCK})...`);
  const res = http.post(`${BASE_URL}/admin/seed?count=${SEED_COUNT}&stock=${SEED_STOCK}`);
  if (res.status !== 200) {
    throw new Error(`[Setup] Seed failed — status=${res.status} body=${res.body}`);
  }
  const body = res.json();
  const productIds = body.productIds;
  if (!productIds || productIds.length === 0) {
    throw new Error('[Setup] Seed returned no productIds');
  }
  console.log(`[Setup] Seed OK — ${productIds.length} products loaded into Hazelcast`);
  return { productIds };
}

// ---------------------------------------------------------------------------
// default — runs per VU iteration
// Each iteration: add ONE product to cart → checkout
// userId = unique per VU+ITER → fresh cart every time, no cross-contamination
// ---------------------------------------------------------------------------
export default function (data) {
  const productIds = data.productIds;

  // Round-robin across the product pool so all 100 products get hit evenly
  const productId = productIds[(__VU + __ITER) % productIds.length];
  const userId    = `${USER_PREFIX}-${__VU}-${__ITER}`;

  // --- Step 1: Add to cart ---------------------------------------------------
  const addRes = http.post(
    `${BASE_URL}/cart/add`,
    JSON.stringify({ userId, productId, quantity: QUANTITY }),
    { headers: { 'Content-Type': 'application/json' }, tags: { api: 'cart_add' } },
  );

  const cartOk = check(addRes, { 'cart/add: 200': (r) => r.status === 200 });
  if (!cartOk) {
    cartAddFailures.add(1);
    // Chỉ log 1% để tránh spam console dưới tải lớn
    if (Math.random() < 0.01) {
      console.warn(`[cart/add] FAIL userId=${userId} status=${addRes.status} body=${addRes.body}`);
    }
    return; // no point checking out with an empty cart
  }

  sleep(0.5);

  // --- Step 2: Checkout (one product, sequential per-product deduction) ------
  const checkoutRes = http.post(
    `${BASE_URL}/checkout?userId=${encodeURIComponent(userId)}`,
    null,
    { tags: { api: 'checkout' } },
  );

  checkoutLatency.add(checkoutRes.timings.duration);
  serverErrorRate.add(checkoutRes.status >= 500);

  const body = checkoutRes.body || '';

  if (checkoutRes.status === 200) {
    checkoutSuccess.add(1);
  } else if (checkoutRes.status === 400 && body.includes('Sold Out')) {
    checkoutSoldOut.add(1);
  } else if (checkoutRes.status === 400 && body.includes('Cart is empty')) {
    // Should not happen since cart/add succeeded — flag for investigation
    checkoutCartEmpty.add(1);
    console.warn(`[checkout] CartEmpty userId=${userId}`);
  } else if (checkoutRes.status === 400) {
    checkoutBadRequestOther.add(1);
    console.warn(`[checkout] 400 Other userId=${userId} body=${body}`);
  } else if (checkoutRes.status >= 500) {
    checkoutServerErrors.add(1);
    console.error(`[checkout] 5xx userId=${userId} status=${checkoutRes.status} body=${body}`);
  } else {
    checkoutUnexpected.add(1);
  }

  check(checkoutRes, {
    'checkout: 200 or Sold Out': (r) =>
      r.status === 200 || (r.status === 400 && (r.body || '').includes('Sold Out')),
    'checkout: no 5xx': (r) => r.status < 500,
  });
}

// ---------------------------------------------------------------------------
// teardown() — runs ONCE after all VUs finish
// Fetches live Hazelcast stock via /admin/products to show what's left
// ---------------------------------------------------------------------------
export function teardown() {
  console.log('[Teardown] Fetching remaining stock from Hazelcast...');

  // Retry tối đa 3 lần — server có thể vẫn đang drain sau khi test
  let res;
  for (let attempt = 1; attempt <= 3; attempt++) {
    res = http.get(`${BASE_URL}/admin/products`);
    if (res.status === 200) break;
    console.warn(`[Teardown] /admin/products attempt=${attempt} status=${res.status} — retrying...`);
    sleep(2);
  }

  if (!res || res.status !== 200) {
    console.error(`[Teardown] /admin/products failed after 3 attempts — status=${res ? res.status : 'N/A'} body=${res ? res.body : ''}`);
    console.log('[Teardown] Fallback: stock summary unavailable. Check MongoDB orders count manually.');
    return;
  }

  const products = (res.json().products || []).sort((a, b) => a.name.localeCompare(b.name));
  let totalRemaining = 0;
  products.forEach((p) => { totalRemaining += p.stock; });

  const totalSold = TOTAL_STOCK - totalRemaining;

  console.log('');
  console.log('╔══ STOCK SUMMARY (Hazelcast live) ═══════════════════════╗');
  console.log(`║  Initial  : ${SEED_COUNT} products × ${SEED_STOCK} = ${TOTAL_STOCK} units`);
  console.log(`║  Sold     : ${totalSold} units`);
  console.log(`║  Remaining: ${totalRemaining} units`);
  console.log('╠══ Per-product ═══════════════════════════════════════════╣');
  products.forEach((p) => {
    const sold = SEED_STOCK - p.stock;
    console.log(`║  ${p.name.padEnd(12)} sold=${String(sold).padStart(5)}  left=${p.stock}`);
  });
  console.log('╚══════════════════════════════════════════════════════════╝');
}

// ---------------------------------------------------------------------------
// handleSummary — final report printed + written to JSON
// ---------------------------------------------------------------------------
export function handleSummary(data) {
  const success      = data.metrics.checkout_success_200?.values?.count         || 0;
  const soldOut      = data.metrics.checkout_sold_out_400?.values?.count        || 0;
  const cartEmpty    = data.metrics.checkout_cart_empty_400?.values?.count      || 0;
  const badOther     = data.metrics.checkout_bad_request_other_400?.values?.count || 0;
  const serverErrors = data.metrics.checkout_server_errors_5xx?.values?.count   || 0;
  const cartFails    = data.metrics.cart_add_failures?.values?.count            || 0;
  const p95  = data.metrics.checkout_latency_ms?.values?.['p(95)'] || 0;
  const p99  = data.metrics.checkout_latency_ms?.values?.['p(99)'] || 0;
  const avg  = data.metrics.checkout_latency_ms?.values?.avg        || 0;
  const pass = serverErrors === 0;

  const text = `
FLASH SALE 1000 CCU — SPACE-BASED ARCHITECTURE
===============================================
Seed            : ${SEED_COUNT} products × ${SEED_STOCK} stock = ${TOTAL_STOCK} total units

--- Checkout results ---
200 OK (ordered) : ${success}
400 Sold Out     : ${soldOut}
400 Cart Empty   : ${cartEmpty}  (unexpected — check cart_add_failures)
400 Other        : ${badOther}
5xx Server Error : ${serverErrors}
Cart add failures: ${cartFails}

--- Latency (checkout only) ---
avg : ${avg.toFixed(2)} ms
p95 : ${p95.toFixed(2)} ms
p99 : ${p99.toFixed(2)} ms

--- Stock ---
Ordered (from metrics) : ${success} units
Remaining stock        : see teardown output above (live Hazelcast read)

RESULT: ${pass ? 'PASS (no 5xx)' : 'FAIL (server errors detected)'}
NOTE: latency p95=${p95.toFixed(0)}ms — xem teardown output cho stock còn lại
`;

  const report = {
    timestamp: new Date().toISOString(),
    seedConfig: { count: SEED_COUNT, stockPerProduct: SEED_STOCK, totalStock: TOTAL_STOCK },
    results: {
      checkoutSuccess200: success,
      checkoutSoldOut400: soldOut,
      checkoutCartEmpty400: cartEmpty,
      checkoutBadRequestOther400: badOther,
      checkoutServerErrors5xx: serverErrors,
      cartAddFailures: cartFails,
    },
    latencyMs: data.metrics.checkout_latency_ms?.values || {},
    pass,
  };

  return {
    stdout: text,
    'flash-sale-1000ccu-summary.json': JSON.stringify(report, null, 2),
  };
}
