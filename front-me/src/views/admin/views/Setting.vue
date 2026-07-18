<template>
<div class="page-container">
    <!-- 主体内容 -->
    <div class="main-content-grid">
        <!-- 左边部分 -->
        <div class="column">
            <!-- 个人信息 Card -->
            <el-card class="info-card" shadow="hover">
                <div class="card-header">
                    <h2 class="card-subtitle">个人信息</h2>
                </div>
                <div class="info-display">
                    <p class="info-item"><strong>地址:</strong> <span class="info-value">{{ userInfo.address }}</span></p>
                    <p class="info-item"><strong>类型:</strong> <span class="info-value">{{ userInfo.type == "4" ? "管理员" :
                        "其他角色" }}</span></p>
                </div>
            </el-card>

            <!-- 系统信息 Card -->
            <el-card class="info-card" shadow="hover">
                <div class="card-header">
                    <h2 class="card-subtitle">系统信息</h2>
                </div>
                <div class="info-display">
                    <p class="info-item"><strong>系统名称:</strong> <span class="info-value">{{ systemInfo.name }}</span>
                    </p>
                    <p class="info-item"><strong>版本号:</strong> <span class="info-value">{{ systemInfo.version }}</span>
                    </p>
                    <p class="info-item"><strong>系统描述:</strong> <span class="info-value">{{ systemInfo.description
                    }}</span></p>
                </div>
            </el-card>
        </div>

        <!-- 右边部分 - 系统信息配置表单 -->
        <div class="column">
            <el-card class="form-card" shadow="hover">
                <div class="card-header">
                    <h2 class="card-subtitle">系统配置</h2>
                    <p class="card-description">管理和更新系统基本信息</p>
                </div>
                <el-form :model="form" :rules="rules" ref="systemInfoForm" label-width="100px" class="apple-form">
                    <el-form-item label="系统名称" prop="name">
                        <el-input v-model="form.name" placeholder="请输入系统名称"></el-input>
                    </el-form-item>
                    <el-form-item label="版本号" prop="version">
                        <el-input v-model="form.version" placeholder="请输入版本号"></el-input>
                    </el-form-item>
                    <el-form-item label="描述" prop="description">
                        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入系统描述"
                            class="custom-textarea"></el-input>
                    </el-form-item>
                    <el-form-item class="button-group">
                        <el-button type="primary" @click="submitForm('systemInfoForm')"
                            class="submit-btn">提交</el-button>
                        <el-button type="danger" @click="submitClear" class="clear-btn">清空系统信息</el-button>
                    </el-form-item>
                </el-form>
            </el-card>
        </div>
    </div>
</div>
</template>

<script>
import { getSystemInfo, setSystemInfo, clearSystemInfo } from '@/apis/systemInfo';

export default {
    name: "admin-view",
    data() {
        return {
            userInfo: {
                address: '北京市',
                type: '管理员'
            },
            systemInfo: {
                name: '农产品溯源系统',
                version: '1.0.0',
                description: ''
            },
            form: {
                name: '',
                version: '',
                description: ''
            },
            rules: {
                name: [
                    { required: true, message: '请输入系统名称', trigger: 'blur' }
                ],
                version: [
                    { required: true, message: '请输入版本号', trigger: 'blur' }
                ],
                description: [
                    { required: true, message: '请输入描述', trigger: 'blur' }
                ]
            }
        };
    },
    created() {
        this.userInfo = JSON.parse(localStorage.getItem('userInfo'));
        this.fetchSystemInfo();
    },
    methods: {
        fetchSystemInfo() {
            getSystemInfo().then(response => {
                this.systemInfo = { ...response.data };
                this.form = { ...response.data };
            });
        },
        submitForm(formName) {
            this.$refs[formName].validate((valid) => {
                if (valid) {
                    setSystemInfo(this.form).then(response => {
                        if (response.code == 200) {
                            this.$message.success('提交成功');
                            this.fetchSystemInfo();
                        } else {
                            this.$message.error(response.mes);
                        }
                    })
                }
            });
        },
        logout() {
            // 实现退出操作
            localStorage.removeItem('userInfo');
            this.$router.push('/login');
        },
        async submitClear() {
            try {
                await this.$confirm('此操作清空系统信息回归默认值，是否继续？', '提示', {
                    type: 'warning'
                })
            } catch (err) {
                return this.$message.warning('已取消')
            }
            clearSystemInfo().then(response => {
                this.fetchSystemInfo();
            })
        }
    }
};
</script>

<style scoped>
.page-container {
    min-height: calc(100vh - 80px);
    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
    padding: 30px;
    display: flex;
    flex-direction: column;
    gap: 30px;
}
.main-content-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 30px;
    flex: 1;
}

.column {
    display: flex;
    flex-direction: column;
    gap: 30px;
}

.info-card,
.form-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.3);
    /* height: 100%; */
    /* Ensure cards in a column take full height */
}

.card-header {
    text-align: center;
    margin-bottom: 30px;
}

.card-subtitle {
    font-size: 22px;
    font-weight: 600;
    color: #1d1d1f;
    margin: 0;
    letter-spacing: -0.5px;
}

.card-description {
    font-size: 15px;
    color: #86868b;
    margin-top: 8px;
    font-weight: 400;
}

.info-display {
    margin-top: 20px;
}

.info-item {
    font-size: 16px;
    color: #333;
    margin-bottom: 15px;
    display: flex;
    word-break: break-all;
}

.info-item strong {
    font-weight: 500;
    margin-right: 10px;
    color: #555;
    flex-shrink: 0;
}

.info-value {
    flex-grow: 1;
}

.apple-form {
    padding: 0 10px;
}

:deep(.el-form-item__label) {
    font-size: 15px;
    color: #333;
    font-weight: 500;
}

:deep(.el-input__inner) {
    height: 44px;
    border-radius: 12px;
    border: 1px solid #d2d2d7;
    background: rgba(255, 255, 255, 0.8);
    font-size: 15px;
    padding: 0 16px;
    transition: all 0.3s ease;
}

:deep(.el-input__inner:focus) {
    border-color: #0071e3;
    box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

.custom-textarea :deep(.el-textarea__inner) {
    height: 100px !important;
    border-radius: 12px;
    border: 1px solid #d2d2d7;
    background: rgba(255, 255, 255, 0.8);
    font-size: 15px;
    padding: 16px;
    transition: all 0.3s ease;
    resize: vertical;
}

.custom-textarea :deep(.el-textarea__inner:focus) {
    border-color: #0071e3;
    box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

.button-group {
    margin-top: 30px;
    text-align: center;
}

.submit-btn {
    width: 140px;
    height: 48px;
    border-radius: 12px;
    background: #0071e3;
    border: none;
    font-size: 16px;
    font-weight: 500;
    transition: all 0.3s ease;
    margin-right: 20px;
}

.submit-btn:hover {
    background: #0077ed;
    transform: translateY(-1px);
}

.clear-btn {
    width: 140px;
    height: 48px;
    border-radius: 12px;
    background: #ff3b30;
    border: none;
    color: white;
    font-size: 16px;
    font-weight: 500;
    transition: all 0.3s ease;
}

.clear-btn:hover {
    background: #ff453a;
    transform: translateY(-1px);
}

@media (max-width: 992px) {
    .main-content-grid {
        grid-template-columns: 1fr;
    }
}

@media (max-width: 768px) {
    .page-container {
        padding: 20px;
    }

    .admin-header {
        padding: 10px 20px;
        border-radius: 12px;
    }

    .admin-title {
        font-size: 20px;
    }

    .logout-btn {
        font-size: 12px;
        padding: 6px 10px;
    }

    .info-card,
    .form-card {
        padding: 30px 20px;
    }

    .card-subtitle {
        font-size: 20px;
    }

    .card-description {
        font-size: 14px;
    }

    .info-item {
        font-size: 15px;
    }

    :deep(.el-form-item__label) {
        font-size: 14px;
    }

    :deep(.el-input__inner),
    .custom-textarea :deep(.el-textarea__inner) {
        height: 40px !important;
        font-size: 14px;
        padding: 0 12px;
    }

    .submit-btn,
    .clear-btn {
        width: 120px;
        height: 44px;
        font-size: 15px;
    }
}
</style>