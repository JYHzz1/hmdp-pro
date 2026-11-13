import { execFileSync } from 'node:child_process'
import { randomInt } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import assert from 'node:assert/strict'

assert.ok(process.env.DB_PASSWORD, 'Set DB_PASSWORD for the local Pro database')
const count = Number(process.argv[2] || 3000)
const spread = Number(process.argv[3] || 1)
const capacity = Number(process.argv[4] || count)
assert.ok(Number.isInteger(count) && count > 0 && count <= 50000)
assert.ok(Number.isInteger(spread) && spread > 0 && spread <= 120)
assert.ok(Number.isInteger(capacity) && capacity > 0)
const base = 'http://127.0.0.1:8086'
async function api(path, body, token, method = 'POST') {
  const response = await fetch(base + path, { method, headers: {
    ...(body && { 'content-type': 'application/json' }), ...(token && { authorization: token })
  }, body: body && JSON.stringify(body) })
  const result = await response.json()
  assert.equal(result.success, true, result.errorMsg)
  return result.data
}
const phone = '139' + randomInt(10000000, 99999999)
const code = await api('/user/code?phone=' + phone)
const token = await api('/user/login', { phone, code })
const user = await api('/user/me', undefined, token, 'GET')
assert.match(String(user.id), /^\d+$/)
const batch = (BigInt(Date.now()) * 10n + BigInt(randomInt(10))).toString()
const orders = Array.from({ length: count }, (_, i) => String(BigInt(batch) * 100000n + BigInt(i)))
function sql(input) {
  return execFileSync(process.env.MYSQL_EXE || 'mysql',
    ['-h', '127.0.0.1', '-u', 'root', '-N', '-B', 'hmdppro_booking'],
    { input, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).trim()
}
sql(`
  INSERT IGNORE INTO booking_shop VALUES (900090,900090,'Load fixture',116.4,39.9);
  INSERT INTO booking_product VALUES (${batch},900090,'Load fixture',NOW()+INTERVAL 30 DAY);
  INSERT INTO booking_product_shop VALUES (${batch},900090);
  ${Array.from({ length: spread }, (_, i) => `
    INSERT INTO booking_slot(shop_id,voucher_id,start_at,end_at,capacity)
    VALUES (900090,${batch},NOW()+INTERVAL ${i+24} HOUR,NOW()+INTERVAL ${i+25} HOUR,${capacity});
  `).join('')}
`)
for (let i = 0; i < orders.length; i += 1000) {
  sql('INSERT INTO booking_entitlement(order_id,user_id,voucher_id) VALUES '
    + orders.slice(i, i + 1000).map(order => '(' + order + ',' + user.id + ',' + batch + ')').join(',') + ';')
}
const slots = sql(`SELECT id FROM booking_slot WHERE voucher_id=${batch} ORDER BY id;`).split(/\s+/)
writeFileSync('.local/booking-load.json', JSON.stringify({ token, batch, slots, orders }))
console.log(JSON.stringify({ batch, orders: orders.length, slots: slots.length, capacityPerSlot: capacity }))
