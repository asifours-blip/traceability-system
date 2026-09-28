import request, { TX_TIMEOUT } from '@/utils/request'

export const getSystemInfo = () => {
    return request({
        url: `/getSystemInfo`,
        method: 'get',
    });
};


export const clearSystemInfo = () => {
    return request({
        url: `/clearSystemInfo`,
        method: 'post',
        // 系统信息写在链上，是交易
        timeout: TX_TIMEOUT,
        tx: true
    });
};

export const setSystemInfo = (data) => {
    return request({
        url: `/setSystemInfo`,
        method: 'post',
        data,
        timeout: TX_TIMEOUT,
        tx: true
    });
};
