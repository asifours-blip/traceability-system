import request from '@/utils/request'

// 获取溯源列表
export function getTraceList() {
    return request({
        url: '/trace/list',
        method: 'get',
    })
}

// 获取溯源详情
export function getTraceDetail(traceNumber) {
    return request({
        url: '/trace/detail/' + traceNumber,
        method: 'get',
    })
}
