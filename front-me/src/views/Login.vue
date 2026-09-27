<template>
<div class="login-box">
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <img src="/logo.png" alt="logo" class="logo" v-if="$store.state.systemInfo.logo">
        <h1 class="title">{{ $store.state.systemInfo.name }}</h1>
        <p class="subtitle">登录账号</p>
      </div>

      <el-form :model="loginForm" :rules="rules" ref="loginForm" class="login-form">
        <el-form-item prop="username" class="form-item">
          <el-input v-model="loginForm.username" placeholder="用户名" class="custom-input">
          </el-input>
        </el-form-item>

        <el-form-item prop="password" class="form-item">
          <el-input v-model="loginForm.password" type="password" show-password placeholder="密码" class="custom-input"
            @keyup.enter.native="submitForm('loginForm')">
          </el-input>
        </el-form-item>

        <div class="button-group">
          <el-button type="primary" @click="submitForm('loginForm')" class="submit-btn">
            登录
          </el-button>
          <!-- 账号由管理员创建，不提供公开注册；消费者查询溯源无需登录 -->
          <el-button @click="goTrace" class="register-btn">
            溯源查询（免登录）
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</div>
</template>

<script>
import { login } from '@/apis/user'
import { saveLogin } from '@/utils/auth';

export default {
  name: 'login-view',
  data() {
    return {
      loginForm: {
        username: '',
        password: ''
      },
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
        ],
      }
    };
  },
  methods: {
    submitForm(formName) {
      this.$refs[formName].validate(async (valid) => {
        if (!valid) {
          return false;
        }
        const { code, mes, data } = await login(this.loginForm)
        if (code != 200) {
          this.$message.error(mes)
          return
        }
        saveLogin(data)
        this.$message.success('登录成功')
        // 登录过期被跳转过来的：回到原页面（只接受站内相对路径）
        const redirect = this.$route.query.redirect
        if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.startsWith('/login')) {
          this.$router.push(redirect).catch(() => {})
          return
        }
        this.$router.push(data.user.role === 'ADMIN' ? '/admin' : '/userCenter')
      });
    },
    goTrace() {
      this.$router.push('/trace')
    },
  }
};
</script>

<style scoped>
.login-box {
  min-height: 100vh;
  background: url('../assets/imgs/login-bg.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  display: flex;
  align-items: center;
  justify-content: center;
  /* padding: 20px; */
}

.login-container {
  width: 100%;
  max-width: 400px;
}

.login-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.login-header {
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

.login-form {
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

.register-btn {
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

.register-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  border-color: #1d1d1f;
}

:deep(.el-form-item__error) {
  color: #ff3b30;
  font-size: 14px;
  margin-top: 4px;
}

@media (max-width: 480px) {
  .login-card {
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