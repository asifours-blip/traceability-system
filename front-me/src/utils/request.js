import axios from 'axios';
import { Message } from 'element-ui';
import router from '@/router';
import { getToken, clearLogin } from './auth';

// 1. 配置请求根路径
const instance = axios.create({
    baseURL: 'http://localhost:8010',
    timeout: 5000, // 请求超时时间
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
    (error) => {
        return error
    }
);

// 3. 响应拦截器
instance.interceptors.response.use(
    (success) => {
        // 响应成功拦截器
        return success.data;
    },
    (error) => {
        const response = error.response
        const serverMes = response && response.data && response.data.mes
        if (response && response.status === 401) {
            const message = serverMes || '未登录或登录已失效，请重新登录'
            // 登录接口自己的失败（密码错误）交给登录页提示
            if (error.config && error.config.url === '/login') {
                return { code: 401, mes: message }
            }
            clearLogin()
            Message.error(message)
            if (router.currentRoute.path !== '/login') {
                router.push('/login')
            }
            return { code: 401, mes: message }
        }
        if (response && response.status === 403) {
            const message = serverMes || '当前角色无权执行该操作'
            Message.error(message)
            return { code: 403, mes: message }
        }
        console.log(error);
        Message.error(error)
        return {  code: 500, mes: '服务器错误'}
    }
);
// 导出封装后的axios实例
export default instance;
