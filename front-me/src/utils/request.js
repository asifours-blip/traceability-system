import axios from 'axios';
import { Message } from 'element-ui';
import { localStorageService } from './commonUtil';

// 1. 配置请求根路径
const instance = axios.create({
    baseURL: 'http://localhost:8010',
    timeout: 5000, // 请求超时时间
});

// 2. 请求拦截器
instance.interceptors.request.use(
    (config) => {
        const userInfo = localStorageService.getItem('userInfo');
        if (userInfo && userInfo.address) {
            config.headers['address'] = userInfo.address
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

        console.log(error);
        Message.error(error)
        return {  code: 500, mes: '服务器错误'}
    }
);
// 导出封装后的axios实例
export default instance;

