// 与后端 TraceFields 保持一致：字段名、中文名、是否在消费者页公开
export const STAGES = [
    { key: 'PRODUCTION', label: '生产', legacy: 'producer', file: 'production', fileLabel: '生产认证' },
    { key: 'DISTRIBUTION', label: '分销', legacy: 'distributor', file: 'distribution', fileLabel: '质检报告' },
    { key: 'RETAIL', label: '零售', legacy: 'retailer', file: null, fileLabel: null }
]

export const STAGE_FIELDS = {
    PRODUCTION: [
        { name: 'companyName', label: '生产企业', public: true },
        { name: 'productName', label: '产品名称', public: true },
        { name: 'productionLocation', label: '产地', public: true },
        { name: 'variety', label: '品种', public: true },
        { name: 'productionBatch', label: '生产批次', public: true },
        { name: 'productionCert', label: '生产认证（CID）', public: false, cid: true },
        { name: 'productTime', label: '生产日期', public: true }
    ],
    DISTRIBUTION: [
        { name: 'companyName', label: '分销企业', public: true },
        { name: 'storageCondition', label: '存储条件', public: true },
        { name: 'transportMethod', label: '运输方式', public: true },
        { name: 'distributeBatch', label: '分销批次', public: false },
        { name: 'storageLocation', label: '仓库地址', public: false },
        { name: 'distributePrice', label: '分销价格', public: false },
        { name: 'distributeQuantity', label: '分销数量', public: false },
        { name: 'inspectionReport', label: '质检报告（CID）', public: false, cid: true }
    ],
    RETAIL: [
        { name: 'companyName', label: '零售企业', public: true },
        { name: 'salePrice', label: '零售价格', public: false },
        { name: 'saleQuantity', label: '零售数量', public: false },
        { name: 'shelfLife', label: '保质期（天）', public: true },
        { name: 'invoiceNo', label: '单据号', public: false },
        { name: 'saleTime', label: '销售日期', public: true }
    ]
}

// 阶段状态：与后端 BatchServiceImpl 一致
export const STATUS_META = {
    NOT_STARTED: { text: '未开始', type: 'info' },
    PENDING: { text: '待确认', type: 'warning' },
    RELEASED: { text: '查证未写入，可重新提交', type: 'warning' },
    CONFIRMED: { text: '已上链', type: 'success' },
    FAILED: { text: '失败', type: 'danger' }
}

// 溯源号：大写字母开头，只含大写字母、数字、连字符，4-64 位（与后端 TraceValidator 一致）
export const TRACE_NUMBER_PATTERN = /^[A-Z][A-Z0-9-]{3,63}$/

// 生成形如 SY20260928-4821 的溯源号（前缀沿用 IoT 模拟批次的 SY）
export function generateTraceNumber() {
    const d = new Date()
    const pad = n => String(n).padStart(2, '0')
    const random = String(Math.floor(Math.random() * 10000)).padStart(4, '0')
    return `SY${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${random}`
}

// 时间显示：兼容毫秒时间戳与 ISO 字符串
export function formatTime(value) {
    if (value === null || value === undefined || value === '') {
        return ''
    }
    const date = new Date(typeof value === 'number' || /^\d+$/.test(String(value)) ? Number(value) : value)
    if (isNaN(date.getTime())) {
        return String(value)
    }
    const pad = n => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function shortHash(hash) {
    return hash ? hash.substring(0, 10) + '…' + hash.substring(hash.length - 8) : ''
}

// 不能选今天以后的日期（与后端「不晚于今天」一致）
export const pastDateOptions = {
    disabledDate(time) {
        return time.getTime() > Date.now()
    }
}

/**
 * 把上链类接口的返回解释成提示：
 * 200 已上链；202 结果未知需查证；409 重复/冲突（可能带未决交易记录）；400 字段错误；TIMEOUT 前端等待超时。
 * 返回 { type, text, fieldErrors, goDetail }
 */
export function explainTxResult(res) {
    const fieldErrors = {}
    if (res.code === 400 && res.data && Array.isArray(res.data.errors)) {
        res.data.errors.forEach(e => { fieldErrors[e.field] = e.message })
    }
    switch (res.code) {
        case 200:
            return {
                type: 'success',
                text: `已上链：交易哈希 ${shortHash(res.data && res.data.txHash)}，块高 ${res.data && res.data.blockNumber}`,
                fieldErrors, goDetail: true
            }
        case 202:
            return { type: 'warning', text: `${res.mes}。请在批次详情该阶段点「查证」`, fieldErrors, goDetail: true }
        case 409:
            // data 是交易记录说明是「未决交易」冲突，去详情查证；否则是业务冲突，留在原页
            return { type: 'warning', text: res.mes, fieldErrors, goDetail: !!(res.data && res.data.id) }
        case 'TIMEOUT':
            return { type: 'warning', text: res.mes, fieldErrors, goDetail: true }
        default:
            return { type: 'error', text: res.mes || '操作失败', fieldErrors, goDetail: false }
    }
}
