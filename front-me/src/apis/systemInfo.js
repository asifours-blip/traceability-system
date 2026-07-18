import request from '@/utils/request'

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
    });
};

export const setSystemInfo = (data) => {
    return request({
        url: `/setSystemInfo`,
        method: 'post',
        data
    });
};
