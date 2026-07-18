<template>
<div class="register-box">
    <div class="register-container">
        <div class="register-card">
            <div class="register-header">
                <img src="/logo.png" alt="logo" class="logo" v-if="$store.state.systemInfo.logo">
                <h1 class="title">{{ $store.state.systemInfo.name }}</h1>
                <p class="subtitle">创建新账号</p>
            </div>

            <el-form :model="registerForm" :rules="rules" ref="registerForm" class="register-form">
                <el-form-item prop="address" class="form-item">
                    <el-input v-model="registerForm.address" placeholder="输入用户地址" class="custom-input">
                    </el-input>
                </el-form-item>

                <el-form-item prop="roleAddress" class="form-item">
                    <el-input v-model="registerForm.roleAddress" placeholder="输入邀请账户地址" class="custom-input">
                    </el-input>
                </el-form-item>

                <el-form-item prop="type" class="form-item">
                    <el-select v-model="registerForm.type" placeholder="选择用户类型" class="type-select">
                        <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value">
                        </el-option>
                    </el-select>
                </el-form-item>

                <div class="button-group">
                    <el-button type="primary" @click="submitForm('registerForm')" class="submit-btn">
                        注册
                    </el-button>
                    <el-button @click="resetForm('registerForm')" class="reset-btn">
                        重置
                    </el-button>
                </div>

                <div class="login-link">
                    <router-link to="/login">已有账号？立即登录</router-link>
                </div>
            </el-form>
        </div>
    </div>
</div>
</template>

<script>
import { register } from '@/apis/user';

export default {
    name: 'register-view',
    data() {
        return {
            registerForm: {
                address: '',
                type: '',
                roleAddress: ''
            },
            rules: {
                roleAddress: [
                    { required: true, message: '请输入邀请账户地址', trigger: 'blur' },
                ],
                address: [
                    { required: true, message: '请输入用户地址', trigger: 'blur' },
                ],
                type: [
                    { required: true, message: '请输入用户类型', trigger: 'blur' },
                ],
            },
            options: [
                {
                    value: '0',
                    label: '生产商'
                },
                {
                    value: '1',
                    label: '分销商'
                },
                {
                    value: '2',
                    label: '零售商'
                }
            ]
        };
    },
    methods: {
        submitForm(formName) {
            this.$refs[formName].validate(async (valid) => {
                if (valid) {
                    const { code,mes } = await register(this.registerForm)
                    if (code == 200) {
                        this.$router.push('/login')
                    }else{
                      this.$message.error(mes)
                    }
                } else {
                    console.log('注册失败!');
                    return false;
                }
            });
        },
        resetForm(formName) {
            this.$refs[formName].resetFields();
        }
    }
};
</script>

<style scoped>
.register-box {
    min-height: 100vh;
    background: url('../assets/imgs/register-bg.png');
    background-size: cover;
    background-position: center;
    background-repeat: no-repeat;
    display: flex;
    align-items: center;
    justify-content: center;
}

.register-container {
    width: 100%;
    max-width: 400px;
}

.register-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.3);
}

.register-header {
    text-align: center;
    margin-bottom: 40px;
}

.logo {
    width: 64px;
    height: 64px;
    margin-bottom: 16px;
}

.title {
    font-size: 28px;
    font-weight: 600;
    color: #1d1d1f;
    margin: 0;
    letter-spacing: -0.5px;
}

.subtitle {
    font-size: 16px;
    color: #86868b;
    margin-top: 8px;
    font-weight: 400;
}

.register-form {
    margin-top: 20px;
}

.form-item {
    margin-bottom: 20px;
}

:deep(.el-input__inner) {
    height: 48px;
    border-radius: 12px;
    border: 1px solid #d2d2d7;
    background: rgba(255, 255, 255, 0.8);
    font-size: 16px;
    padding: 0 16px;
    transition: all 0.3s ease;
}

:deep(.el-input__inner:focus) {
    border-color: #0071e3;
    box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

:deep(.el-select .el-input__inner) {
    padding-right: 30px;
}

.button-group {
    margin-top: 32px;
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.submit-btn {
    width: 100%;
    height: 48px;
    border-radius: 12px;
    background: #0071e3;
    border: none;
    font-size: 16px;
    font-weight: 500;
    transition: all 0.3s ease;
}

.submit-btn:hover {
    background: #0077ed;
    transform: translateY(-1px);
}

.reset-btn {
    width: 100%;
    height: 48px;
    border-radius: 12px;
    background: transparent;
    border: 1px solid #d2d2d7;
    color: #1d1d1f;
    font-size: 16px;
    font-weight: 500;
    transition: all 0.3s ease;
}

.reset-btn:hover {
    background: rgba(0, 0, 0, 0.05);
    border-color: #1d1d1f;
}

.login-link {
    text-align: center;
    margin-top: 20px;
}

.login-link a {
    color: #0071e3;
    text-decoration: none;
    font-size: 14px;
    transition: color 0.3s ease;
}

.login-link a:hover {
    color: #0077ed;
}

:deep(.el-form-item__error) {
    color: #ff3b30;
    font-size: 14px;
    margin-top: 4px;
}

@media (max-width: 480px) {
    .register-card {
        padding: 30px 20px;
    }

    .title {
        font-size: 24px;
    }

    .subtitle {
        font-size: 14px;
    }
}
</style>