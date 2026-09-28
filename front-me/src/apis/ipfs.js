import request from '@/utils/request';

// 上传文件（multipart，后端流式处理）：只接受 PNG / JPEG / WEBP / PDF，按文件内容判定类型，单个不超过 10 MB。
// 成功返回 { cid, sha256, size, mimeType, fileName, status: 'UPLOADED' }；交易确认后才变为已绑定。
// 原 /uploadBase64、按 CID 读取的 /file、/fileBase64 已删除：文件只能按溯源号与阶段经 /trace/{溯源号}/file/{阶段} 读取。
export const uploadFile = (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return request({
        url: '/upload',
        method: 'post',
        data: formData,
        // 10 MB 在慢网下可能超过默认 5 秒
        timeout: 60000,
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    });
};

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const ACCEPT_TYPES = 'image/png,image/jpeg,image/webp,application/pdf';

// 上传错误码 → 提示
export const UPLOAD_ERRORS = {
    FILE_EMPTY: '文件为空',
    FILE_TOO_LARGE: '文件超过 10 MB',
    FILE_TYPE_NOT_ALLOWED: '只接受 PNG、JPEG、WEBP 图片或 PDF（按文件内容判定，改扩展名无效）',
    FILE_EXTENSION_MISMATCH: '扩展名与文件内容不符',
    IPFS_UNAVAILABLE: '文件存储服务暂时不可用，请稍后重试',
    IPFS_VERIFY_FAILED: '存储节点返回的内容与上传不一致，已放弃，请重试',
    FILE_CHANGED: '上传过程中文件发生变化，请重新选择'
};
