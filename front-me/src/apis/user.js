import request, { TX_TIMEOUT } from '@/utils/request'

export const login = (data) => {
    return request({
        url: '/login',
        method: 'post',
        data
    })
}

// 撤销当前 token
export const logout = () => {
    return request({
        url: '/logout',
        method: 'post'
    })
}

// 以下为管理员接口，后端校验 ADMIN 角色
export const listUsers = () => {
    return request({
        url: '/admin/users',
        method: 'get'
    })
}

// 建号可能发授权交易：链上已有角色则跳过；结果未知时返回 202，账号保持停用
export const createUser = (data) => {
    return request({
        url: '/admin/users',
        method: 'post',
        data,
        timeout: TX_TIMEOUT,
        tx: true
    })
}

export const disableUser = (id) => {
    return request({
        url: `/admin/users/${id}/disable`,
        method: 'post',
        timeout: TX_TIMEOUT,
        tx: true
    })
}

// 授权交易结果未知的账号：查证链上角色，已有则启用
export const verifyUserRole = (id) => {
    return request({
        url: `/admin/users/${id}/verify-role`,
        method: 'post',
        timeout: TX_TIMEOUT,
        tx: true
    })
}

// 链上仍无角色时重新发送授权交易
export const retryGrantRole = (id) => {
    return request({
        url: `/admin/users/${id}/grant-role`,
        method: 'post',
        timeout: TX_TIMEOUT,
        tx: true
    })
}

export const getChainRole = (params) => {
    return request({
        url: '/get/user/role',
        method: 'get',
        params
    })
}
