import request, { BASE_URL, TX_TIMEOUT } from '@/utils/request'

const enc = encodeURIComponent

// ---------- 消费者（免登录） ----------

// 公开溯源信息：只含公开字段
export function getTraceDetail(traceNumber) {
    return request({ url: '/trace/detail/' + enc(traceNumber), method: 'get' })
}

// 已绑定到溯源号某阶段的公开文件（生产认证 production / 质检报告 distribution），可直接用作 <img src>
export function publicFileUrl(traceNumber, stage) {
    return `${BASE_URL}/trace/${enc(traceNumber)}/file/${stage}`
}

// ---------- 批次（需登录，后端按账号与批次关系鉴权） ----------

export function listBatches() {
    return request({ url: '/batches', method: 'get' })
}

export function getBatch(traceNumber) {
    return request({ url: '/batches/' + enc(traceNumber), method: 'get', timeout: 15000 })
}

// 可指定的交接对象：生产商查 DISTRIBUTOR，分销商查 RETAILER
export function listPartners(role) {
    return request({ url: '/partners', method: 'get', params: { role } })
}

export function assignDistributor(traceNumber, data) {
    return request({ url: `/batches/${enc(traceNumber)}/distributor`, method: 'put', data, timeout: 15000 })
}

export function assignRetailer(traceNumber, data) {
    return request({ url: `/batches/${enc(traceNumber)}/retailer`, method: 'put', data, timeout: 15000 })
}

export function addCorrection(traceNumber, data) {
    return request({ url: `/batches/${enc(traceNumber)}/corrections`, method: 'post', data, timeout: 15000 })
}

// ---------- 上链与查证：单独放宽超时，覆盖后端最坏等待 ----------

export function submitProduction(data) {
    return request({ url: '/producer/add', method: 'post', data, timeout: TX_TIMEOUT, tx: true })
}

export function submitDistribution(data) {
    return request({ url: '/distributor/add', method: 'post', data, timeout: TX_TIMEOUT, tx: true })
}

export function submitRetail(data) {
    return request({ url: '/retailer/add', method: 'post', data, timeout: TX_TIMEOUT, tx: true })
}

export function verifyTx(id) {
    return request({ url: `/chain-tx/${id}/verify`, method: 'post', timeout: TX_TIMEOUT, tx: true })
}
