import axios from 'axios';
import router from '@/router';
import { getToken, clearLogin } from './auth';

export const BASE_URL = 'http://localhost:8010';

// 普通请求 5 秒。
// 交易类请求（上链、查证）单独放宽：WeBASE-Front 等回执最长约 30 秒（transMaxWait，上一阶段实测），
// 后端读超时 40 秒（webase-front.read-timeout-ms），前后还有几次只读调用，所以取 60 秒。不要全局放大。
export const TX_TIMEOUT = 60000;

// 1. 配置请求根路径
const instance = axios.create({
    baseURL: BASE_URL,
    timeout: 5000,
});

// 2. 请求拦截器：只带服务端签发的 token，不再自报 address
instance.interceptors.request.use(
    (config) => {
        const token = getToken();
        if (token) {
            config.headers['Authorization'] = 'Bearer ' + token
        }
        return config;
    },
    (error) => Promise.reject(error)
);

// 免登录页面（消费者扫码）：遇到 401 不跳登录页
function isPublicPage(path) {
    return path === '/login' || path === '/trace' || path.startsWith('/traceDetail/') || path.startsWith('/qrcodeTrace/')
}

// 登录过期：清登录态并跳登录页，带上当前地址，登录后回到原页面
function toLogin() {
    const current = router.currentRoute;
    if (isPublicPage(current.path)) {
        return;
    }
    router.push({ path: '/login', query: { redirect: current.fullPath } }).catch(() => {});
}

// 3. 响应拦截器：一律返回 { code, mes, data }，由页面按 code 处理
//    200 成功；202 交易结果未知（data 为交易记录，需查证）；400 字段校验失败（data.errors）；
//    403 无权；404 不存在；409 重复或冲突（data 可能是未决的交易记录）；TIMEOUT 前端等待超时
instance.interceptors.response.use(
    (success) => success.data,
    (error) => {
        const response = error.response
        const config = error.config || {}
        if (!response) {
            if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
                return {
                    code: 'TIMEOUT',
                    mes: config.tx
                        ? '等待超时：交易结果未知。请到批次详情查看该阶段状态，必要时点「查证」，不要直接重复提交'
                        : '请求超时，请稍后重试'
                }
            }
            return { code: 0, mes: '无法连接后端服务' }
        }
        const body = response.data && typeof response.data === 'object' && !(response.data instanceof Blob)
            ? response.data : null
        const mes = (body && body.mes) || `请求失败（HTTP ${response.status}）`
        if (response.status === 401) {
            // 登录接口自己的失败（密码错误）交给登录页提示
            if (config.url !== '/login') {
                clearLogin()
                toLogin()
            }
            return { code: 401, mes: body && body.mes ? body.mes : '未登录或登录已失效，请重新登录' }
        }
        return { code: response.status, mes, data: body ? body.data : undefined }
    }
);
// 导出封装后的axios实例
export default instance;
