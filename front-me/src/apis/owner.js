import request from '@/utils/request'

export const getContractOwner = () => {
    return request({
        url: `/getContractOwner`,
        method: 'get',
    });
};