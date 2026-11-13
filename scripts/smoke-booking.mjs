import assert from 'node:assert/strict'
import { randomInt, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'

const base = process.env.HMDP_PRO_API || 'http://127.0.0.1:5176/api'
assert.ok(process.env.DB_PASSWORD, 'Set DB_PASSWORD for the local MySQL fixture')
const mysql = process.env.MYSQL_EXE || 'mysql'
const db = 'hmdppro_booking'
function sql(statement) {
  return execFileSync(mysql, ['-u', 'root', '-N', '-B', db, '-e', statement],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).trim()
}
async function call(path, token, body, method = 'POST') {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token && { authorization: token }), ...(body && { 'content-type': 'application/json' }) },
    body: body && JSON.stringify(body),
    signal: AbortSignal.timeout(15000)
  })
  assert.equal(response.status, 200, path)
  return response.json()
}
async function api(...args) {
  const response = await call(...args)
  assert.equal(response.success, true, args[0] + ': ' + response.errorMsg)
  return response.data
}
async function login() {
  const phone = '139' + randomInt(10000000, 99999999)
  const code = await api('/user/code?phone=' + phone)
  const token = await api('/user/login', null, { phone, code })
  const user = await api('/user/me', token, null, 'GET')
  return { token, id: user.id, phone }
}
async function waitFor(check, description) {
  for (let i = 0; i < 40; i++) {
    const result = await check()
    if (result) return result
    await new Promise(resolve => setTimeout(resolve, 300))
  }
  assert.fail(description)
}
function pass(message) { console.log('PASS ' + message) }
const types = await api('/shop-type/list', null, null, 'GET')
assert.ok(types.length > 0)
const operator = await login()
const users = [await login(), await login()]
const shop = (await api('/shop/of/type?typeId=1&current=1', null, null, 'GET'))[0]
const format = date => new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit'
}).format(date)
const voucher = await api('/voucher/seckill', operator.token, {
  shopId: shop.id, title: '跨店洗护预约演示', subTitle: '三店通用', rules: '本地预约演示',
  payValue: 100, actualValue: 200, type: 1, status: 1, stock: 2,
  beginTime: format(new Date(Date.now() - 60000)), endTime: format(new Date(Date.now() + 3600000))
})
assert.match(String(voucher), /^\d+$/)
sql(`
  INSERT IGNORE INTO booking_shop VALUES
    (900001,900001,'洗护 A 店',116.4000,39.9000),
    (900002,900001,'洗护 B 店',116.4100,39.9000),
    (900003,900001,'洗护 C 店',116.4500,39.9000);
  INSERT INTO booking_product VALUES (${voucher},900001,'宠物洗护',NOW()+INTERVAL 7 DAY);
  INSERT INTO booking_product_shop VALUES (${voucher},900001),(${voucher},900002),(${voucher},900003);
  INSERT IGNORE INTO booking_staff VALUES
    (${operator.id},900001),(${operator.id},900002),(${operator.id},900003);
  SET @start=DATE_ADD(CURRENT_DATE(),INTERVAL 1 DAY)+INTERVAL 10 HOUR;
  INSERT INTO booking_slot(shop_id,voucher_id,start_at,end_at,capacity) VALUES
    (900001,${voucher},@start,@start+INTERVAL 1 HOUR,2),
    (900002,${voucher},@start,@start+INTERVAL 1 HOUR,1),
    (900003,${voucher},@start,@start+INTERVAL 1 HOUR,1);
`)
const [a, b, c] = sql(`SELECT id FROM booking_slot WHERE voucher_id=${voucher} ORDER BY shop_id`).split(/\s+/)
for (const [index, user] of users.entries()) {
  if (index > 0) await new Promise(resolve => setTimeout(resolve, 5200))
  const access = await api('/voucher-order/seckill/token/' + voucher, user.token, null, 'GET')
  user.order = await api('/voucher-order/seckill/' + voucher + '?accessToken=' + encodeURIComponent(access), user.token)
  const view = await waitFor(async () => {
    const result = await call('/booking/entitlements/' + voucher, user.token)
    return result.success ? result.data : null
  }, 'Order did not become a booking entitlement')
  assert.equal(String(view.orderId), String(user.order))
}
users.sort((left, right) => BigInt(left.order) < BigInt(right.order) ? -1 : 1)
const [flexible, constrained] = users
pass('真实 Kafka 秒杀订单可登记预约权益')
if (process.argv.includes('--demo')) {
  console.log(JSON.stringify({ voucherId: String(voucher), shopId: String(shop.id),
    phones: { flexible: flexible.phone, constrained: constrained.phone, staff: operator.phone },
    slots: { a, b, c } }, null, 2))
  process.exit(0)
}
assert.equal((await call('/booking/' + flexible.order, constrained.token, null, 'GET')).success, false)
assert.equal((await call('/booking/slots/' + a + '/close', flexible.token)).success, false)
assert.equal((await call('/voucher-order/cancel', flexible.token, { voucherId: voucher })).success, false)
pass('用户归属、门店操作权限与预约券交易取消入口均受约束')

const cmd = (user, action, value, requestId = randomUUID()) =>
  api('/booking/' + user.order + '/' + action, user.token, { value, requestId })
const raw = (user, action, value) =>
  call('/booking/' + user.order + '/' + action, user.token, { value, requestId: randomUUID() })
const view = user => api('/booking/' + user.order, user.token, null, 'GET')
const counts = id => sql(`SELECT CONCAT(held,':',booked) FROM booking_slot WHERE id=${id}`)
const race = await Promise.all(users.map(user => raw(user, 'reserve', b)))
assert.equal(race.filter(response => response.success).length, 1)
assert.equal(counts(b), '0:1')
const winner = users[race.findIndex(response => response.success)]
const cancelId = randomUUID()
const cancelled = await cmd(winner, 'cancel', (await view(winner)).reservation.version, cancelId)
const previous = Number(cancelled) - 1
assert.equal(String(await cmd(winner, 'cancel', previous, cancelId)), String(cancelled))
assert.equal(counts(b), '0:0')
await Promise.all(users.map(user => cmd(user, 'reserve', a)))
assert.equal(counts(a), '0:2')
pass('两人争最后一个工位仅一人成功，取消重放不会重复释放，可持原券重新预约')

const direct = (user, value, version) => api('/booking/' + user.order + '/reschedule', user.token,
  { value, version, requestId: randomUUID() })
const switched = await direct(flexible, b, (await view(flexible)).reservation.version)
assert.equal(counts(a), '0:1')
assert.equal(counts(b), '0:1')
await direct(flexible, a, switched)
assert.equal(counts(a), '0:2')
assert.equal(counts(b), '0:0')
pass('用户主动改期一次提交完成，无临时占位')

await api('/booking/' + flexible.order + '/preference', flexible.token, { radiusKm: 6, windowHours: 4 }, 'PUT')
await api('/booking/' + constrained.order + '/preference', constrained.token, { radiusKm: 2, windowHours: 4 }, 'PUT')
await api('/booking/slots/' + a + '/close', operator.token)
const options = { radiusKm: 10, windowHours: 4 }
const comparison = await api('/booking/slots/' + a + '/compare', operator.token, options)
assert.equal(Number(comparison.nearest.total), 2)
assert.equal(comparison.nearest.matches.length, 1)
assert.equal(comparison.constrained.matches.length, 2)
const issued = await api('/booking/slots/' + a + '/assign', operator.token, options)
assert.equal(issued.offers.length, 2)
const stale = (await view(flexible)).offer.id
sql(`UPDATE booking_offer SET expires_at=NOW()-INTERVAL 1 SECOND WHERE id=${stale}`)
await waitFor(() => counts(c) === '0:0', 'Scheduled expiry did not release held capacity')
assert.equal((await raw(flexible, 'confirm', stale)).success, false)
const reissued = await api('/booking/slots/' + a + '/assign', operator.token, options)
assert.equal(reissued.offers.length, 1)
assert.equal((await raw(flexible, 'confirm', stale)).success, false)
pass('门店发出的备选邀请过期后回收名额，旧确认不能接受新邀请')
assert.equal(counts(a), '0:2')
assert.equal(counts(b), '1:0')
assert.equal(counts(c), '1:0')
const bookings = await Promise.all(users.map(view))
assert.equal(String(bookings[0].offer.target), String(c))
assert.equal(String(bookings[1].offer.target), String(b))
await Promise.all(users.map((user, i) => cmd(user, 'confirm', bookings[i].offer.id)))
assert.equal(counts(a), '0:0')
assert.equal(counts(b), '0:1')
assert.equal(counts(c), '0:1')
pass('停业改派同一快照最近优先 1/2、受限用户优先 2/2，两位用户确认后完成换位')

const old = await call('/booking/shops/900001/redeem', operator.token,
  { orderId: flexible.order, version: bookings[0].reservation.version, requestId: randomUUID() })
assert.equal(old.success, false)
const current = await view(flexible)
sql(`UPDATE booking_slot SET start_at=NOW()-INTERVAL 1 MINUTE,end_at=NOW()+INTERVAL 59 MINUTE WHERE id=${c}`)
const redemption = { orderId: flexible.order, version: current.reservation.version, requestId: randomUUID() }
const redeemRace = await Promise.all([
  call('/booking/shops/900003/redeem', operator.token, redemption),
  call('/booking/shops/900003/redeem', operator.token, { ...redemption, requestId: randomUUID() })
])
assert.equal(redeemRace.filter(x => x.success).length, 1)
const completed = await view(flexible)
assert.equal(completed.entitlementState, 'USED')
assert.equal(completed.reservation.state, 'COMPLETED')
assert.equal(sql(`SELECT COUNT(*) FROM booking_event WHERE order_id=${flexible.order} AND operation='COMPLETED'`), '1')
assert.equal(counts(c), '0:1')
pass('旧门店核销失败，并发核销只有一次成功')
await api('/booking/slots/' + b + '/close', operator.token)
assert.equal(Number(await api('/booking/slots/' + b + '/cancel-affected', operator.token)), 1)
assert.equal(Number(await api('/booking/slots/' + b + '/cancel-affected', operator.token)), 0)
const cancelledByShop = await view(constrained)
assert.equal(cancelledByShop.cancelledByShop, true)
assert.equal(cancelledByShop.entitlementState, 'AVAILABLE')
assert.equal(counts(b), '0:0')
pass('店员停约取消后释放名额并恢复券，用户可查看原因')
assert.equal(sql(`
  SELECT COUNT(*) FROM booking_slot s
  WHERE s.voucher_id=${voucher} AND (
    s.held <> (SELECT COUNT(*) FROM booking_offer o WHERE o.target_slot_id=s.id AND o.state='PENDING')
    OR s.booked <> (SELECT COUNT(*) FROM booking_reservation r WHERE r.slot_id=s.id
        AND r.state IN ('CONFIRMED','COMPLETED','NO_SHOW'))
  )
`), '0')
pass('逐时段容量与预约、占位记录一致')
console.log(JSON.stringify({ voucherId: String(voucher), slots: { a, b, c },
  nearest: comparison.nearest, constrained: comparison.constrained }, null, 2))
