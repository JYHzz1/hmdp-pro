<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'

const route = useRoute()
const router = useRouter()
const voucher = ref(String(route.query.voucherId ?? ''))
const booking = ref(null)
const busy = ref(false)
const error = ref('')
const radiusKm = ref(5)
const windowHours = ref(24)
const selected = ref('')
const station = ref({ shop: '', slot: '', order: '', version: '' })
const comparison = ref(null)
const inviteResult = ref(null)
const staffNotice = ref('')
const confirmed = computed(() => booking.value?.reservation?.state === 'CONFIRMED')
const uncertainRequests = new Map()

async function api(path, method = 'get', data) {
  const result = await request({ url: '/booking' + path, method, data })
  if (!result) throw new Error('未收到操作结果，请重试')
  if (!result.success) {
    const rejection = new Error(result.errorMsg || '请求未完成')
    rejection.businessRejection = Boolean(result.data?.code)
    rejection.code = result.data?.code
    throw rejection
  }
  return result.data
}

async function sendCommand(path, args) {
  const key = JSON.stringify([path, args])
  if (!uncertainRequests.has(key)) uncertainRequests.set(key, crypto.randomUUID())
  try {
    const result = await api(path, 'post', { ...args, requestId: uncertainRequests.get(key) })
    uncertainRequests.delete(key)
    return result
  } catch (error) {
    if (error.businessRejection) uncertainRequests.delete(key)
    throw error
  }
}

async function run(action) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}

async function load() {
  const value = voucher.value.trim()
  if (loadedVoucher !== value) booking.value = null
  booking.value = await api('/entitlements/' + encodeURIComponent(value), 'post')
  loadedVoucher = value
  selected.value = ''
}

let loadedVoucher = ''

function change(operation, value) {
  return run(async () => {
    await sendCommand('/' + booking.value.orderId + '/' + operation, { value })
    await load()
  })
}

function savePreference() {
  return run(async () => {
    await api('/' + booking.value.orderId + '/preference', 'put',
      { radiusKm: radiusKm.value, windowHours: windowHours.value })
    await load()
  })
}

function operate(action) {
  return run(async () => {
    staffNotice.value = ''
    const prefix = '/slots/' + encodeURIComponent(station.value.slot)
    if (action === 'redeem') {
      await sendCommand('/shops/' + encodeURIComponent(station.value.shop) + '/redeem',
        { orderId: station.value.order, version: station.value.version })
      staffNotice.value = '核销成功'
    } else if (action === 'close') {
      await api(prefix + '/close', 'post')
      staffNotice.value = '时段已停约'
    } else {
      const result = await api(prefix + '/' + action, 'post',
        { radiusKm: radiusKm.value, windowHours: windowHours.value })
      if (action === 'compare') comparison.value = result
      else inviteResult.value = result
    }
  })
}

function time(value) {
  return String(value ?? '').replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  if (voucher.value) run(load)
})
</script>

<template>
  <main class="booking-page">
    <header>
      <el-button text @click="router.back()">返回</el-button>
      <h1>门店预约</h1>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon @close="error = ''" />
    <section>
      <h2>我的服务券</h2>
      <form class="row" @submit.prevent="run(load)">
        <el-input v-model="voucher" placeholder="已领取的服务券 ID" aria-label="服务券 ID" />
        <el-button native-type="submit" type="primary" :disabled="!voucher.trim()" :loading="busy">查询</el-button>
      </form>
      <template v-if="booking">
        <p>订单 {{ booking.orderId }} · {{ { AVAILABLE: '可预约', RESERVED: '已预约', USED: '已使用' }[booking.entitlementState] }}</p>
        <div v-if="confirmed" class="summary">
          <strong>{{ booking.currentSlot.shop }}</strong>
          <small>时段编号 {{ booking.currentSlot.id }}</small>
          <span>{{ time(booking.currentSlot.start) }} – {{ time(booking.currentSlot.end) }}</span>
          <span v-if="!booking.currentSlot.open" class="warning">该时段已停业，请查看改派方案或取消预约</span>
          <small>核销信息：门店 {{ booking.currentSlot.shopId }} · 预约版本 {{ booking.reservation.version }}</small>
          <el-button :disabled="busy" @click="change('cancel', booking.reservation.version)">取消预约</el-button>
        </div>
        <div v-if="booking.offer" class="summary">
          <strong>改期至 {{ booking.offeredSlot.shop }}</strong>
          <span>{{ time(booking.offeredSlot.start) }} · {{ time(booking.offer.expires) }} 前确认</span>
          <div class="row">
            <el-button type="primary" :disabled="busy" @click="change('confirm', booking.offer.id)">确认改期</el-button>
            <el-button :disabled="busy" @click="change('decline', booking.offer.id)">放弃方案</el-button>
          </div>
        </div>
        <template v-if="booking.entitlementState === 'AVAILABLE' || (confirmed && !booking.offer)">
          <h3>{{ confirmed ? '选择新时段' : '选择预约时段' }}</h3>
          <p v-if="!booking.candidates.length">当前没有可用时段</p>
          <select v-else v-model="selected" aria-label="可用时段">
            <option disabled value="">请选择</option>
            <option v-for="slot in booking.candidates.filter(s => s.id !== booking.currentSlot?.id)" :key="slot.id" :value="slot.id">
              {{ slot.shop }} · {{ time(slot.start) }} · 余 {{ slot.capacity - slot.booked - slot.held }}
            </option>
          </select>
          <el-button v-if="selected" type="primary" :disabled="busy" @click="change(confirmed ? 'offers' : 'reserve', selected)">
            {{ confirmed ? '保留原预约并占位' : '确认预约' }}
          </el-button>
        </template>
      </template>
    </section>

    <section>
      <h2>改派范围</h2>
      <div class="row">
        <label>最多距离（公里） <el-input-number v-model="radiusKm" :min="1" :max="50" /></label>
        <label>前后时间（小时） <el-input-number v-model="windowHours" :min="1" :max="168" /></label>
        <el-button :disabled="busy || !booking" @click="savePreference">保存我的偏好</el-button>
      </div>
    </section>

    <section>
      <h2>门店操作</h2>
      <div class="row">
        <el-input v-model="station.slot" placeholder="受影响时段 ID" aria-label="时段 ID" />
        <el-button :disabled="busy || !station.slot" @click="operate('close')">停约</el-button>
        <el-button :disabled="busy || !station.slot" @click="operate('compare')">比较改派策略</el-button>
        <el-button :disabled="busy || !station.slot" type="primary" @click="operate('assign')">发送备选方案</el-button>
      </div>
      <div v-if="comparison" class="summary">
        <span>最近优先：{{ comparison.nearest.matches.length }}/{{ comparison.nearest.total }} 可安排 · 总距离 {{ Number(comparison.nearest.totalDistanceKm).toFixed(2) }} 公里</span>
        <span>可选项少优先：{{ comparison.constrained.matches.length }}/{{ comparison.constrained.total }} 可安排 · 总距离 {{ Number(comparison.constrained.totalDistanceKm).toFixed(2) }} 公里</span>
        <span>最大流：{{ comparison.maximum.matches.length }}/{{ comparison.maximum.total }} 可安排 · 总距离 {{ Number(comparison.maximum.totalDistanceKm).toFixed(2) }} 公里</span>
      </div>
      <p v-if="inviteResult">计划 {{ inviteResult.planned }} 份，发出 {{ inviteResult.offers.length }} 份，冲突 {{ Object.keys(inviteResult.conflicts).length }} 份</p>
      <form class="row" @submit.prevent="operate('redeem')">
        <el-input v-model="station.shop" placeholder="门店 ID" aria-label="门店 ID" />
        <el-input v-model="station.order" placeholder="订单 ID" aria-label="订单 ID" />
        <el-input v-model="station.version" placeholder="预约版本" aria-label="预约版本" />
        <el-button native-type="submit" type="primary" :disabled="busy || !station.shop || !station.order || !station.version">核销</el-button>
      </form>
      <p v-if="staffNotice" role="status">{{ staffNotice }}</p>
    </section>
  </main>
</template>

<style scoped>
.booking-page { max-width: 48rem; margin: 0 auto; padding: 1.25rem; color: #1c2936; }
header, .row { display: flex; align-items: center; gap: .75rem; flex-wrap: wrap; }
section { margin: 1rem 0; padding: 1rem; border: 1px solid #d9e2ea; border-radius: .75rem; background: #fff; }
h1, h2, h3 { margin: 0 0 .75rem; }
header h1 { margin: 0; }
.summary { display: grid; gap: .5rem; padding: .75rem; margin: .75rem 0; background: #eef6fb; border-radius: .5rem; justify-items: start; }
.warning { color: #984019; }
.row .el-input { max-width: 14rem; }
select { max-width: 100%; padding: .45rem; margin-right: .75rem; }
form { margin-top: .75rem; }
</style>
