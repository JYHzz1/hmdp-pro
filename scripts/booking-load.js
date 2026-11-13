import http from 'k6/http'
import execution from 'k6/execution'
import { SharedArray } from 'k6/data'
import { Counter, Rate, Trend } from 'k6/metrics'

const fixture = JSON.parse(open(__ENV.BOOKING_FIXTURE || '../.local/booking-load.json'))
const orders = new SharedArray('orders', () => fixture.orders)
const slots = fixture.slots
const base = __ENV.BOOKING_API || 'http://127.0.0.1:8086'
if (base !== 'http://127.0.0.1:8086') throw new Error('Use the isolated local Pro backend')
const rate = Number(__ENV.RATE || 40)
const seconds = Number(__ENV.SECONDS || 15)
if (orders.length < rate * seconds) throw new Error('Prepare fresh orders for every iteration')
export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: { reserve: {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: seconds + 's',
    preAllocatedVUs: 32, maxVUs: 120
  } },
  thresholds: {
    booking_system_error: ['rate<0.01'],
    dropped_iterations: ['count==0']
  }
}

const success = new Counter('booking_success')
const rejected = new Counter('booking_rejected')
const systemError = new Rate('booking_system_error')
const successDuration = new Trend('booking_success_ms', true)

export default function () {
  const n = execution.scenario.iterationInTest
  const order = orders[n]
  const response = http.post(base + '/booking/' + order + '/reserve',
    JSON.stringify({ value: slots[n % slots.length], requestId: 'load-' + n }),
    { headers: { authorization: fixture.token, 'content-type': 'application/json' },
      tags: { name: 'reserve' }, timeout: '5s' })
  let body
  if (response.status === 200) {
    try { body = response.json() } catch { /* Count an invalid response as a system error. */ }
  }
  const ok = body?.success === true
  const full = body?.data?.code === 'SLOT_FULL'
  systemError.add(!ok && !full)
  if (ok) {
    success.add(1)
    successDuration.add(response.timings.duration)
  } else if (full) rejected.add(1)
}

export function handleSummary(data) {
  return { [__ENV.REPORT || '.local/booking-load-report.json']: JSON.stringify(data, null, 2) }
}
