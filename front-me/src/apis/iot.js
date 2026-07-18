import request from '@/utils/request'

export function getIotData(batchId, limit = 10) {
    return request({
        url: '/api/iot/data',
        method: 'get',
        params: { batchId, limit }
    })
}