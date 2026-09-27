import { localStorageService } from './commonUtil';

const TOKEN_KEY = 'token';
const USER_KEY = 'userInfo';

// 后端账号角色 → 前端原有的 type 编码（路由守卫与菜单沿用）
export const ROLE_TYPE_MAP = {
    PRODUCER: '0',
    DISTRIBUTOR: '1',
    RETAILER: '2',
    ADMIN: '4'
};

export function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

// userInfo 只用于页面展示与前端路由，身份以服务端 token 为准
export function saveLogin({ token, user }) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorageService.setItem(USER_KEY, {
        username: user.username,
        role: user.role,
        type: ROLE_TYPE_MAP[user.role],
        address: user.chainAddress,
        companyName: user.companyName
    });
}

export function clearLogin() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
}
