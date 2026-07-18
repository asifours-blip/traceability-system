<template>
<div class="user-center">
    <div class="user-container">
        <div class="user-card">
            <div class="user-header">
                <div class="avatar">
                    <i class="el-icon-user-solid"></i>
                </div>
                <h2 class="title">个人信息</h2>
            </div>

            <div class="info-section">
                <div class="info-item">
                    <span class="label">用户类型</span>
                    <span class="value">{{ userInfo.typeName }}</span>
                </div>
                <div class="info-item">
                    <span class="label">用户地址</span>
                    <span class="value address">{{ userInfo.address }}</span>
                </div>
            </div>
        </div>
    </div>
</div>
</template>

<script>
import { localStorageService } from '@/utils/commonUtil';

export default {
    name: 'user-center-view',

    data() {
        return {
            userInfo: {
                address: '',
                typeName: ''
            }
        };
    },

    mounted() {
        let { type, address } = localStorageService.getItem('userInfo');
        if (type === '0') {
            this.userInfo.typeName = '生产商';
        } else if (type === '1') {
            this.userInfo.typeName = '分销商';
        } else if (type === '2') {
            this.userInfo.typeName = '零售商';
        } else {
            this.userInfo.typeName = '消费者';
        }
        this.userInfo.address = address;
    },

    methods: {

    },
};
</script>

<style scoped>
.user-center {
    min-height: calc(100vh - 220px);
    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
}

.user-container {
    width: 100%;
    max-width: 400px;
}

.user-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.3);
}

.user-header {
    text-align: center;
    margin-bottom: 40px;
}

.avatar {
    width: 80px;
    height: 80px;
    background: #0071e3;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0 auto 20px;
}

.avatar i {
    font-size: 40px;
    color: white;
}

.title {
    font-size: 24px;
    font-weight: 600;
    color: #1d1d1f;
    margin: 0;
    letter-spacing: -0.5px;
}

.info-section {
    background: rgba(255, 255, 255, 0.5);
    border-radius: 16px;
    padding: 24px;
}

.info-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 0;
    border-bottom: 1px solid rgba(0, 0, 0, 0.1);
    flex-wrap: wrap;
}

.info-item:last-child {
    border-bottom: none;
}

.label {
    font-size: 16px;
    color: #86868b;
    font-weight: 500;
    flex-shrink: 0;
    margin-right: 15px;
}

.value {
    font-size: 16px;
    color: #1d1d1f;
    font-weight: 500;
    flex-grow: 1;
}

.address {
    font-family: 'SF Mono', SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
    font-size: 14px;
    color: #0071e3;
    background: rgba(0, 113, 227, 0.1);
    padding: 4px 8px;
    border-radius: 6px;
    word-break: break-all;
}

@media (max-width: 480px) {
    .user-card {
        padding: 30px 20px;
    }

    .title {
        font-size: 20px;
    }

    .info-section {
        padding: 16px;
    }

    .label,
    .value {
        font-size: 14px;
    }
}
</style>