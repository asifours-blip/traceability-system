import request from '@/utils/request'



export const getSearchBlock = (num) => {
    return request({
        url: `/block/${num}`,
        method: 'get',
    });
};
export const getNodeList = (num) => {
    return request({
        url: `/block/getNodeList`,
        method: 'get',
    });
};
export const getTransactionTotal = (num) => {
    return request({
        url: `/block/getTxTotal`,
        method: 'get',
    });
};