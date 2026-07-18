import request from '@/utils/request'; // 假设 request 是你的请求工具函数

// 上传文件
export const uploadFile = (file) => {
    const formData = new FormData();
    formData.append('file', file);

    return request({
        url: '/upload', // 修改 URL 以匹配后端接口
        method: 'post',
        data: formData,
        headers: {
            'Content-Type': 'multipart/form-data' // 设置请求头
        }
    });
};

// 上传文件base64编码
export const uploadFileBase64 = (file) => {
    return request({
        url: '/uploadBase64', // 修改 URL 以匹配后端接口
        method: 'post',
        data: {
            file
        }
    });
};

// 加载文件获取base64编码
export const loadFileBase64 = (hash) => {
    return request({
        url: `/fileBase64/${hash}`,
        method: 'get',
    });
};

// 加载文件
export const loadFile = (hash) => {
    return request({
        url: `/file/${hash}`, // 根据后端接口路径设置 URL
        method: 'get',
        responseType: 'blob' // 设置响应类型为 blob
    });
};