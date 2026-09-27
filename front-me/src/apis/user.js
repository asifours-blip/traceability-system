import request from '@/utils/request'

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

export const createUser = (data) => {
    return request({
        url: '/admin/users',
        method: 'post',
        data
    })
}

export const disableUser = (id) => {
    return request({
        url: `/admin/users/${id}/disable`,
        method: 'post'
    })
}

export const getChainRole = (params) => {
    return request({
        url: '/get/user/role',
        method: 'get',
        params
    })
}
